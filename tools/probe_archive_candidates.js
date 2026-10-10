/**
 * 归档候选分析：找出"无标题 + 长期无活动"的废弃会话，按不同阈值统计规模。
 * 归档操作可逆（宿主支持取消归档），因此先看清范围再执行。
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
		ws.on("open", () => ws.send(JSON.stringify({ type: "open", streamId: "probe-arch2", endpoint: "workspace/follow", payload: { args: {} } })));
		ws.on("message", (d) => {
			const f = JSON.parse(d.toString());
			if (f.type === "item" && f.value?.type === "baseline") resolve(f.value.value);
		});
		ws.on("error", reject);
		setTimeout(() => reject(new Error("baseline 超时")), 8000);
	});
	ws.close();

	const archived = new Set(baseline.archivedSessionIds.map(String));
	const active = items.filter((it) => !archived.has(String(it.sessionId)));
	const now = Date.now();
	const ageDays = (it) => (now - Number(it.updatedAt ?? 0)) / DAY;
	const untitled = active.filter((it) => !it.projections?.values?.title);

	console.log(`未归档会话 ${active.length} 个；其中无标题 ${untitled.length} 个\n`);
	console.log('无标题会话按“最后活动时间”分布：');
	for (const days of [7, 14, 30, 60, 90, 180]) {
		const count = untitled.filter((it) => ageDays(it) > days).length;
		console.log(`  超过 ${String(days).padStart(3)} 天未活动: ${count} 个`);
	}

	console.log("\n候选明细（无标题且超过 30 天未活动，最多列 40 条）:");
	const candidates = untitled.filter((it) => ageDays(it) > 30).sort((a, b) => a.updatedAt - b.updatedAt);
	for (const it of candidates.slice(0, 40)) {
		const title = it.projections?.values?.title ?? "(无标题)";
		console.log(`  ${String(Math.round(ageDays(it))).padStart(4)} 天前 | ${it.sessionId} | ${title} | ${it.cwd}`);
	}
	console.log(`\n候选总计: ${candidates.length} 个`);
	const byCwd = new Map();
	for (const it of candidates) {
		const key = String(it.cwd ?? "(无)");
		byCwd.set(key, (byCwd.get(key) ?? 0) + 1);
	}
	console.log("候选按目录分布:");
	for (const [cwd, count] of [...byCwd.entries()].sort((a, b) => b[1] - a[1])) {
		console.log(`  ${String(count).padStart(3)} 个 ← ${cwd}`);
	}
})();
