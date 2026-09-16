#!/usr/bin/env node
/**
 * dsh-auth-proxy —— 新版 dsh(≥0.1.2-rc.1) WebUI 一次性令牌认证的本地自动登录代理。
 *
 * 背景：新版 dsh web 启动时随机生成一次性令牌（每次重启换新），所有无 Cookie 的请求
 * 一律 401。浏览器首次用 `?token=<令牌>` 打开会得到 303 + 会话 Cookie（30 天），
 * 之后带 Cookie 的任意请求（含裸地址、WebSocket）直接放行。
 *
 * 本代理的作用：让 App / Tailscale Serve / 任何客户端 **继续使用无 token 的地址**：
 *   客户端 → 本代理(如 3080) → 自动附会话 Cookie → dsh(如 3082)
 * - 启动时与每次收到上游 401 时，自动从 dsh 启动日志提取**最新令牌**换取新 Cookie（自愈，
 *   dsh 重启令牌轮换后无需人工干预）；
 * - WebSocket 升级请求同样注入 Cookie 转发；
 * - 客户端自带 `?token=` 的请求也不受影响（令牌由上游处理，代理仅透传该查询参数）。
 *
 * 用法：
 *   node dsh-auth-proxy.mjs --port 3080 --upstream-port 3082 --log "C:\TEMP\dsh-webui.log"
 *   （--log 指向 dsh web 的 stdout 日志，内含 `dsh web: http://127.0.0.1:3082/?token=<令牌>` 行）
 */
import http from "node:http";
import fs from "node:fs";
import path from "node:path";

