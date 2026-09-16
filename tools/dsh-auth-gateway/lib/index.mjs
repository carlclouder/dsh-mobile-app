/**
 * dsh 免令牌网关插件（dsh-auth-gateway）
 *
 * 解决什么问题：dsh 0.1.2-rc.1 起 WebUI 启用链接一次性令牌认证，裸地址会 401。
 * 手机 App 想"只填裸地址、不带令牌"访问，就需要有人替它把令牌换成会话凭证。
 *
 * 本插件的做法：**在 dsh 进程内**起一个本地反向代理（默认 127.0.0.1:3081），
 * 把进来的请求转发给 dsh 自身的 Web 服务（默认 127.0.0.1:3080），并自动注入有效会话 Cookie。
 * 令牌直接从 dsh 内部服务取（connection.authenticatedUrl，官方 dsh-web-app 打印
 * "dsh web: http://.../?token=..." 用的就是它），因此不需要读日志、不需要外部脚本。
 *
 * 生命周期：完全由 dsh 管理——dsh 启动时插件加载并监听，dsh 退出时监听随进程结束。
 * 不产生独立常驻进程、不需要计划任务、不需要修改任何启动脚本（bat）。
 *
 * 部署形态（配合 Tailscale Serve）：
 *   dsh web 监听  127.0.0.1:3080
 *   本插件监听    127.0.0.1:3081  （自动注入会话 Cookie）
 *   tailscale serve  443 → 127.0.0.1:3081
 *   → 手机浏览器/App 用裸地址 https://<机器名>.<tailnet>.ts.net/ 即可访问
 *
 * 安全边界：仍只监听 loopback；远程可见性由 Tailscale Serve 决定。
 * 注入的会话 Cookie 有效期由 dsh 的 cookieMaxAgeDays 决定（默认 30 天），
 * 过期或被拒（401）时插件会自动重新用进程令牌换一次。
 */
import http from "node:http";
import { request as httpRequest } from "node:http";
import z from "@deepseek-ai/schemastery";

/** 稳定插件名（Cordis 用）。 */
export const name = "dsh-auth-gateway";

/** 需要 dsh 的 connection 服务（提供带进程令牌的认证 URL）。 */
export const inject = ["connection"];

export const Config = z.object({
	/** 网关监听端口（Tailscale Serve 应指向这里）。 */
	port: z.number().default(3081),
	/** 上游主机：dsh 自身 Web 服务。 */
	upstreamHost: z.string().default("127.0.0.1"),
	/** 上游端口：dsh 自身 Web 服务端口。 */
	upstreamPort: z.number().default(3080),
	/** 会话 Cookie 主动刷新间隔（毫秒），默认 6 小时；401 时会立即被动刷新。 */
	cookieRefreshMs: z.number().default(6 * 60 * 60 * 1000),
	/**
	 * 是否放行"经 Tailscale Serve 转发进来的请求"（默认开启）。
	 * 开启后：Host 命中 dsh 受信权威（`--trusted-host` 名单）且来源为 loopback 的请求
	 * 直接视为已认证 —— 手机用裸地址即可访问，且 Serve 仍指向 3080、无需改动任何启动脚本。
	 * 安全含义：信任边界落在 Tailscale 私有网络（tailnet 内可达者免令牌；公网仍不可达）。
	 * 关闭后恢复 dsh 原生认证（裸地址 401，需带令牌链接或经下方反向代理端口访问）。
	 */
	allowTailnetForwarded: z.boolean().default(true)
});

/**
 * 插件入口：启动本地反向代理并绑定生命周期。
 * @param {object} ctx - Cordis 插件上下文（已注入 connection）。
 * @param {object} config - 已校验的插件配置。
 */
