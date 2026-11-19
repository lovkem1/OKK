// OKK Compose 设置 UI 自描述功能元数据目录
// 对齐 Nuke x50/xz0 架构：按分类自描述、带依赖声明、配置键绑定与全局统计能力。

package com.OKK.yes.loader.ui

import com.OKK.yes.core.hooks.DownloadRedirectHook
import com.OKK.yes.core.hooks.PublicConfigStore

/** 功能分类（5 大类：聊天 / 朋友圈 / 美化 / 增强 / Java脚本） */
enum class FeatureCategory(val title: String, val summary: String) {
    Chat("聊天", "防撤回、手势引用、精细时间与聊天统计"),
    Moments("朋友圈", "朋友圈与动态防删、防撤回与广告拦截"),
    Beauty("美化", "会话分组、底栏胶囊、主题壁纸与界面细节"),
    Assist("增强", "虚拟定位、PC 自动登录、热更新屏蔽与文件分流"),
    Script("Java脚本", "执行 Java 脚本，支持多脚本动态加载与独立启停")
}

/**
 * 一个自描述功能定义模型（借鉴 Nuke x50 抽象）。
 * @param key ConfigStore 中的全局布尔配置键
 * @param title 功能显示标题
 * @param summary 功能详细说明
 * @param category 所属分类
 * @param defaultOn 默认开启状态
 * @param hasConfig 是否具备二级参数配置 Dialog
 * @param badge 徽章提示（可选，如 "实验性" / "推荐"）
 * @param badgeWarning 徽章是否显示为警告黄色/红色
 * @param dependsOn 依赖的前置功能 key 集合
 */
data class FeatureDescriptor(
    val key: String,
    val title: String,
    val summary: String,
    val category: FeatureCategory,
    val defaultOn: Boolean = false,
    val hasConfig: Boolean = false,
    val badge: String? = null,
    val badgeWarning: Boolean = false,
    val dependsOn: Set<String> = emptySet()
)

typealias CategoryFeature = FeatureDescriptor

object FeatureCatalog {

    private val chatFeatures = listOf(
        FeatureDescriptor("anti_revoke", "消息防撤回", "拦截文本/图片/语音/视频等消息的撤回指令，保留原消息并显式标记", FeatureCategory.Chat, defaultOn = true, hasConfig = true, badge = "核心"),
        FeatureDescriptor("anti_revoke_keep_self", "自行撤回保留", "自己撤回的消息同样拦截并保留内容", FeatureCategory.Chat, defaultOn = true)
    )

    private val momentsFeatures = listOf(
        FeatureDescriptor("anti_moments_delete", "朋友圈防删除", "好友删除的朋友圈动态本地仍可见", FeatureCategory.Moments, defaultOn = true),
        FeatureDescriptor("anti_moments_comment_revoke", "朋友圈评论防撤回", "对方删除的互动评论拦截并记录，本地保留", FeatureCategory.Moments, defaultOn = true)
    )

    private val beautyFeatures = emptyList<FeatureDescriptor>()

    private val assistFeatures = listOf(
        FeatureDescriptor("disable_hot_update", "屏蔽热更新", "禁用 Tinker 补丁热更新，锁定当前运行版本", FeatureCategory.Assist, defaultOn = true),
        FeatureDescriptor(DownloadRedirectHook.KEY_ENABLED, "存储重定向", "微信接收的聊天文件自动转存到自定义目录", FeatureCategory.Assist, defaultOn = true, hasConfig = true)
    )

    private val scriptFeatures = emptyList<FeatureDescriptor>()

    val categories: List<Pair<FeatureCategory, List<FeatureDescriptor>>> = listOf(
        FeatureCategory.Chat to chatFeatures,
        FeatureCategory.Moments to momentsFeatures,
        FeatureCategory.Beauty to beautyFeatures,
        FeatureCategory.Assist to assistFeatures,
        FeatureCategory.Script to scriptFeatures
    ).filter { it.second.isNotEmpty() } // 定制版：隐藏无功能的空分类

    val allFeatures: List<FeatureDescriptor> = categories.flatMap { it.second }

    fun getCategoryFeatures(category: FeatureCategory): List<FeatureDescriptor> {
        return categories.firstOrNull { it.first == category }?.second.orEmpty()
    }

    fun isFeatureOn(key: String, defaultOn: Boolean): Boolean {
        return runCatching { PublicConfigStore.getBoolean(key, defaultOn) }.getOrDefault(defaultOn)
    }

    fun setFeatureOn(key: String, on: Boolean) {
        runCatching { PublicConfigStore.putBoolean(key, on, true) }
    }

    fun getCategoryOnCount(category: FeatureCategory): Int {
        return getCategoryFeatures(category).count { isFeatureOn(it.key, it.defaultOn) }
    }

    fun getTotalOnCount(): Int {
        return allFeatures.count { isFeatureOn(it.key, it.defaultOn) }
    }

    fun batchSetCategory(category: FeatureCategory, enabled: Boolean) {
        getCategoryFeatures(category).forEach { feat ->
            setFeatureOn(feat.key, enabled)
        }
    }

    fun batchResetCategory(category: FeatureCategory) {
        getCategoryFeatures(category).forEach { feat ->
            setFeatureOn(feat.key, feat.defaultOn)
        }
    }
}