const args = process.argv.slice(2);
function arg(name, fallback) {
  const i = args.indexOf(name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : fallback;
}
const PORT = Number(arg("--port", 3080));
const UP_HOST = arg("--upstream-host", "127.0.0.1");
const UP_PORT = Number(arg("--upstream-port", 3082));
const LOG = arg("--log", "");
const BIND = arg("--bind", "127.0.0.1");

let sessionCookie = null;   // 形如 "dsh-auth-xxx=v1...."（可能多段以 "; " 相连）
let refreshing = null;      // 并发去重：同一时刻只跑一次刷新

/** 从 dsh 启动日志提取该上游实例的最后一个令牌。 */
function readTokenFromLog() {
  if (!LOG) return null;
  try {
    const text = fs.readFileSync(LOG, "utf8");
    const escaped = UP_HOST.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const re = new RegExp(
      `dsh web: http://${escaped}:${UP_PORT}/\\?token=([A-Za-z0-9_\\-]+)`, "g");
    let m, last = null;
    while ((m = re.exec(text)) !== null) last = m[1];
    return last;
  } catch {
    return null;
  }
}

/** 用一次性令牌换取会话 Cookie；失败返回 null。 */
function exchangeCookie(token) {
  return new Promise((resolve) => {
    const req = http.request(
      { host: UP_HOST, port: UP_PORT, path: "/?token=" + token, method: "GET" },
      (res) => {
        const setCookies = res.headers["set-cookie"] || [];
        res.resume();
        const pairs = setCookies.map((one) => one.split(";")[0]).filter(Boolean);
        resolve(pairs.length ? pairs.join("; ") : null);
      });
    req.on("error", () => resolve(null));
    req.setTimeout(8000, () => req.destroy(new Error("exchange timeout")));
    req.end();
  });
}

/** 确保手上有会话 Cookie；force=true 时强制重新提取令牌并换取。 */
async function ensureCookie(force = false) {
  if (sessionCookie && !force) return sessionCookie;
  if (refreshing) return refreshing;
  refreshing = (async () => {
    const token = readTokenFromLog();
    if (!token) {
      console.error(`[dsh-auth-proxy] no token found in log: ${LOG}`);
      return sessionCookie;
    }
    const got = await exchangeCookie(token);
    if (got) {
      sessionCookie = got;
      console.error(`[dsh-auth-proxy] session cookie refreshed (token ${token.slice(0, 6)}…)`);
    }
    return sessionCookie;
  })();
  try {
    return await refreshing;
  } finally {
    refreshing = null;
  }
}

/** 把请求头里的 dsh-auth Cookie 替换为当前会话 Cookie（保留客户端其余 Cookie）。
 *  同时把 Host 头改写为上游地址：会话 Cookie 的 JWT 绑定颁发时的 authority
 *  （即上游 host:port），Host 不一致服务端会判 401。 */
function injectCookie(headers, cookie) {
  const out = { ...headers };
  const others = String(headers.cookie || "")
    .split(";").map((one) => one.trim())
    .filter((one) => one && !/^dsh-auth-/.test(one));
  const merged = cookie ? [...others, cookie].join("; ") : others.join("; ");
  if (merged) out.cookie = merged; else delete out.cookie;
  out.host = `${UP_HOST}:${UP_PORT}`;
  delete out.origin;
  delete out.referer;
  return out;
}

const server = http.createServer(async (req, res) => {
  let cookie = await ensureCookie();
  const attempt = (retry) => {
    const headers = injectCookie(req.headers, cookie);
    console.error(`[dsh-auth-proxy] ${req.method} ${req.url} retry=${retry} cookie=${cookie ? cookie.slice(0, 24) + "…" : "(none)"}`);
    const up = http.request(
      { host: UP_HOST, port: UP_PORT, path: req.url, method: req.method, headers },
      (ures) => {
        // 上游 401 = 会话 Cookie 失效（dsh 重启令牌轮换）→ 强制刷新后重放一次
        if (ures.statusCode === 401 && retry) {
          ures.resume();
          ensureCookie(true).then((c) => { cookie = c; attempt(false); });
          return;
        }
        res.writeHead(ures.statusCode, ures.headers);
        ures.pipe(res);
      });
    up.on("error", () => {
      if (!res.headersSent) { res.writeHead(502, { "content-type": "text/plain" }); res.end("dsh-auth-proxy: upstream error"); }
    });
    req.pipe(up);
  };
  attempt(true);
});

/** WebSocket 升级：注入 Cookie 后转发，双向透传字节流。 */
server.on("upgrade", async (req, socket, head) => {
  const cookie = await ensureCookie();
  const headers = injectCookie(req.headers, cookie);
  headers.connection = "upgrade";
  headers.upgrade = req.headers.upgrade;
  const up = http.request({
    host: UP_HOST, port: UP_PORT, path: req.url, method: req.method, headers,
  });
  up.end();
  up.on("upgrade", (ures, upSocket, upHead) => {
    const lines = ["HTTP/1.1 101 Switching Protocols"];
    for (const [k, v] of Object.entries(ures.headers)) lines.push(`${k}: ${Array.isArray(v) ? v.join(", ") : v}`);
    socket.write(lines.join("\r\n") + "\r\n\r\n");
    if (upHead?.length) socket.write(upHead);
    upSocket.pipe(socket);
    socket.pipe(upSocket);
    const kill = () => { upSocket.destroy(); socket.destroy(); };
    upSocket.on("error", kill);
    socket.on("error", kill);
  });
  up.on("response", (ures) => {
    // 上游拒绝升级（401/403）：刷新 Cookie 一次后按原状态回给客户端，客户端会自行重试
    const body = ures.statusMessage || "";
    socket.end(`HTTP/1.1 ${ures.statusCode} ${ures.statusMessage}\r\nConnection: close\r\nContent-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`);
    ensureCookie(true);
  });
  up.on("error", () => socket.destroy());
});

server.listen(PORT, BIND, async () => {
  console.log(`[dsh-auth-proxy] listening on ${BIND}:${PORT} -> ${UP_HOST}:${UP_PORT}${LOG ? ` (log: ${LOG})` : ""}`);
  await ensureCookie(true); // 启动即预热会话 Cookie
});
