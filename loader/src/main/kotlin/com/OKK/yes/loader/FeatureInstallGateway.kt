package com.OKK.yes.loader

import android.content.Context
import android.util.Log
import com.OKK.yes.core.compat.CompatReportStore
import com.OKK.yes.core.hooks.AntiMomentsCommentHook
import com.OKK.yes.core.hooks.AntiMomentsDeleteHook
import com.OKK.yes.core.hooks.AntiRevokeHook
import com.OKK.yes.core.hooks.DisableHotUpdateHook
import com.OKK.yes.core.hooks.DownloadRedirectHook
import com.OKK.yes.core.hooks.HomeDrawerBridge
import com.OKK.yes.loader.ui.OKKSettingsDialog
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 业务 Hook 安装网关：探针结束后按结果安装；FAIL 不装。
 */
object FeatureInstallGateway {
    private const val TAG = "OKK-FeatureGate"
    private val featuresInstalled = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var modulePath: String? = null

    fun prepare(context: Context, cl: ClassLoader, mp: String?) {
        appContext = context.applicationContext ?: context
        classLoader = cl
        modulePath = mp
    }

    fun isFeaturesInstalled(): Boolean = featuresInstalled.get()

    /** 按当前 [CompatReportStore] 报告安装全部业务功能（只一次） */
    fun installFeaturesOnce(reason: String) {
        if (!featuresInstalled.compareAndSet(false, true)) {
            xlog("features already installed, skip ($reason)")
            return
        }
        val ctx = appContext ?: return
        val cl = classLoader ?: return
        val mp = modulePath
        xlog("install features via $reason")

        // 设置入口桥接（设置入口 Hook 在 HookEntry 中始终安装）
        HomeDrawerBridge.openSettings = { act ->
            OKKSettingsDialog.show(act)
        }
        HomeDrawerBridge.openAbout = { act ->
            OKKSettingsDialog.show(act, EmbeddedSettingsUi.StartTarget.Settings)
        }

        // ── 定制版：仅安装用户指定的 6 项消息保护功能 ────────────────────
        // 1) 消息防撤回（文本/图片/语音/视频）— 含"自行撤回保留"子能力
        installIfOk("AntiRevoke") {
            AntiRevokeHook.install(ctx, cl, mp)
        }
        // 2) 朋友圈防删除
        installIfOk("AntiMomentsDelete") {
            AntiMomentsDeleteHook.install(ctx, cl, mp)
        }
        // 3) 朋友圈评论防撤回
        installIfOk("AntiMomentsComment") {
            AntiMomentsCommentHook.install(ctx, cl, mp)
        }
        // 4) 屏蔽热更新
        installIfOk("DisableHotUpdate") {
            DisableHotUpdateHook.install(ctx, cl, mp)
        }
        // 5) 存储重定向
        installIfOk("DownloadRedirect") {
            DownloadRedirectHook.install(ctx, cl, mp)
        }
        // ──────────────────────────────────────────────────────────────

        FeatureHookRegistry.persist()
        xlog("features done ${FeatureHookRegistry.summaryLine()}")
    }

    private fun installIfOk(name: String, block: () -> Unit) {
        if (!CompatReportStore.isInstallable(name)) {
            val detail = CompatReportStore.lastReport?.byId(name)?.detail ?: "compat FAIL"
            FeatureHookRegistry.markSkip(name, "不适配: $detail")
            xlog("skip $name ($detail)")
            return
        }
        FeatureHookRegistry.installIsolated(name, block)
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
