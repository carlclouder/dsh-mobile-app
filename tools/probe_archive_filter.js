/**
 * 计算归档过滤的期望值：对比 session/list 全量与会话归档集合，
 * 并取出若干"已归档会话标题"，供 UI dump 比对（用于验证 App 是否真的隐藏了归档会话）。
 */
const path = require("node:path");
const http = require("node:http");
const fs = require("node:fs");

const DSH_NODE_MODULES = "C:/Users/Carl/AppData/Roaming/npm/node_modules/@deepseek-ai/dsh/node_modules";
const WebSocket = require(path.join(DSH_NODE_MODULES, "ws"));
const LOG = "C:/Users/Carl/.dsh/logs/dsh-webui.log";
const PORT = 3080;

function readToken() {
	const text = fs.readFileSync(LOG, "utf8");
	const all = [...text.matchAll(/dsh web: http:\/\/127\.0\.0\.1:\d+\/\?token=([A-Za-z0-9_-]+)/g)];
	return all[all.length - 1][1];
}

function fetchCookie(token) {
	return new Promise((resolve, reject) => {
		const req = http.request({ host: "127.0.0.1", port: PORT, path: `/?token=${token}`, method: "GET" }, (res) => {
			const raw = res.headers["set-cookie"];
			res.resume();
			if (Array.isArray(raw) && raw.length) resolve(raw.map((one) => String(one).split(";")[0]).join("; "));
			else reject(new Error("no set-cookie"));
		});
		req.on("error", reject);
		req.end();
	});
}

function rpc(method, payload, cookie) {
	return new Promise((resolve, reject) => {
		const body = JSON.stringify({ type: "client-request", rpcId: `probe-${method}`, method, payload });
		const req = http.request(
			{ host: "127.0.0.1", port: PORT, path: `/api/${method}`, method: "POST",
			  headers: { "content-type": "application/json", cookie, "content-length": Buffer.byteLength(body) } },
			(res) => {
				let data = "";
				res.on("data", (chunk) => (data += chunk));
				res.on("end", () => resolve(JSON.parse(data)));
			},
		);
		req.on("error", reject);
		req.end(body);
	});
}

(async () => {
	const cookie = await fetchCookie(readToken());
	const list = await rpc("session/list", { args: { _request: {} } }, cookie);
	const items = list.result.value.items;

	// 取工作区 baseline（归档集合所在）
	const ws = new WebSocket(`ws://127.0.0.1:${PORT}/api/remote.mux`, { headers: { Cookie: cookie } });
	const baseline = await new Promise((resolve, reject) => {
		ws.on("open", () => ws.send(JSON.stringify({
			type: "open", streamId: "probe-arch", endpoint: "workspace/follow", payload: { args: {} },
		})));
		ws.on("message", (data) => {
			const frame = JSON.parse(data.toString());
			if (frame.type === "item" && frame.value?.type === "baseline") resolve(frame.value.value);
		});
		ws.on("error", reject);
		setTimeout(() => reject(new Error("baseline 超时")), 8000);
	});
	ws.close();

	const archived = new Set(baseline.archivedSessionIds.map(String));
	const total = items.length;
	const visible = items.filter((it) => !archived.has(String(it.sessionId)));
	const hiddenSamples = items.filter((it) => archived.has(String(it.sessionId))).slice(0, 8);

	console.log(`会话总数(session/list): ${total}`);
	console.log(`归档集合大小: ${archived.size}`);
	console.log(`按归档过滤后应显示: ${visible.length}`);
	console.log("\n应被隐藏的归档会话标题样本（App 界面上不应出现）:");
	for (const it of hiddenSamples) {
		console.log("  -", it.projections?.values?.title ?? "(无标题)", "|", it.sessionId);
	}
	console.log("\n应显示的活跃会话标题样本（App 界面上应出现）:");
	for (const it of visible.slice(0, 5)) {
		console.log("  -", it.projections?.values?.title ?? "(无标题)");
	}
})();
