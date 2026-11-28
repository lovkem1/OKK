package io.github.lovkem1.okk.core.hooks

import android.content.Context
import io.github.lovkem1.okk.core.common.SecureStrings
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared config file readable from the WeChat process.
 *
 * Keep this as the single writer for normal switches. Writing the same
 * properties file from several async executors can overwrite freshly changed
 * keys with stale snapshots after WeChat restarts.
 */
object PublicConfigStore {
    private val FILE: String get() = SecureStrings.d("wD9FxyhJwzVew3RL2jxC3DlzxztE0Ds=")
    private const val MEDIA_DIR = "/storage/emulated/0/Android/media/com.tencent.mm/OKK"

    private val cache = ConcurrentHashMap<String, String>()
    private val lastLoad = AtomicLong(0L)
    private val writeSeq = AtomicInteger(0)
    private val writeLock = Any()
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "okk-public-cfg").apply { isDaemon = true }
    }

    @Volatile private var migrated = false

    /** 配置默认值版本：升级到此版本时一次性把所有功能开关重置为关闭。 */
    private const val KEY_DEFAULTS_VERSION = "feature_defaults_version"
    private const val DEFAULTS_VERSION = "2"
    /** 需要随版本迁移强制关闭的功能开关（不含 settings_entry_enabled 入口）。 */
    private val featureSwitchKeys = listOf(
        "anti_revoke", "close_friend_hide_conversation", "close_friend_hide_contact",
        "close_friend_hide_sns", "close_friend_hide_search", "revoke_notice_enabled",
        "anti_revoke_keep_self", "media_protect_enabled", "anti_moments_delete",
        "swipe_quote", "bubble_enabled", "detail_enabled", "input_stats_enabled",
        "input_stats_count_send", "anti_moments_comment_revoke", "home_avatar_entry",
        "remove_call_limits_enabled", "chat_toolbar_enabled", "auto_dpi_scaling_enabled",
        "disable_hot_update", "download_redirect_enabled", "conv_card_enabled",
        "fold_banner_fixed", "bottom_tab_floating_labels", "bottom_tab_floating_badge",
        "night_mode_follow", "block_typing_report", "swipe_delete_keep",
        "conversation_grouping_enabled", "floating_quick_entry", "java_plugin_enabled",
        "finder_video_download"
    )

    // 配置变化监听器：key -> listeners。保存配置后立即回调，实现“保存即生效”。
    // 监听器在主线程回调（各功能刷新 UI 需要主线程）。
    private val listeners = ConcurrentHashMap<String, MutableList<(String) -> Unit>>()
    private val globalListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 监听指定 key 变化。保存该 key 时在主线程回调（参数为 key）。 */
    fun addListener(key: String, listener: (String) -> Unit) {
        listeners.getOrPut(key) { java.util.concurrent.CopyOnWriteArrayList() }.add(listener)
    }

    /** 监听所有配置变化。 */
    fun addGlobalListener(listener: (String) -> Unit) {
        globalListeners.add(listener)
    }

    private fun notifyChanged(key: String) {
        if (listeners.isEmpty() && globalListeners.isEmpty()) return
        mainHandler.post {
            listeners[key]?.forEach { l -> runCatching { l(key) } }
            globalListeners.forEach { l -> runCatching { l(key) } }
        }
    }

    /**
     * 品牌名从 AChat 改为 OKK 后，配置目录从 …/AChat 迁移到 …/OKK。
     * 首次访问时将旧目录下的文件复制到新目录（不覆盖已有新文件），避免用户配置丢失。
     */
    private fun migrateLegacyDir() {
        if (migrated) return
        migrated = true
        runCatching {
            val oldDir = File("/storage/emulated/0/Android/media/com.tencent.mm/AChat")
            val newDir = File(MEDIA_DIR)
            if (!oldDir.isDirectory) return
            val newCfg = File(newDir, FILE)
            if (newCfg.isFile) return // 新目录已有主配置，无需迁移
            newDir.mkdirs()
            oldDir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    val dst = File(newDir, f.name)
                    if (!dst.isFile) f.copyTo(dst, overwrite = false)
                }
            }
        }
    }

    private val defaults = linkedMapOf(
        "anti_revoke" to "false",
        "close_friend_enabled" to "false",
        "close_friend_hide_conversation" to "false",
        "close_friend_hide_contact" to "false",
        "close_friend_hide_sns" to "false",
        "close_friend_hide_search" to "false",
        "revoke_notice_enabled" to "false",
        "anti_revoke_keep_self" to "false",
        "anti_revoke_notice_text" to "{name}撤回了一条消息",
        "media_protect_enabled" to "false",
        "anti_moments_delete" to "false",
        "swipe_quote" to "false",
        "swipe_repeat" to "false",
        "quote_delete_clear" to "false",
        "bubble_enabled" to "false",
        "settings_entry_enabled" to "true",
        "module_log_enabled" to "false",
        "bottom_tab_hide_title" to "false",
        "detail_enabled" to "false",
        "detail_template" to "\${time} \${relativeTime}",
        "detail_time_pattern" to "MM-dd HH:mm:ss",
        "detail_text_size" to "12",
        "detail_left_margin" to "0",
        "detail_right_margin" to "0",
        "detail_text_color_light" to "#E6000000",
        "detail_text_color_dark" to "#CCFFFFFF",
        "detail_click_show" to "false",
        "input_stats_enabled" to "false",
        "input_stats_count_send" to "false",
        "input_stats_template" to "今日已发\${totalMsg}条",
        "round_avatar_enabled" to "false",
        "round_avatar_radius" to "0.36",
        "anti_moments_comment_revoke" to "false",
        "virtual_location_enabled" to "false",
        "virtual_location_latitude" to "",
        "virtual_location_longitude" to "",
        "auto_login_win_enabled" to "false",
        "auto_login_win_sync_msg" to "false",
        "auto_login_win_show_device" to "false",
        "auto_login_win_auto_device" to "false",
        "auto_login_win_auto_click" to "false",
        "remove_moments_ads" to "false",
        "profile_id" to "false",
        "home_avatar_entry" to "false",
        "home_drawer_shortcuts" to "qrcode,pay,favorite",
        "home_drawer_signature" to "Lokk 快捷面板",
        "home_status_custom" to "",
        "theme_wallpaper_enabled" to "false",
        "theme_wallpaper_alpha" to "0.15",
        "theme_wallpaper_transparent_status_bar" to "true",
        "theme_wallpaper_text_bold" to "true",
        "system_camera_enabled" to "false",
        "remove_call_limits_enabled" to "false",
        "chat_toolbar_enabled" to "false",
        "auto_dpi_scaling_enabled" to "false",
        "disable_hot_update" to "false",
        "download_redirect_enabled" to "false",
        "real_name_tail" to "false",
        "real_name_tail_color" to "#9E9E9E",
        "member_title" to "false",
        "member_title_show_member" to "false",
        "member_title_owner" to "群主",
        "member_title_admin" to "管理员",
        "member_title_member" to "成员",
        "edit_message" to "false",
        "hide_home_divider" to "false",
        "conv_card_enabled" to "false",
        "conv_card_inset_dp" to "10",
        "conv_card_corner_dp" to "12",
        "conv_card_mode" to "container",
        "conv_pinned_color_light" to "#E6E8ED",
        "conv_pinned_color_dark" to "#38383E",
        "conv_normal_color_light" to "#FFFFFF",
        "conv_normal_color_dark" to "#1E1E1E",
        "fold_banner_fixed" to "false",
        "bottom_tab_floating" to "false",
        "bottom_tab_floating_labels" to "false",
        "bottom_tab_floating_badge" to "false",
        "bottom_tab_title_chats" to "微信",
        "bottom_tab_title_contacts" to "通讯录",
        "bottom_tab_title_discover" to "发现",
        "bottom_tab_title_me" to "我",
        "night_mode_follow" to "false",
        "night_mode" to "false"
    )

    fun reload(force: Boolean = false) {
        migrateLegacyDir()
        val now = System.currentTimeMillis()
        if (!force && now - lastLoad.get() < 2_000L && cache.isNotEmpty()) return

        val map = LinkedHashMap(defaults)
        for (f in configFiles()) {
            if (!f.isFile) continue
            runCatching {
                parse(f.readText(Charsets.UTF_8)).forEach { (k, v) -> map[k] = v }
            }
        }
        cache.clear()
        cache.putAll(map)
        lastLoad.set(now)
    }

    fun getBoolean(key: String, default: Boolean): Boolean {
        reload(force = cache.isEmpty())
        val v = cache[key] ?: return default
        return v.equals("true", ignoreCase = true) ||
            v == "1" ||
            v.equals("yes", ignoreCase = true) ||
            v.equals("on", ignoreCase = true)
    }

    fun getString(key: String, default: String): String {
        reload(force = cache.isEmpty())
        return cache[key] ?: default
    }

    fun getInt(key: String, default: Int): Int {
        val raw = getString(key, default.toString())
        return raw.toIntOrNull() ?: default
    }

    fun containsKey(key: String): Boolean {
        reload()
        return cache.containsKey(key)
    }

    fun put(key: String, value: String, async: Boolean = true) {
        reload()
        val changed = cache[key] != value
        cache[key] = value
        flush(async)
        if (changed) notifyChanged(key)
    }

    fun putBoolean(key: String, value: Boolean, async: Boolean = true) {
        put(key, value.toString(), async)
    }

    /**
     * 用于用户显式编辑且不能丢失的内容。
     * 强制从磁盘合并最新配置并同步落盘，避免旧进程缓存或排队异步写覆盖新值。
     */
    fun putPersisted(key: String, value: String) {
        reload(force = true)
        val changed = cache[key] != value
        cache[key] = value
        flush(async = false)
        if (changed) notifyChanged(key)
    }

    fun putAll(entries: Map<String, String>, async: Boolean = true) {
        reload()
        val changedKeys = entries.filter { (k, v) -> cache[k] != v }.keys
        cache.putAll(entries)
        flush(async)
        changedKeys.forEach { notifyChanged(it) }
    }

    fun snapshot(): Map<String, String> {
        reload()
        return LinkedHashMap(cache)
    }

    /** 导出当前生效配置为 JSON 字符串 */
    fun exportJson(): String {
        val map = snapshot()
        return buildString {
            append("{\n")
            val entries = map.entries.toList()
            entries.forEachIndexed { index, (k, v) ->
                val escapedV = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                append("  \"$k\": \"$escapedV\"")
                if (index < entries.size - 1) append(",")
                append("\n")
            }
            append("}")
        }
    }

    /** 从 JSON 字符串导入配置并应用落盘 */
    fun importJson(jsonStr: String): Boolean {
        return runCatching {
            val map = HashMap<String, String>()
            val regex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
            regex.findAll(jsonStr).forEach { m ->
                map[m.groupValues[1]] = m.groupValues[2]
            }
            if (map.isNotEmpty()) {
                putAll(map, async = false)
                true
            } else false
        }.getOrDefault(false)
    }

    fun ensureDefaults(context: Context? = null, asyncWrite: Boolean = false) {
        reload(force = true)
        var changed = configFiles().none { it.isFile }
        defaults.forEach { (k, v) ->
            if (!cache.containsKey(k)) {
                cache[k] = v
                changed = true
            }
        }

        val newTemplate = defaults["detail_template"]!!
        val newPattern = defaults["detail_time_pattern"]!!
        val oldTemplates = setOf(
            "\${time}|\${relativeTime}",
            "\${time} \${weekday} \${relativeTime}",
            "\${time}"
        )
        val oldPatterns = setOf(
            "MM-dd 周一 HH:mm:ss",
            "MM-dd HH:mm",
            "yyyy-MM-dd HH:mm:ss"
        )
        if (cache["detail_template"] in oldTemplates || cache["detail_template"].isNullOrBlank()) {
            cache["detail_template"] = newTemplate
            changed = true
        }
        if (cache["detail_time_pattern"] in oldPatterns || cache["detail_time_pattern"].isNullOrBlank()) {
            cache["detail_time_pattern"] = newPattern
            changed = true
        }

        // 一次性迁移：老用户升级后所有功能默认关闭（保留入口与用户后续自定义）
        if (cache[KEY_DEFAULTS_VERSION] != DEFAULTS_VERSION) {
            featureSwitchKeys.forEach { key ->
                cache[key] = "false"
            }
            cache[KEY_DEFAULTS_VERSION] = DEFAULTS_VERSION
            changed = true
        }

        if (changed) flush(async = asyncWrite)
    }

    /**
     * 重置所有功能配置为默认值：清空用户设置、同步落盘，并废弃所有排队中的异步写，
     * 避免旧快照在重置后回写覆盖。各模块钩子大多在 install 时读取配置，重启微信后完全生效。
     */
    fun resetAll() {
        synchronized(writeLock) {
            writeSeq.incrementAndGet()
            cache.clear()
            cache.putAll(defaults)
            lastLoad.set(System.currentTimeMillis())
            flush(async = false)
        }
    }

    /** 强制同步落盘（保存设置按钮调用）：丢弃排队中的异步写，立即把当前 cache 写入文件。 */
    fun flushNow() {
        synchronized(writeLock) {
            writeSeq.incrementAndGet()
            flush(async = false)
        }
    }

    private fun flush(async: Boolean) {
        val body = buildString {
            appendLine("# OKK public config")
            appendLine("updated=${System.currentTimeMillis()}")
            cache.toSortedMap().forEach { (k, v) ->
                if (k != "updated") appendLine("$k=$v")
            }
        }
        val targets = configFiles()
        if (async) {
            val seq = writeSeq.incrementAndGet()
            io.execute {
                synchronized(writeLock) {
                    if (seq != writeSeq.get()) return@execute
                    targets.forEach { writeFile(it, body) }
                }
            }
        } else {
            // 同步写必须废弃所有旧异步快照，避免其随后回写覆盖最新配置。
            writeSeq.incrementAndGet()
            synchronized(writeLock) {
                targets.forEach { writeFile(it, body) }
            }
        }
    }

    private fun writeFile(file: File, body: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(body, Charsets.UTF_8)
        }
    }

    private fun configFiles(): List<File> = listOf(File(MEDIA_DIR, FILE))

    private fun parse(text: String): Map<String, String> {
        val clean = text.removePrefix("\uFEFF")
        return clean.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .map {
                val i = it.indexOf('=')
                it.substring(0, i).trim() to it.substring(i + 1).trim()
            }
            .filter { (k, _) -> k != "updated" }
            .associate { it }
    }
}
