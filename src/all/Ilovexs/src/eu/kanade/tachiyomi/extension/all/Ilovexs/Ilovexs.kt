package eu.kanade.tachiyomi.extension.all.ilovexs

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstance
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class Ilovexs : HttpSource() {
    override val name = "Ilovexs"
    override val baseUrl = "https://www.ilovexs.com"
    override val lang = "all"
    override val supportsLatest = true

    private val baseHttpUrl = baseUrl.toHttpUrl()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ========================= Popular =========================
    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            baseHttpUrl
        } else {
            baseHttpUrl.newBuilder()
                .addPathSegment("page")
                .addPathSegment(page.toString())
                .build()
        }
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return parseMangasPage(response.asJsoup())
    }

    // ========================= Latest =========================
    override fun latestUpdatesRequest(page: Int): Request {
        return popularMangaRequest(page)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // ========================= Search =========================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // 深度链接处理
        val deepLinkUrl = query.toHttpUrlOrNull()
        if (deepLinkUrl != null && deepLinkUrl.host == baseHttpUrl.host) {
            val pathSegments = deepLinkUrl.pathSegments.filter { it.isNotBlank() }
            if (isMangaOrChapterPath(pathSegments)) {
                return GET(deepLinkUrl, headers)
            }
        }

        // 关键词搜索: /search/?key=xxx&page=x
        if (query.isNotBlank()) {
            val url = baseHttpUrl.newBuilder()
                .addPathSegment("search")
                .addQueryParameter("key", query.trim())
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }

        // 过滤器搜索
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val categoryFilter = filterList.firstInstance<CategoryFilter>()
        val tagFilter = filterList.firstInstance<TagFilter>()

        categoryFilter.selected?.let { category ->
            val url = baseHttpUrl.newBuilder()
                .addPathSegment("category")
                .addPathSegment(category)
                .addPathSegment("page")
                .addPathSegment(page.toString())
                .build()
            return GET(url, headers)
        }

        val tag = tagFilter.toUriPart()
        if (tag.isNotEmpty()) {
            val url = baseHttpUrl.newBuilder()
                .addPathSegment("tag")
                .addPathSegment(tag)
                .addPathSegment("page")
                .addPathSegment(page.toString())
                .build()
            return GET(url, headers)
        }

        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val requestPathSegments = response.request.url.pathSegments.filter { it.isNotBlank() }

        // 如果是详情页直接返回
        if (isMangaOrChapterPath(requestPathSegments)) {
            val manga = mangaDetailsParse(document).apply {
                url = response.request.url.encodedPath
            }
            return MangasPage(listOf(manga), false)
        }

        return parseMangasPage(document)
    }

    // ========================= Filters =========================
    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("提示: 标签搜索仅支持单个标签"),
        Filter.Separator(),
        CategoryFilter(),
        TagFilter(),
    )

    // ========================= Details =========================
    override fun mangaDetailsParse(response: Response): SManga {
        return mangaDetailsParse(response.asJsoup()).apply {
            url = response.request.url.encodedPath
        }
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val body = document.body()
        
        // 优先使用 data 属性获取标题
        title = body.attr("data-album-title")
            .takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1.hero-title")?.text()
            ?: throw Exception("Title is mandatory")

        // 封面图
        thumbnail_url = body.attr("data-album-cover")
            .takeIf { it.isNotBlank() }
            ?: document.selectFirst("img.album-card-image")?.absUrl("src")

        // 分类/标签
        val category = body.attr("data-album-category")
        val categoryLinks = document.select("a.post-meta-link")
            .joinToString { it.text() }
        genre = listOf(category, categoryLinks)
            .filter { it.isNotEmpty() }
            .joinToString()

        description = document.selectFirst("p.hero-lede")?.text()

        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    // ========================= Chapters =========================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val title = document.selectFirst("h1.hero-title")?.text() ?: "Gallery"
        val mediaCount = document.selectFirst("span:contains(media items)")
            ?.text()?.substringBefore(" ")?.toIntOrNull() ?: 0

        return listOf(
            SChapter.create().apply {
                url = response.request.url.encodedPath
                chapter_number = 1F
                name = if (mediaCount > 0) {
                    "$title ($mediaCount 张图片)"
                } else {
                    title
                }
                scanlator = "Ilovexs"
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // ========================= Pages =========================
    override fun pageListParse(response: Response): List<Page> {
        val pages = response.asJsoup()
            .select("section.image-gallery figure.gallery-item-image a.image-link")
            .mapIndexedNotNull { index, element ->
                // 优先使用 href (大图链接), 回退到 data-src 或 src
                val imageUrl = element.absUrl("href")
                    .ifBlank { element.absUrl("data-src") }
                    .ifBlank { element.selectFirst("img")?.absUrl("src") }
                
                if (imageUrl.isBlank()) {
                    null
                } else {
                    Page(index, imageUrl = imageUrl)
                }
            }

        if (pages.isEmpty()) {
            // 备用选择器
            val fallbackPages = response.asJsoup()
                .select("a.image-link[href*='.jpg'], a.image-link[href*='.webp'], a.image-link[href*='.png']")
                .mapIndexedNotNull { index, element ->
                    val imageUrl = element.absUrl("href")
                    if (imageUrl.isBlank()) null else Page(index, imageUrl = imageUrl)
                }
            
            if (fallbackPages.isNotEmpty()) {
                return fallbackPages
            }
            throw Exception("未找到图片列表，请检查网站结构是否变更")
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================= Helpers =========================
    private fun parseMangasPage(document: Document): MangasPage {
        val mangas = document.select("article.album-card").map(::mangaFromElement)
        // 检测是否有下一页
        val hasNextPage = document.selectFirst("a.pagination-link:not(.is-disabled)[href*='/page/']") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a.album-card-link")?.absUrl("href")
            ?: throw Exception("Album URL not found")

        val parsedUrl = link.toHttpUrlOrNull() ?: throw Exception("Invalid album URL: $link")
        url = parsedUrl.encodedPath

        title = element.selectFirst("h3.album-card-title")?.text()
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Title is mandatory")

        thumbnail_url = element.selectFirst("img.album-card-image")?.absUrl("src")
            ?: element.selectFirst("img")?.absUrl("src")
    }

    private fun isMangaOrChapterPath(pathSegments: List<String>): Boolean {
        if (pathSegments.isEmpty()) return false
        // 排除分类/标签/搜索/分页路径
        if (pathSegments.first() in NON_POST_PATH_PREFIXES) return false
        // post_id 开头的是详情页
        return pathSegments.first() == "post_id" || pathSegments.size >= 2
    }

    companion object {
        private val NON_POST_PATH_PREFIXES = setOf(
            "category",
            "tag", 
            "search",
            "page",
        )
    }
}