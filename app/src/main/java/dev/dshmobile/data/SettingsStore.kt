package dev.dshmobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 配置存储（设计 §5.7：DataStore<Preferences>，仅存服务器地址 + 三个通知开关）。
 * 无数据库：会话快照为易失内存态，重启后由 baseline() 重建。
 */
private val Context.dshDataStore: DataStore<Preferences> by preferencesDataStore(name = "dsh_settings")

/** 三个通知开关的快照值（EventStreamService 消费）。 */
data class NotificationToggles(
    val turnComplete: Boolean = true,
    val approval: Boolean = true,
    val question: Boolean = true,
)

class SettingsStore(private val context: Context) {

    companion object {
        /**
         * 默认服务器地址。取自 BuildConfig（app/personal.properties 注入，git 忽略）——
         * 源码不含私人域名（开源发布安全）；配置缺失时为空串，首装用户在设置页填自己的地址。
         */
        val DEFAULT_BASE_URL: String = dev.dshmobile.BuildConfig.DEFAULT_BASE_URL

        /** 主题键（默认/护眼蓝/护眼绿；default|eyeblue|eyegreen）。 */
        const val THEME_DEFAULT = "default"
        const val THEME_EYE_BLUE = "eyeblue"
        const val THEME_EYE_GREEN = "eyegreen"

        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_TOGGLE_TURN = booleanPreferencesKey("toggle_turn_complete")
        private val KEY_TOGGLE_APPROVAL = booleanPreferencesKey("toggle_approval")
        private val KEY_TOGGLE_QUESTION = booleanPreferencesKey("toggle_question")
        private val KEY_THEME = stringPreferencesKey("theme_key")
    }

    /** 主题键流（默认 default）。 */
    val themeKeyFlow: Flow<String> = context.dshDataStore.data.map { prefs ->
        prefs[KEY_THEME] ?: THEME_DEFAULT
    }

    suspend fun themeKeyOnce(): String = themeKeyFlow.first()

    suspend fun setThemeKey(key: String) {
        context.dshDataStore.edit { it[KEY_THEME] = key }
    }

    /** 服务器地址流（空值归一为默认地址）。 */
    val baseUrlFlow: Flow<String> = context.dshDataStore.data.map { prefs ->
        prefs[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
    }

    /** 通知开关流（缺省全开）。 */
    val togglesFlow: Flow<NotificationToggles> = context.dshDataStore.data.map { prefs ->
        NotificationToggles(
            turnComplete = prefs[KEY_TOGGLE_TURN] ?: true,
            approval = prefs[KEY_TOGGLE_APPROVAL] ?: true,
            question = prefs[KEY_TOGGLE_QUESTION] ?: true,
        )
    }

    /** 单次读取（服务启动时的同步初始化路径）。 */
    suspend fun baseUrlOnce(): String = baseUrlFlow.first()

    suspend fun togglesOnce(): NotificationToggles = togglesFlow.first()

    /** 更新服务器地址（设置页）；空串视作恢复默认。 */
    suspend fun setBaseUrl(url: String) {
        context.dshDataStore.edit { prefs ->
            val normalized = url.trim()
            prefs[KEY_BASE_URL] = normalized.ifBlank { DEFAULT_BASE_URL }
        }
    }

    suspend fun setToggleTurnComplete(enabled: Boolean) {
        context.dshDataStore.edit { it[KEY_TOGGLE_TURN] = enabled }
    }

    suspend fun setToggleApproval(enabled: Boolean) {
        context.dshDataStore.edit { it[KEY_TOGGLE_APPROVAL] = enabled }
    }

    suspend fun setToggleQuestion(enabled: Boolean) {
        context.dshDataStore.edit { it[KEY_TOGGLE_QUESTION] = enabled }
    }
}
