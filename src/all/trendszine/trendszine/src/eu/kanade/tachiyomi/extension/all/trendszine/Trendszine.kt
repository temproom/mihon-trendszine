package eu.kanade.tachiyomi.extension.all.trendszine

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class Trendszine : ParsedHttpSource() {
    override val name = "Trendszine"
    override val baseUrl = "https://trendszine.com"
    override val lang = "all"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")

    // ========================= Popular & Latest =========================
    override fun popularMangaRequest(page: Int) = latestUpdatesRequest(page)
    override fun popularMangaSelector() = latestUpdatesSelector()
    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request {
        return if (page == 1) GET(baseUrl, headers) else GET("$baseUrl/page/$page/", headers)
    }

    override fun latestUpdatesSelector() = "article.post"
    override fun latestUpdatesNextPageSelector() = "a.next.page-numbers"

    override fun latestUpdatesFromElement(element: Element): SManga {
        return SManga.create().apply {
            val linkElement = element.selectFirst("h2.entry-title a")!!
            title = linkElement.text()
            setUrlWithoutDomain(linkElement.absUrl("href"))
            
            val img = element.selectFirst("div.post-image img")
            thumbnail_url = img?.let { 
                it.absUrl("src").ifEmpty { it.absUrl("data-src") } 
            }
        }
    }

    // ========================= Search & Deep Link =========================
    // 🌟 核心升级：支持在搜索框粘贴 URL 直接跳转
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val deepLinkUrl = query.toHttpUrlOrNull()
        if (page == 1 && deepLinkUrl != null && deepLinkUrl.host == baseUrl.toHttpUrl().host) {
            if (deepLinkUrl.pathSegments.any { it.matches(Regex(".*\\.html.*")) }) {
                val manga = SManga.create().apply {
                    url = deepLinkUrl.encodedPath
                }
                return fetchMangaDetails(manga).map { MangasPage(listOf(it), false) }
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        filters.forEach { filter ->
            if (filter is CategoryFilter && filter.selected != null) {
                val categoryPath = filter.selected!!
                val fullUrl = if (page > 1) "$baseUrl/${categoryPath}page/$page/" else "$baseUrl/$categoryPath"
                return GET(fullUrl, headers)
            }
        }
        if (query.isNotEmpty()) {
            if (page > 1) url.addPathSegment("page").addPathSegment(page.toString())
            url.addQueryParameter("s", query)
            return GET(url.build(), headers)
        }
        return latestUpdatesRequest(page)
    }

    override fun searchMangaSelector() = latestUpdatesSelector()
    override fun searchMangaFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    // ========================= Details =========================
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1.entry-title")?.text() ?: ""
            val genres = document.select("span.cat-links a, span.tags-links a").map { it.text() }
            genre = genres.joinToString(", ")
            status = SManga.COMPLETED
        }
    }

    // ========================= Chapters (Pagination) =========================
    override fun chapterListSelector() = throw UnsupportedOperationException()
    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException()

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val currentUrl = response.request.url.toString()
        
        val dateStr = document.selectFirst("time.entry-date")?.attr("datetime")
        val dateUpload = parseIsoDate(dateStr)

        val pagination = document.selectFirst("div.pgntn-page-pagination-block")
        
        if (pagination == null) {
            return listOf(
                SChapter.create().apply {
                    name = "Gallery (全集)"
                    setUrlWithoutDomain(currentUrl)
                    date_upload = dateUpload
                }
            )
        }

        val pageNumbers = pagination.select("a.post-page-numbers, span.page-numbers.current").mapNotNull { 
            it.text().trim().replace("«  上一页", "").toIntOrNull() 
        }
        val maxPage = pageNumbers.maxOrNull() ?: 1

        return (1..maxPage).map { page ->
            SChapter.create().apply {
                val pageUrl = if (page == 1) currentUrl else "$currentUrl/$page"
                setUrlWithoutDomain(pageUrl)
                name = "Page $page"
                date_upload = dateUpload
            }
        }
    }

    private fun parseIsoDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ========================= Pages =========================
    override fun pageListParse(document: Document): List<Page> {
        val content = document.selectFirst("div.entry-content") ?: return emptyList()
        
        return content.select("img").mapIndexedNotNull { index, img ->
            val imgUrl = img.absUrl("src").ifEmpty {
                img.absUrl("data-src").ifEmpty {
                    img.absUrl("data-lazy-src")
                }
            }
            
            if (imgUrl.isNotEmpty() && 
                !imgUrl.startsWith("data:") && 
                imgUrl.contains("wp-content/uploads")
            ) {
                Page(index, imageUrl = imgUrl)
            } else {
                null
            }
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ========================= Filters =========================
    override fun getFilterList() = FilterList(
        CategoryFilter()
    )
}
