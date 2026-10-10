/**
 * 归档"废弃会话"：默认归档 cwd = C:\Users\Carl 的无标题会话（用户在 App 上看到的 Carl 条目）。
 * 归档可逆（宿主支持取消归档）。
 *
 * 用法：node archive_sessions.js            → 归档 Carl 目录下的无标题会话
 *       node archive_sessions.js --dry-run  → 只列出将归档的会话，不执行
 */
const path = require("node:path");
const http = require("node:http");
const fs = require("node:fs");

const DSH_NODE_MODULES = "C:/Users/Carl/AppData/Roaming/npm/node_modules/@deepseek-ai/dsh/node_modules";
const WebSocket = require(path.join(DSH_NODE_MODULES, "ws"));
const LOG = "C:/Users/Carl/.dsh/logs/dsh-webui.log";
const PORT = 3080;
const DRY_RUN = process.argv.includes("--dry-run");
const TARGET_CWD = "c:\\users\\carl";

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
const readArchived = async (cookie) => {
	const ws = new WebSocket(`ws://127.0.0.1:${PORT}/api/remote.mux`, { headers: { Cookie: cookie } });
	const baseline = await new Promise((resolve, reject) => {
		ws.on("open", () => ws.send(JSON.stringify({ type: "open", streamId: `baseline-${Date.now()}`, endpoint: "workspace/follow", payload: { args: {} } })));
		ws.on("message", (d) => {
			const f = JSON.parse(d.toString());
			if (f.type === "item" && f.value?.type === "baseline") resolve(f.value.value);
		});
		ws.on("error", reject);
		setTimeout(() => reject(new Error("baseline 超时")), 8000);
	});
	ws.close();
	return baseline.archivedSessionIds.map(String);
};

(async () => {
	const cookie = await fetchCookie(readToken());
	const list = await rpc("session/list", { args: { _request: {} } }, cookie);
	const items = list.result.value.items;
	const before = await readArchived(cookie);
	const archivedSet = new Set(before);

	const targets = items.filter((it) =>
		!archivedSet.has(String(it.sessionId)) &&
		!it.projections?.values?.title &&
		String(it.cwd ?? "").toLowerCase() === TARGET_CWD
	);

	console.log(`归档前：归档集合 ${before.length} 个；本次目标 ${targets.length} 个（cwd=${TARGET_CWD} 且无标题且未归档）`);
	for (const it of targets) console.log(`  - ${it.sessionId} | ${it.cwd}`);

	if (DRY_RUN) {
		console.log("\n[dry-run] 未执行归档。");
		return;
	}

	let ok = 0;
	for (const it of targets) {
		const res = await rpc("workspace/archiveSession", { args: { request: { sessionId: String(it.sessionId) } } }, cookie);
		const result = res?.result;
		if (result?.ok) {
			ok += 1;
			console.log(`  ✓ 已归档 ${it.sessionId}（响应 archivedSessionIds 数量 ${result.value?.archivedSessionIds?.length ?? "?"}）`);
		} else {
			console.log(`  ✗ 归档失败 ${it.sessionId}: ${JSON.stringify(result?.error ?? res).slice(0, 200)}`);
		}
	}

	const after = await readArchived(cookie);
	console.log(`\n归档后：归档集合 ${after.length} 个（增加 ${after.length - before.length}）`);
	console.log(`成功条数：${ok}/${targets.length}`);
	const stillVisible = items.filter((it) => !new Set(after).has(String(it.sessionId)) && !it.projections?.values?.title && String(it.cwd ?? "").toLowerCase() === TARGET_CWD);
	console.log(`仍未归档的同目录无标题会话：${stillVisible.length} 个`);
})();
