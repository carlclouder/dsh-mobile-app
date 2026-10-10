/**
 * 列出全部"无标题（App 会用目录末段当标题显示）"的未归档会话，供用户指定归档范围。
 */
const path = require("node:path");
const http = require("node:http");
const fs = require("node:fs");

const DSH_NODE_MODULES = "C:/Users/Carl/AppData/Roaming/npm/node_modules/@deepseek-ai/dsh/node_modules";
const WebSocket = require(path.join(DSH_NODE_MODULES, "ws"));
const LOG = "C:/Users/Carl/.dsh/logs/dsh-webui.log";
const PORT = 3080;
const DAY = 86_400_000;

const readToken = () => {
	const text = fs.readFileSync(LOG, "utf8");
	const all = [...text.matchAll(/dsh web: http:\/\/127\.0\.0\.1:\d+\/\?token=([A-Za-z0-9_-]+)/g)];
	return all[all.length - 1][1];
};
const fetchCookie = (token) =>
	new Promise((resolve, reject) => {
		const req = http.request({ host: "127.0.0.1", port: PORT, path: `/?token=${token}`, method: "GET" }, (res) => {
			const raw = res.headers["set-cookie"];
			res.resume();
			if (Array.isArray(raw) && raw.length) resolve(raw.map((c) => String(c).split(";")[0]).join("; "));
			else reject(new Error("no set-cookie"));
		});
		req.on("error", reject);
		req.end();
	});
const rpc = (method, payload, cookie) =>
	new Promise((resolve, reject) => {
		const body = JSON.stringify({ type: "client-request", rpcId: `p-${method}`, method, payload });
		const req = http.request(
			{ host: "127.0.0.1", port: PORT, path: `/api/${method}`, method: "POST",
			  headers: { "content-type": "application/json", cookie, "content-length": Buffer.byteLength(body) } },
			(res) => { let d = ""; res.on("data", (c) => (d += c)); res.on("end", () => resolve(JSON.parse(d))); },
		);
		req.on("error", reject);
		req.end(body);
	});

(async () => {
	const cookie = await fetchCookie(readToken());
	const list = await rpc("session/list", { args: { _request: {} } }, cookie);
	const items = list.result.value.items;

	const ws = new WebSocket(`ws://127.0.0.1:${PORT}/api/remote.mux`, { headers: { Cookie: cookie } });
	const baseline = await new Promise((resolve, reject) => {
		ws.on("open", () => ws.send(JSON.stringify({ type: "open", streamId: "probe-list", endpoint: "workspace/follow", payload: { args: {} } })));
		ws.on("message", (d) => {
			const f = JSON.parse(d.toString());
			if (f.type === "item" && f.value?.type === "baseline") resolve(f.value.value);
		});
		ws.on("error", reject);
		setTimeout(() => reject(new Error("baseline 超时")), 8000);
	});
	ws.close();

	const archived = new Set(baseline.archivedSessionIds.map(String));
	const now = Date.now();
	const untitled = items
		.filter((it) => !archived.has(String(it.sessionId)))
		.filter((it) => !it.projections?.values?.title)
		.sort((a, b) => Number(b.updatedAt) - Number(a.updatedAt));

	console.log(`无标题的未归档会话：${untitled.length} 个（App 会显示为"目录末段"标题）\n`);
	console.log("序号 | 最后活动 | App 显示标题(目录末段) | 工作目录 | sessionId");
	untitled.forEach((it, index) => {
		const days = Math.round((now - Number(it.updatedAt)) / DAY * 10) / 10;
		const cwd = String(it.cwd ?? "");
		const shown = cwd.split(/[\\/]/).filter(Boolean).pop() ?? "(无)";
		console.log(`${String(index + 1).padStart(3)} | ${String(days).padStart(6)} 天前 | ${shown} | ${cwd} | ${it.sessionId}`);
	});
})();
