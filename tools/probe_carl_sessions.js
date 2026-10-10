/**
 * 精确核对：界面上出现的会话（尤其标题为 Carl、路径 C:\Users\Carl 的）是否属于归档集合。
 * 输出三组：①归档集合里标题为 Carl 的会话 ②未归档里标题为 Carl 的会话 ③按归档过滤后的完整可见清单统计。
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
		ws.on("open", () => ws.send(JSON.stringify({ type: "open", streamId: "probe-carl", endpoint: "workspace/follow", payload: { args: {} } })));
		ws.on("message", (d) => {
			const f = JSON.parse(d.toString());
			if (f.type === "item" && f.value?.type === "baseline") resolve(f.value.value);
		});
		ws.on("error", reject);
		setTimeout(() => reject(new Error("baseline 超时")), 8000);
	});
	ws.close();

	const archived = new Set(baseline.archivedSessionIds.map(String));
	const titleOf = (it) => it.projections?.values?.title ?? "(无标题)";

	const carlAll = items.filter((it) => titleOf(it) === "Carl");
	const carlArchived = carlAll.filter((it) => archived.has(String(it.sessionId)));
	const carlVisible = carlAll.filter((it) => !archived.has(String(it.sessionId)));

	console.log(`会话总数: ${items.length} | 归档: ${archived.size} | 过滤后应显示: ${items.length - archived.size}`);
	console.log(`\n标题为 "Carl" 的会话共 ${carlAll.length} 个：`);
	console.log(`  其中【已归档】（App 不应显示）: ${carlArchived.length}`);
	console.log(`  其中【未归档】（App 应显示）  : ${carlVisible.length}`);
	if (carlArchived.length) {
		console.log("\n已归档的 Carl 会话（前 10，sessionId 供 UI dump 比对）:");
		for (const it of carlArchived.slice(0, 10)) console.log("  -", it.sessionId, "|", it.cwd);
	}
	if (carlVisible.length) {
		console.log("\n未归档的 Carl 会话（前 10）:");
		for (const it of carlVisible.slice(0, 10)) console.log("  -", it.sessionId, "|", it.cwd, "| updatedAt", new Date(it.updatedAt).toISOString());
	}
})();