export function apply(ctx, config) {
	const upstreamHost = config.upstreamHost ?? "127.0.0.1";
	const upstreamPort = config.upstreamPort ?? 3080;
	const upstreamAuthority = `${upstreamHost}:${upstreamPort}`;

	// -------------------------------------------------------------------
	// 免令牌放行：让"经 Tailscale Serve 转发进来"的请求直接通过认证
	// -------------------------------------------------------------------
	// 为什么需要它：用户环境的启动脚本会把 Tailscale Serve 固定指向 3080（dsh 本体），
	// 因此"另起网关端口 + Serve 指向网关"的路线会被脚本覆盖。改为在 dsh 自己的认证判定上
	// 放行 Serve 转发的请求，则 Serve 保持 3080 不动、任何启动脚本都无需修改。
	//
	// 判定条件（两个同时满足才放行，避免扩大范围）：
	//   ① 来源地址是 loopback —— Tailscale Serve 是本机进程，转发进来的请求必来自 127.0.0.1；
	//   ② Host 命中 dsh 的受信权威名单（--trusted-host 传入的 tailnet 域名）——
	//      本机浏览器用 127.0.0.1:3080 访问时 Host 不受信，仍走原生认证。
	// 关掉配置项 allowTailnetForwarded 即可恢复原生认证行为。
	if (config.allowTailnetForwarded !== false) {
		const trustedHosts = ctx.connection?.trustedHosts;
		const trustedList = Array.isArray(trustedHosts)
			? trustedHosts
			: typeof trustedHosts?.[Symbol.iterator] === "function"
				? Array.from(trustedHosts)
				: [];
		const isLoopback = (address) =>
			address === "127.0.0.1" || address === "::1" || address === "::ffff:127.0.0.1";
		const isForwardedFromTailnet = (request) => {
			if (!isLoopback(request?.socket?.remoteAddress)) return false;
			const host = String(request?.headers?.host ?? "").split(":")[0].toLowerCase();
			if (host === "") return false;
			return trustedList.some((entry) => String(entry).split(":")[0].toLowerCase() === host);
		};
		const originalRejection = ctx.connection.requestRejection.bind(ctx.connection);
		ctx.connection.requestRejection = (request) => {
			const verdict = originalRejection(request);
			if (verdict === undefined) return undefined; // 原生认证已通过
			return isForwardedFromTailnet(request) ? undefined : verdict;
		};
		// 首页（HTML 界面）走的是另一条认证入口 authorizeIndex（不在 requestRejection 上）：
		// 实测只补 requestRejection 时 API 已放行、但根路径仍 401。此处对 Serve 转发的请求
		// 直接放行（返回 true 表示"可以渲染首页"，不调用原生逻辑，避免它已写出 303/401 响应后冲突）。
		const originalAuthorizeIndex = ctx.connection.authorizeIndex.bind(ctx.connection);
		ctx.connection.authorizeIndex = (req, res) => {
			if (isForwardedFromTailnet(req)) return true;
			return originalAuthorizeIndex(req, res);
		};
		console.log(
			`[auth-gateway] 已开启 tailnet 免令牌放行（受信 Host: ${trustedList.join(", ") || "（空）"}）`
		);
	}

	/** 会话 Cookie 缓存（进程内单份；上游按 Host 绑定签发，故与转发时的 Host 必须一致）。 */
	let cookieValue = null;
	let cookieFetchedAt = 0;
	/** 并发去重：多个请求同时发现无 Cookie 时只换一次。 */
	let inflight = null;

	/**
	 * 用 dsh 进程令牌换一份会话 Cookie。
	 * 机制：connection.authenticatedUrl() 给出带令牌的根地址，GET 它会被 dsh 换成
	 * 303 + Set-Cookie（官方 authorizeIndex 行为）。令牌是进程内变量，无需读日志。
	 * @returns {Promise<string|null>} Cookie 头值；失败返回 null（此时转发不带 Cookie，由上游 401 暴露）。
	 */
	function fetchSessionCookie() {
		if (inflight) return inflight;
		inflight = new Promise((resolve) => {
			let url;
			try {
				url = ctx.connection.authenticatedUrl(`http://${upstreamAuthority}/`);
			} catch (error) {
				console.log(`[auth-gateway] 取认证地址失败: ${error?.message ?? error}`);
				resolve(null);
				return;
			}
			const probe = httpRequest(url, { method: "GET", headers: { host: upstreamAuthority } }, (res) => {
				const raw = res.headers["set-cookie"];
				res.resume(); // 丢弃响应体（303 无实质内容）
				if (Array.isArray(raw) && raw.length > 0) {
					// 只取 name=value 段（丢属性），多份 Cookie 以 ";" 连接
					resolve(raw.map((one) => String(one).split(";")[0]).join("; "));
				} else {
					console.log(`[auth-gateway] 令牌换取 Cookie 失败: HTTP ${res.statusCode}`);
					resolve(null);
				}
			});
			probe.on("error", (error) => {
				console.log(`[auth-gateway] 换取 Cookie 请求出错: ${error?.message ?? error}`);
				resolve(null);
			});
			probe.setTimeout(5000, () => probe.destroy());
			probe.end();
		}).finally(() => {
			inflight = null;
		});
		return inflight;
	}

	/**
	 * 取可用会话 Cookie（带缓存与过期刷新）。
	 * @param {boolean} force - true 时强制重新换取（401 自愈路径）。
	 * @returns {Promise<string|null>}
	 */
	async function ensureCookie(force) {
		const now = Date.now();
		const expired = cookieValue === null || now - cookieFetchedAt > (config.cookieRefreshMs ?? 0);
		if (!force && !expired) return cookieValue;
		const fresh = await fetchSessionCookie();
		if (fresh !== null) {
			cookieValue = fresh;
			cookieFetchedAt = now;
			console.log("[auth-gateway] 已刷新会话 Cookie");
		}
		return cookieValue;
	}

	/**
	 * 构造转发到上游的请求头：Host 改写为上游、剥离 Origin/Referer、
	 * 注入会话 Cookie（覆盖客户端自带的旧值，保证用网关这一份有效凭证）。
	 */
	function upstreamHeaders(clientHeaders, cookie) {
		const headers = { ...clientHeaders, host: upstreamAuthority };
		// Origin/Referer 指向的是外部域名；剥离后按 loopback 同源处理，避免触发信任围栏的跨源判定
		delete headers.origin;
		delete headers.referer;
		if (cookie) headers.cookie = cookie;
		else delete headers.cookie;
		return headers;
	}

	/** 普通 HTTP 请求转发（含一次 401 自愈重试）。 */
	function forward(clientReq, clientRes, cookie, retried) {
		const upstream = httpRequest(
			{
				host: upstreamHost,
				port: upstreamPort,
				method: clientReq.method,
				path: clientReq.url,
				headers: upstreamHeaders(clientReq.headers, cookie)
			},
			(upRes) => {
				// 401：凭证过期/被拒 → 强制刷新后重试一次（只重试一次，避免死循环）
				if (upRes.statusCode === 401 && !retried) {
					upRes.resume();
					console.log("[auth-gateway] 上游 401，刷新 Cookie 后重试");
					ensureCookie(true).then((fresh) => forward(clientReq, clientRes, fresh, true));
					return;
				}
				clientRes.writeHead(upRes.statusCode ?? 502, upRes.headers);
				upRes.pipe(clientRes);
			}
		);
		upstream.on("error", (error) => {
			console.log(`[auth-gateway] 转发上游出错: ${error?.message ?? error}`);
			if (!clientRes.headersSent) clientRes.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
			clientRes.end("auth-gateway: 上游不可达");
		});
		clientReq.pipe(upstream);
	}

	const server = http.createServer((clientReq, clientRes) => {
		ensureCookie(false)
			.then((cookie) => forward(clientReq, clientRes, cookie, false))
			.catch((error) => {
				console.log(`[auth-gateway] 处理请求异常: ${error?.message ?? error}`);
				if (!clientRes.headersSent) clientRes.writeHead(500);
				clientRes.end();
			});
	});

	// WebSocket 升级同样转发（浏览器端实时通道；App 端走轮询，但保持完整能力）
	server.on("upgrade", (clientReq, socket, head) => {
		ensureCookie(false).then((cookie) => {
			const upstream = httpRequest({
				host: upstreamHost,
				port: upstreamPort,
				method: clientReq.method,
				path: clientReq.url,
				headers: upstreamHeaders(clientReq.headers, cookie)
			});
			upstream.on("upgrade", (upRes, upstreamSocket, upstreamHead) => {
				const headerLines = Object.entries(upRes.headers)
					.map(([key, value]) => `${key}: ${value}`)
					.join("\r\n");
				socket.write(`HTTP/1.1 101 Switching Protocols\r\n${headerLines}\r\n\r\n`);
				if (upstreamHead?.length) upstreamSocket.unshift(upstreamHead);
				if (head?.length) upstream.write(head);
				upstreamSocket.pipe(socket);
				socket.pipe(upstreamSocket);
				const teardown = () => {
					upstreamSocket.destroy();
					socket.destroy();
				};
				upstreamSocket.on("error", teardown);
				socket.on("error", teardown);
			});
			// 非 101（如 401/403）：把上游响应原样回给客户端，便于排查
			upstream.on("response", (upRes) => {
				socket.write(`HTTP/1.1 ${upRes.statusCode} ${upRes.statusMessage ?? ""}\r\n\r\n`);
				upRes.pipe(socket);
			});
			upstream.on("error", (error) => {
				console.log(`[auth-gateway] WebSocket 转发出错: ${error?.message ?? error}`);
				socket.destroy();
			});
			upstream.end();
		});
	});

	server.on("error", (error) => {
		console.log(`[auth-gateway] 监听 ${config.port} 失败: ${error?.message ?? error}`);
	});

	// 生命周期交给 dsh：插件卸载时关闭监听；进程退出时随之消失
	ctx.effect(() => {
		server.listen(config.port ?? 3081, "127.0.0.1", () => {
			console.log(
				`[auth-gateway] 免令牌网关已就绪: http://127.0.0.1:${config.port ?? 3081} → http://${upstreamAuthority}`
			);
		});
		return () => {
			try {
				server.close();
			} catch {
				// 关闭失败不影响进程退出
			}
		};
	}, "dsh-auth-gateway: reverse proxy");
}
