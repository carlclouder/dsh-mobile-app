/**
 * 按工作目录核对：cwd = C:\Users\Carl 的会话共有多少、其中归档多少。
 * 这类会话在 App 上会以"路径末段 Carl"作为标题显示（宿主未给 title），
 * 正是用户说的"大量 Carl 会话"。
 */
const path = require("node:path");
const http = require("node:http");
const fs = require("node:fs");

const DSH_NODE_MODULES = "C:/Users/Carl/AppData/Roaming/npm/node_modules/@deepseek-ai/dsh/node_modules";
const WebSocket = require(path.join(DSH_NODE_MODULES, "ws"));
const LOG = "C:/Users/Carl/.dsh/logs/dsh-webui.log";
const PORT = 3080;

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
		ws.on("open", () => ws.send(JSON.stringify({ type: "open", streamId: "probe-cwd", endpoint: "workspace/follow", payload: { args: {} } })));
		ws.on("message", (d) => {
			const f = JSON.parse(d.toString());
			if (f.type === "item" && f.value?.type === "baseline") resolve(f.value.value);
		});
		ws.on("error", reject);
		setTimeout(() => reject(new Error("baseline 超时")), 8000);
	});
	ws.close();

	const archived = new Set(baseline.archivedSessionIds.map(String));
	const noTitle = items.filter((it) => !(it.projections?.values?.title));
	const carlCwd = items.filter((it) => String(it.cwd ?? "").toLowerCase() === "c:\\users\\carl");

	console.log(`会话总数 ${items.length} | 归档 ${archived.size} | 过滤后应显示 ${items.length - archived.size}`);
	console.log(`\n【无 title 的会话】共 ${noTitle.length} 个（App 会用 cwd 末段当标题显示）`);
	console.log(`  其中 cwd = C:\\Users\\Carl 的: ${carlCwd.length} 个`);
	console.log(`    已归档（App 不应显示）: ${carlCwd.filter((it) => archived.has(String(it.sessionId))).length}`);
	console.log(`    未归档（App 应显示）  : ${carlCwd.filter((it) => !archived.has(String(it.sessionId))).length}`);

	console.log("\n按 cwd 分组统计（无 title 会话的分布）:");
	const byCwd = new Map();
	for (const it of noTitle) {
		const key = String(it.cwd ?? "(无)");
		byCwd.set(key, (byCwd.get(key) ?? 0) + 1);
	}
	for (const [cwd, count] of [...byCwd.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8)) {
		console.log(`  ${count} 个 ← ${cwd}`);
	}
})();
