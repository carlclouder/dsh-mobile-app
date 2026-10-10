/**
 * 直连 /api/remote.mux 抓取 workspace/follow 的真实帧形状（用于校准 App 端解析）。
 *
 * 步骤：① 用进程令牌换会话 Cookie ② 以该 Cookie 连 WS ③ 发 open 帧 ④ 打印前若干原始帧。
 * 运行位置：本脚本从 dsh 包内解析 ws 依赖（裸包名解析走脚本自身路径）。
 */
const path = require("node:path");
const http = require("node:http");
const fs = require("node:fs");

const DSH_NODE_MODULES = "C:/Users/Carl/AppData/Roaming/npm/node_modules/@deepseek-ai/dsh/node_modules";
const WebSocket = require(path.join(DSH_NODE_MODULES, "ws"));

const LOG = "C:/Users/Carl/.dsh/logs/dsh-webui.log";
const PORT = 3080;

/** 从 dsh 启动日志里读当前令牌。 */
function readToken() {
	const text = fs.readFileSync(LOG, "utf8");
	const matches = [...text.matchAll(/dsh web: http:\/\/127\.0\.0\.1:\d+\/\?token=([A-Za-z0-9_-]+)/g)];
	if (matches.length === 0) throw new Error("日志中未找到令牌");
	return matches[matches.length - 1][1];
}

/** 用令牌换会话 Cookie。 */
function exchangeCookie(token) {
	return new Promise((resolve, reject) => {
		const req = http.request(
			{ host: "127.0.0.1", port: PORT, path: `/?token=${token}`, method: "GET" },
			(res) => {
				const raw = res.headers["set-cookie"];
				res.resume();
				if (Array.isArray(raw) && raw.length > 0) {
					resolve(raw.map((one) => String(one).split(";")[0]).join("; "));
				} else {
					reject(new Error(`未拿到 Set-Cookie（HTTP ${res.statusCode}）`));
				}
			},
		);
		req.on("error", reject);
		req.end();
	});
}

(async () => {
	const token = readToken();
	const cookie = await exchangeCookie(token);
	console.log("cookie 就绪:", cookie.slice(0, 32) + "...");

	const ws = new WebSocket(`ws://127.0.0.1:${PORT}/api/remote.mux`, { headers: { Cookie: cookie } });
	ws.on("open", () => {
		console.log("WS 已连接，发送 open 帧 (workspace/follow)");
		ws.send(JSON.stringify({
			type: "open",
			streamId: "probe-workspace-1",
			endpoint: "workspace/follow",
			// 服务端要求 payload 恰含一个 args 字段（实测报错 "Remote payload must contain exactly one plain-object args field"）
			payload: { args: {} },
		}));
	});
	let count = 0;
	ws.on("message", (data) => {
		count += 1;
		const text = data.toString();
		console.log(`\n--- 帧 ${count}（${text.length} 字节）---`);
		console.log(text.slice(0, 900));
		if (count >= 3) {
			console.log("\n已收到 3 帧，关闭探测连接");
			ws.close();
			process.exit(0);
		}
	});
	ws.on("error", (error) => {
		console.log("WS 错误:", error.message);
		process.exit(1);
	});
	ws.on("close", (code, reason) => {
		console.log(`WS 关闭 code=${code} reason=${reason}`);
		if (count === 0) process.exit(1);
	});
	setTimeout(() => {
		console.log(`\n超时退出（收到 ${count} 帧）`);
		process.exit(count > 0 ? 0 : 1);
	}, 8000);
})();
