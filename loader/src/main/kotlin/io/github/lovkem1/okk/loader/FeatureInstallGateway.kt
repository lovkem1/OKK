package io.github.lovkem1.okk.loader

import android.content.Context
import android.util.Log
import io.github.lovkem1.okk.core.compat.CompatReportStore
import io.github.lovkem1.okk.core.hooks.AntiMomentsCommentHook
import io.github.lovkem1.okk.core.hooks.AntiMomentsDeleteHook
import io.github.lovkem1.okk.core.hooks.AntiRevokeHook
import io.github.lovkem1.okk.core.hooks.AutoLoginWinHook
import io.github.lovkem1.okk.core.hooks.BlockTypingReportHook
import io.github.lovkem1.okk.core.hooks.ChatToolbarHook
import io.github.lovkem1.okk.core.hooks.SystemCameraHook
import io.github.lovkem1.okk.core.hooks.RemoveLimitsDuringCallsHook
import io.github.lovkem1.okk.core.hooks.BottomTabConfig
import io.github.lovkem1.okk.core.hooks.BottomTabFloatingHook
import io.github.lovkem1.okk.core.hooks.BottomTabIconHook
import io.github.lovkem1.okk.core.hooks.ChatEnhanceHook
import io.github.lovkem1.okk.core.hooks.ConversationGroupingHook
import io.github.lovkem1.okk.core.hooks.ConversationListStyleHook
import io.github.lovkem1.okk.core.hooks.DisableHotUpdateHook
import io.github.lovkem1.okk.core.hooks.DownloadRedirectHook
import io.github.lovkem1.okk.core.hooks.EditMessageHook
import io.github.lovkem1.okk.core.hooks.FoldBannerPinHook
import io.github.lovkem1.okk.core.hooks.HideHomeDividerHook
import io.github.lovkem1.okk.core.hooks.FloatingQuickEntryHook
import io.github.lovkem1.okk.core.hooks.FinderVideoDownloadHook
import io.github.lovkem1.okk.core.hooks.HomeAvatarHook
import io.github.lovkem1.okk.core.hooks.HomeDrawerBridge
import io.github.lovkem1.okk.loader.ui.OKKSettingsDialog
import io.github.lovkem1.okk.core.hooks.InputStatsHook
import io.github.lovkem1.okk.core.hooks.MemberTitleHook
import io.github.lovkem1.okk.core.hooks.MomentsAdBlockHook
import io.github.lovkem1.okk.core.hooks.ProfileIdHook
import io.github.lovkem1.okk.core.hooks.SwipeDeleteKeepHook
import io.github.lovkem1.okk.core.hooks.CloseFriendHook
import io.github.lovkem1.okk.core.hooks.QuoteDeleteClearHook
import io.github.lovkem1.okk.core.hooks.RealNameTailHook
import io.github.lovkem1.okk.core.hooks.RoundAvatarHook
import io.github.lovkem1.okk.core.hooks.ThemeWallpaperConfig
import io.github.lovkem1.okk.core.hooks.ThemeWallpaperHook
import io.github.lovkem1.okk.core.hooks.VirtualLocationConfig
import io.github.lovkem1.okk.core.hooks.VirtualLocationHook
import io.github.lovkem1.okk.core.hooks.WeChatMapPickBridge
import io.github.lovkem1.okk.core.startup.FeatureHookRegistry
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

        // 全局绑定桥接入口，无视是否开启 HomeAvatar
        HomeDrawerBridge.openSettings = { act ->
            OKKSettingsDialog.show(act)
        }
        HomeDrawerBridge.openAbout = { act ->
            OKKSettingsDialog.show(act, EmbeddedSettingsUi.StartTarget.Settings)
        }
        HomeDrawerBridge.openVirtualLocation = { act ->
            OKKSettingsDialog.show(
                act,
                EmbeddedSettingsUi.StartTarget.FeatureDetail(VirtualLocationConfig.KEY_ENABLED)
            )
        }
        HomeDrawerBridge.openFloatingBar = { act ->
            OKKSettingsDialog.show(
                act,
                EmbeddedSettingsUi.StartTarget.FeatureDetail(BottomTabConfig.KEY_FLOATING)
            )
        }
        HomeDrawerBridge.openTheme = { act ->
            OKKSettingsDialog.show(
                act,
                EmbeddedSettingsUi.StartTarget.FeatureDetail(ThemeWallpaperConfig.KEY_ENABLED)
            )
        }

        installIfOk("HomeAvatar") {
            HomeAvatarHook.install(ctx, cl, mp)
        }

        installIfOk("HideHomeDivider") {
            HideHomeDividerHook.install(ctx, cl, mp)
        }
        installIfOk("FoldBannerPin") {
            FoldBannerPinHook.install(ctx, cl, mp)
        }
        installIfOk("BottomTabIcon") {
            BottomTabIconHook.install(ctx, cl, mp)
        }
        installLenient("BottomTabFloating", "探针误判时仍尝试安装，失败由隔离器兜底") {
            BottomTabFloatingHook.install(ctx, cl, mp)
        }
        installIfOk("RoundAvatar") {
            RoundAvatarHook.install(ctx, cl, mp)
        }
        installIfOk("ThemeWallpaper") {
            ThemeWallpaperHook.install(ctx, cl, mp)
        }
        installIfOk("AntiRevoke") {
            AntiRevokeHook.install(ctx, cl, mp)
        }
        installIfOk("BlockTypingReport") {
            BlockTypingReportHook.install(ctx, cl, mp)
        }
        installLenient("ChatEnhance", "探针误判时仍尝试安装，失败由隔离器兜底") {
            ChatEnhanceHook.install(ctx, cl, mp)
        }
        installIfOk("QuoteDeleteClear") {
            QuoteDeleteClearHook.install(ctx, cl, mp)
        }
        installLenient("SwipeDeleteKeep", "探针误判时仍尝试安装，失败由隔离器兜底") {
            SwipeDeleteKeepHook.install(ctx, cl, mp)
        }
        installLenient("ConversationListStyle", "会话列表卡片美化与置顶染色") {
            ConversationListStyleHook.install(ctx, cl, mp)
        }
        installLenient("CloseFriend", "探针误判时仍尝试安装，失败由隔离器兜底") {
            CloseFriendHook.install(ctx, cl, mp)
        }
        installIfOk("EditMessage") {
            EditMessageHook.install(ctx, cl, mp)
        }
        installIfOk("InputStats") {
            InputStatsHook.install(ctx, cl)
        }
        installIfOk("AntiMomentsDelete") {
            AntiMomentsDeleteHook.install(ctx, cl, mp)
        }
        installIfOk("AntiMomentsComment") {
            AntiMomentsCommentHook.install(ctx, cl, mp)
        }
        installIfOk("MomentsAdBlock") {
            MomentsAdBlockHook.install(ctx, cl, mp)
        }
        installIfOk("ProfileId") {
            ProfileIdHook.install(ctx, cl, mp)
        }
        installIfOk("RealNameTail") {
            RealNameTailHook.install(ctx, cl, mp)
        }
        installIfOk("MemberTitle") {
            MemberTitleHook.install(ctx, cl, mp)
        }
        installIfOk("DisableHotUpdate") {
            DisableHotUpdateHook.install(ctx, cl, mp)
        }
        installIfOk("VirtualLocation") {
            VirtualLocationHook.install(ctx, cl, mp)
        }
        installIfOk("AutoLoginWin") {
            AutoLoginWinHook.install(ctx, cl, mp)
        }
        installIfOk("SystemCamera") {
            SystemCameraHook.install(ctx, cl)
        }
        installIfOk("RemoveLimitsDuringCalls") {
            RemoveLimitsDuringCallsHook.install(ctx, cl, mp)
        }
        installIfOk("ChatToolbar") {
            ChatToolbarHook.install(ctx, cl, mp)
        }
        installIfOk("AutoDpi") {
            // DPI scaling is handled dynamically in Compose UI themes and settings
        }
        installIfOk("MapPickBridge") {
            WeChatMapPickBridge.install(cl)
        }
        installIfOk("DownloadRedirect") {
            DownloadRedirectHook.install(ctx, cl, mp)
        }
        installLenient("FinderVideoDownload", "视频号分享菜单探针特征可能随微信版本偏移，仍尝试安装，失败由隔离器兜底") {
            FinderVideoDownloadHook.install(ctx, cl, mp)
        }
        installIfOk("FloatingQuickEntry") {
            FloatingQuickEntryHook.install(ctx, cl, mp)
        }
        installLenient("ConversationGrouping", "探针误判时仍尝试安装，失败由隔离器兜底") {
            ConversationGroupingHook.install(ctx, cl, mp)
        }
        
        installLenient("JavaPlugin", "无需探针，通过动态脚本拓展核心功能") {
            io.github.lovkem1.okk.core.hooks.plugins.JavaPluginHook.install(ctx, cl, mp)
        }
        
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

    private fun installLenient(name: String, reason: String, block: () -> Unit) {
        if (!CompatReportStore.isInstallable(name)) {
            val detail = CompatReportStore.lastReport?.byId(name)?.detail ?: "compat FAIL"
            xlog("force install $name despite compat report ($detail): $reason")
        }
        FeatureHookRegistry.installIsolated(name, block)
    }

    private fun xlog(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }
}
