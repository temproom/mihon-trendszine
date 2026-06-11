package eu.kanade.tachiyomi.extension.all.ilovexs

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(
    displayName: String,
    private val valuePairs: Array<Pair<String, String>>,
) : Filter.Select<String>(displayName, valuePairs.map { it.first }.toTypedArray()) {
    val selected: String?
        get() = valuePairs[state].second.takeIf { it.isNotEmpty() }
}

class CategoryFilter : UriPartFilter(
    "Category",
    arrayOf(
        "Any" to "",
        "Korea" to "korea",
        "Premium" to "premium", 
        "Japan" to "japan",
        "Chinese" to "chinese",
        "Gravure" to "gravure",
        "Thailand" to "thailand",
        "Cosplay" to "cosplay",
    ),
)

class TagFilter : Filter.Text("Tag") {
    fun toUriPart(): String = state.trim()
        .lowercase()
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString("-")
}