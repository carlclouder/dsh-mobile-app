package dev.dshmobile.network

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 新版 dsh(≥0.1.2-rc.1) WebUI 一次性令牌认证的 App 侧会话管理（2026-09-16 适配）。
 *
 * 服务端语义（全局规则 21 + 本机 0.1.5-rc.2 curl/浏览器实测确凿）：
 * - `dsh web` 每次启动随机生成一次性令牌（重启即换新，无关闭开关）；
 * - `GET /?token=<令牌>` 返回 303 + `Set-Cookie: dsh-auth-<随机名>=<JWT>`（Max-Age 30 天）；
 * - **令牌是一次性的**：换 Cookie 即销毁使用资格；Cookie 是通行证（30 天内任何请求放行）；
 * - Cookie 的 JWT 绑定颁发时的 Host authority：换 Cookie 与后续请求必须同一 Host 头。
 *
 * App 侧状态机：
 * - 设置页地址两种形式：带 `?token=` 的完整链接（令牌 RAW → 首次请求时消费换 Cookie）
 *   或裸地址（直接用已持久化的 Cookie / 旧行为直连）；
 * - **Cookie 持久化**（SharedPreferences）：App 重启后 30 天内继续无感复用，不消耗令牌；
 * - 401 处理：令牌 RAW → 消费换 Cookie 重试一次；令牌已 CONSUMED → 不重试
 *   （令牌已废，用户需重新粘贴宿主新打印的带令牌链接）。
 */
object DshAuthSession {

    private const val COOKIE_HEADER = "Cookie"
    private const val PREFS = "dsh_auth_session"
    private const val KEY_COOKIE = "session_cookie"
    private const val KEY_TOKEN_CONSUMED = "token_consumed"

    /** 当前登记的令牌；null = 地址不带令牌（旧行为直连）。 */
    @Volatile
    private var authToken: String? = null

    /** 令牌所属 base：换 Cookie 必须打同一 base（Cookie JWT 绑定 Host authority）。 */
    @Volatile
    private var authBase: String? = null

    /** 会话 Cookie（形如 dsh-auth-xxx=v1....；多段以 "; " 相连）。 */
    @Volatile
    private var sessionCookie: String? = null

    /** 令牌消费状态：true = 已用该令牌换过 Cookie（服务端已销毁，重试无效）。 */
    @Volatile
    private var tokenConsumed: Boolean = false

    private val refreshMutex = Mutex()

    /** 持久化 prefs（attach 时注入 context；object 无法直接持有 Activity context）。 */
    private var prefs: android.content.SharedPreferences? = null

    /** 独立客户端：仅用于令牌换 Cookie，不带业务拦截器（防递归），不跟随重定向。
     *  不做 Host 改写：新版围栏对带有效令牌的请求不检查 Host（2026-09-16 实测），
     *  且换 Cookie 与后续请求的 Host 口径必须一致（见 DshApiClient 同名注释）。 */
    private val exchangeClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .build()

