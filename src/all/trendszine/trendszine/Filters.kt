package eu.kanade.tachiyomi.extension.all.trendszine

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(
    displayName: String,
    private val valuePairs: Array<Pair<String, String>>,
) : Filter.Select<String>(displayName, valuePairs.map { it.first }.toTypedArray()) {
    val selected: String?
        get() = valuePairs[state].second.takeIf { it.isNotEmpty() }
}

class CategoryFilter : UriPartFilter(
    "浏览分类 (注意: 选择后文本搜索失效)",
    arrayOf(
        "全部 (Latest)" to "",
        "风流" to "category/fengliu/",
        "杂志" to "category/zazhi/",
        "秀人网" to "tag/%e7%a7%80%e4%ba%ba%e7%b6%b2/",
        "韩国" to "category/hanguo/",
        "日本" to "category/riben/",
        "欧美" to "category/oumei/",
        "泰国" to "category/taiguo/",
        "Cosplay" to "category/cosplay/",
    ),
)