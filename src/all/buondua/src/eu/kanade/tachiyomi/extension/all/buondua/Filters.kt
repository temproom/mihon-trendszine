package eu.kanade.tachiyomi.extension.all.buondua

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * 通用的 URL 路径筛选器基类
 * 允许通过下拉菜单选择，并获取对应的 URL 路径片段
 */
open class UriPartFilter(
    displayName: String,
    private val valuePairs: Array<Pair<String, String>>
) : Filter.Select<String>(displayName, valuePairs.map { it.first }.toTypedArray()) {
    
    // 获取当前选中项对应的 URL 路径值
    fun getValue() = valuePairs[state].second
}

/**
 * 分类筛选器 (基于网站 HTML 导航栏提取)
 * 包含：首页、热门(Hot)、合集(Collection)
 */
class CategoryFilter : UriPartFilter(
    "分类 (Category)",
    arrayOf(
        "首页 (Home)" to "",
        "热门 (Hot 🔥)" to "hot",
        "合集 (Collection 📚)" to "collection",
    )
)

/**
 * 标签筛选器
 * 允许用户手动输入 Tag 的 slug (例如: cosplay-10688 或 年年nnian-11456)
 */
class TagFilter : Filter.Text("标签 (Tag slug, 例如: cosplay-10688)")

/**
 * 返回完整的筛选器列表
 */
fun getBuonduaFilterList() = FilterList(
    Filter.Header("💡 提示：优先使用下拉菜单选择分类，或在标签框输入 slug"),
    Filter.Separator(),
    CategoryFilter(),
    TagFilter(),
)