    /**
     * 初始化：注入 Context 并恢复持久化的会话 Cookie（App 重启后 30 天内无感复用）。
     * 在 DshRepository 初始化时调用一次（application context）。
     */
    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sessionCookie = prefs?.getString(KEY_COOKIE, null)
        tokenConsumed = prefs?.getBoolean(KEY_TOKEN_CONSUMED, false) ?: false
    }

    /**
     * 拆分用户输入地址：`scheme://host[:port]/path?token=x` → (base, token?)。
     * - base = 去 query 保留 path、去尾斜杠；
     * - 无 token 参数时返回 (去尾斜杠原串, null)；
     * - 非 http(s) 或不可解析时原样返回（由调用方按非法地址处理）。
     */
    fun splitBaseUrl(raw: String): Pair<String, String?> {
        val trimmed = raw.trim()
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return trimmed to null
        val token = httpUrl.queryParameter("token")?.takeIf { it.isNotBlank() }
            ?: return trimmed.trimEnd('/') to null
        // 标准端口(http=80/https=443)不写进 base；okhttp 4.12 的 defaultPort 是伴生函数
        val defaultPort = okhttp3.HttpUrl.defaultPort(httpUrl.scheme)
        val port = if (httpUrl.port != defaultPort) ":${httpUrl.port}" else ""
        val base = "${httpUrl.scheme}://${httpUrl.host}$port${httpUrl.encodedPath.trimEnd('/')}"
        return base to token
    }

    /**
     * 换址时登记。令牌消费状态按输入形态判定：
     * - 新粘贴的带令牌链接 → 新令牌未消费（RAW）；
     * - 裸地址 → 保持原消费状态（Cookie 仍可复用）。
     * Cookie 不清除：同一宿主的会话 Cookie 在未重启时仍有效，避免不必要的令牌消耗。
     */
    fun configure(base: String, token: String?) {
        authBase = base
        if (token != null) {
            // 新令牌（用户重新粘贴了链接）：重置消费状态
            authToken = token
            tokenConsumed = false
            if (prefs != null) prefs!!.edit().putBoolean(KEY_TOKEN_CONSUMED, false).apply()
        }
        // token == null：保留原 authToken 语义为无令牌直连，但不覆盖消费状态
        if (token == null) authToken = null
    }

    /** 当前会话 Cookie（供 WebSocket 握手手动加头）；null = 尚未建立。 */
    fun currentCookie(): String? = sessionCookie

    /** 令牌是否可用（已登记且未消费——决定 401 时能否自愈）。 */
    fun hasUsableToken(): Boolean = authToken != null && !tokenConsumed

    /**
     * OkHttp 应用拦截器：注入会话 Cookie。
     * - 已有 Cookie：直接注入；
     * - 无 Cookie 且有未消费令牌：先消费令牌换取 Cookie 再放行（省一次 401 往返）；
     * - 上游 401：若令牌未消费则消费重试一次；已消费则交回 401（用户需更新链接）。
     * 注意：必须注册在 Host 改写类拦截器之后，保证换 Cookie 与业务请求同一 Host authority。
     */
    val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        var cookie = sessionCookie
        if (cookie == null && hasUsableToken()) cookie = consumeTokenForCookie()
        val first = if (cookie != null) request.newBuilder().header(COOKIE_HEADER, cookie).build() else request
        val response = chain.proceed(first)
        val canSelfHeal = response.code == 401 && hasUsableToken()
        if (!canSelfHeal) return@Interceptor response
        response.close() // 401 响应体无用，释放连接后消费令牌重试
        val fresh = consumeTokenForCookie()
        if (fresh == null) return@Interceptor chain.proceed(first)
        chain.proceed(request.newBuilder().header(COOKIE_HEADER, fresh).build())
    }

    /**
     * 消费一次性令牌换取会话 Cookie（串行化防并发双花）；
     * 成功 → 持久化 Cookie 并标记 CONSUMED；失败（令牌无效/网络）→ 保持状态可重试。
     */
    private fun consumeTokenForCookie(): String? {
        val token = authToken ?: return null
        val base = authBase ?: return null
        return kotlinx.coroutines.runBlocking {
            refreshMutex.withLock {
                // double-check：并发等待期间另一个线程可能已完成消费
                if (sessionCookie != null && tokenConsumed) return@withLock sessionCookie
                val request = Request.Builder().url("$base/?token=$token").get().build()
                try {
                    exchangeClient.newCall(request).execute().use { response ->
                        val pairs = response.headers("Set-Cookie")
                            .map { it.substringBefore(";") }
                            .filter { it.isNotBlank() }
                        if (pairs.isNotEmpty()) {
                            sessionCookie = pairs.joinToString("; ")
                            tokenConsumed = true
                            prefs?.edit()
                                ?.putString(KEY_COOKIE, sessionCookie)
                                ?.putBoolean(KEY_TOKEN_CONSUMED, true)
                                ?.apply()
                            sessionCookie
                        } else {
                            // 服务端未下发 Cookie（令牌无效或已被消费）——标记已消费，
                            // 避免后续请求反复空耗；交上层提示用户重新粘贴新令牌链接
                            tokenConsumed = true
                            prefs?.edit()?.putBoolean(KEY_TOKEN_CONSUMED, true)?.apply()
                            null
                        }
                    }
                } catch (e: Exception) {
                    null // 网络失败：保持 RAW，下次可重试消费
                }
            }
        }
    }
}
