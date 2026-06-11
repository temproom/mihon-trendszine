package eu.kanade.tachiyomi.extension.all.buondua

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.full

class Buondua : HttpSource() {

    override val name = "Buondua"
    override val baseUrl = "https://buondua.com"
    override val lang = "all" // 包含多国内容，使用 "all"
    override val supportsLatest = false

    // 设置 User-Agent 和 Referer 防止图片防盗链 (403)
    override val headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)
        .build()

    // ============================== 热门/首页 ===============================
    override fun popularMangaRequest(page: Int): Request {
        // 每页 20 条数据，分页参数为 start
        return GET("$baseUrl/?start=${(page - 1) * 20}", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.items-row").map { element ->
            SManga.create().apply {
                val linkElement = element.selectFirst("h2 a.item-link")
                title = linkElement?.text()?.trim() ?: ""
                setUrlWithoutDomain(linkElement!!.attr("href"))
                thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }
        
        // 判断是否有下一页 (Next 按钮是否被 disabled 或 href 包含 javascript)
        val nextButton = document.selectFirst("a.pagination-next")
        val hasNextPage = nextButton != null && !nextButton.hasAttr("disabled") && !nextButton.attr("href").contains("javascript")
        
        return MangasPage(mangas, hasNextPage)
    }

    // =============================== 搜索 & 筛选 ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // 获取我们自定义的筛选器实例
        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        val tagFilter = filters.filterIsInstance<TagFilter>().firstOrNull()

        // 优先级 1：如果用户输入了 Tag slug，则按 Tag 浏览
        if (!tagFilter?.state.isNullOrEmpty()) {
            return GET("$baseUrl/tag/${tagFilter.state}?start=${(page - 1) * 20}", headers)
        }

        // 优先级 2：如果用户选择了特定的分类 (Hot 或 Collection)
        if (categoryFilter != null && categoryFilter.state > 0) {
            val categoryPath = categoryFilter.getValue() // 获取 "hot" 或 "collection"
            return GET("$baseUrl/$categoryPath?start=${(page - 1) * 20}", headers)
        }

        // 优先级 3：如果用户在搜索框输入了关键字
        if (query.isNotEmpty()) {
            return GET("$baseUrl/?search=$query&start=${(page - 1) * 20}", headers)
        }

        // 优先级 4：默认返回首页
        return popularMangaRequest(page)
    }

    // 搜索页的 HTML 结构与首页完全一致，直接复用
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // =============================== 详情 ==============================
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            // 从 title 标签提取标题，去掉 " - ( Page 1 / 4 )" 部分
            val titleText = document.title()
            title = titleText.substringBefore(" - ").trim()
            
            // 提取标签作为 Genre
            val tags = document.select("div.article-tags a.tag").map { it.text().trim() }
            genre = tags.joinToString(", ")
            author = tags.getOrNull(1) ?: "Unknown" // 第二个标签通常是 Coser 名字
            
            status = SManga.COMPLETED // 相册类内容视为已完结
            description = "Album from Buondua"
            thumbnail_url = document.selectFirst("div.article-fulltext p img")?.attr("src")
        }
    }

    // =============================== 章节 =============================
    // 将整个相册视为一个 Chapter，图片分页在 fetchPageList 中处理
    override fun chapterListParse(response: Response): List<SChapter> {
        return listOf(
            SChapter.create().apply {
                name = "Chapter 1"
                // 去掉可能存在的 ?page=1 参数，保留纯净的相册 URL
                url = response.request.url.toString().substringBefore("?")
                date_upload = 0
            }
        )
    }

    // =============================== 图片列表 (核心分页处理) ================================
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            // 1. 请求第一页获取总页数
            val firstPageDoc = client.newCall(GET(chapter.url + "?page=1", headers)).execute().asJsoup()
            val totalPages = parseTotalPages(firstPageDoc)
            
            val pages = mutableListOf<Page>()
            var pageIndex = 0
            
            // 2. 解析第一页的图片
            firstPageDoc.select("div.article-fulltext p img").forEach { img ->
                pages.add(Page(pageIndex++, "", img.attr("src")))
            }
            
            // 3. 串行请求后续页并解析图片
            if (totalPages > 1) {
                for (pageNo in 2..totalPages) {
                    val doc = client.newCall(GET("${chapter.url}?page=$pageNo", headers)).execute().asJsoup()
                    doc.select("div.article-fulltext p img").forEach { img ->
                        pages.add(Page(pageIndex++, "", img.attr("src")))
                    }
                }
            }
            pages
        }
    }

    override fun pageListParse(document: Document) = throw UnsupportedOperationException("Not used.")
    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Not used.")

    // 从标题中解析总页数 (例如: "Page 1 / 4" -> 4)
    private fun parseTotalPages(document: Document): Int {
        val titleText = document.title()
        val regex = Regex("""Page \d+ / (\d+)""")
        val match = regex.find(titleText)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    // =============================== 筛选器 ==============================
    // 直接调用 Filters.kt 中定义的方法
    override fun getFilterList() = getBuonduaFilterList()
}