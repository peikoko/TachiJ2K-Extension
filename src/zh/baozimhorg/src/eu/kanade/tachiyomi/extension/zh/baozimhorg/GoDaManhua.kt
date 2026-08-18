package eu.kanade.tachiyomi.extension.zh.baozimhorg

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * GoDa漫画（baozimh.org）图源，适配 TachiJ2K lib 1.4。
 *
 * 合并自 keiyoushi extensions-source 的 lib-multisrc/goda/GoDa.kt 和
 * src/zh/baozimhorg/GoDaManhua.kt，将 kotlinx.serialization 替换为 org.json，
 * 去除 @Source 注解和 keiyoushi 专有依赖。
 *
 * 镜像域名、API 域名、图片 CDN 域名均可在图源设置中手动切换/修改，
 * 以适配老版本 TachiJ2K 1.4 缺少 keiyoushi 镜像选择机制的问题。
 */
class GoDaManhua : HttpSource(), ConfigurableSource {

    override val name = "GoDa漫画"
    override val lang = "zh"
    override val supportsLatest = true

    /** 默认镜像域名列表 */
    private val mirrors = arrayOf(
        "https://baozimh.org",
        "https://godamh.com",
        "https://m.baozimh.one",
        "https://bzmh.org",
        "https://g-mh.org",
        "https://m.g-mh.org",
    )

    /** 镜像域名显示名 */
    private val mirrorNames = arrayOf(
        "baozimh.org",
        "godamh.com",
        "m.baozimh.one",
        "bzmh.org",
        "g-mh.org",
        "m.g-mh.org",
    )

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    /** 当前选中的镜像域名，默认 baozimh.org */
    override val baseUrl: String
        get() = preferences.getString(PREF_KEY_MIRROR, mirrors[0]) ?: mirrors[0]

    /** 章节列表 API 域名，可在设置中修改 */
    private val apiBaseUrl: String
        get() = preferences.getString(PREF_KEY_API_BASE, DEFAULT_API_BASE) ?: DEFAULT_API_BASE

    /** 图片 CDN 域名，可在设置中修改 */
    private val imageCdn: String
        get() = preferences.getString(PREF_KEY_IMAGE_CDN, "") ?: ""

    /**
     * 根据 images.line 值选择图片 CDN 域名。
     * 站点前端线路映射：line=2 -> c-nd2-1.6wm.top，其他 -> c-nd3-1.6wm.top。
     * 若用户在设置中自定义了图片 CDN 域名则优先使用。
     */
   private fun getCdnForLine(line: Int): String {
        // 用户自定义了 CDN 域名时优先使用（忽略已废弃的旧域名）
        if (imageCdn.isNotEmpty() && imageCdn != DEPRECATED_IMAGE_CDN) return imageCdn
       return when (line) {
           2 -> "https://c-nd2-1.6wm.top"
           else -> "https://c-nd3-1.6wm.top"
       }
   }

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)

    override val client = super.client.newBuilder()
        .addInterceptor(NotFoundInterceptor())
        .build()

    // ==================== Popular ====================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/hots/page/$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup().also(::parseGenres)
        val mangas = document.select(".container > .cardlist .pb-2 a").map { element ->
            SManga.create().apply {
                val imgSrc = element.selectFirst("img")!!.attr("src")
                url = getKey(element.attr("href"))
                title = element.selectFirst("h3")!!.ownText()
                thumbnail_url = if ("url=" in imgSrc) imgSrc.toHttpUrl().queryParameter("url")!! else imgSrc
            }
        }
        val hasNextPage = document.selectFirst("a[aria-label=下一頁] button") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ==================== Latest ====================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/newss/page/$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        popularMangaParse(response)

    // ==================== Search ====================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/s".toHttpUrl().newBuilder()
                .addPathSegment(query)
                .addEncodedQueryParameter("page", "$page")
                .build()
            return GET(url, headers)
        }
        for (filter in filters) {
            if (filter is UriPartFilter) return GET(baseUrl + filter.toUriPart() + "/page/$page", headers)
        }
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        popularMangaParse(response)

    // ==================== Manga Details ====================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    private fun Element.getMangaId(): String =
        selectFirst("#mangachapters")!!.attr("data-mid")

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup().selectFirst("main")!!
        val titleElement = document.selectFirst("h1")!!
        val elements = titleElement.parent()!!.parent()!!.children()
        check(elements[4].tagName() == "p")

        title = titleElement.ownText()
        status = when (titleElement.child(0).text()) {
            "連載中", "Ongoing" -> SManga.ONGOING
            "完結" -> SManga.COMPLETED
            "停止更新" -> SManga.CANCELLED
            "休刊" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        author = Entities.unescape(elements[1].children().drop(1).joinToString { it.text().removeSuffix(" ,") })
        genre = buildList {
            elements[2].children().drop(1).mapTo(this) { it.text().removeSuffix(" ,") }
            elements[3].children().mapTo(this) { it.text().removePrefix("#") }
        }.joinToString()
        description = (elements[4].text() + "\n\nID: ${document.getMangaId()}").trim()
        thumbnail_url = document.selectFirst("img.object-cover")!!.attr("src")
    }

    // ==================== Chapters ====================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val mangaId = manga.description
            ?.substringAfterLast("ID: ", "")
            ?.takeIf { it.toIntOrNull() != null }
            ?: client.newCall(mangaDetailsRequest(manga)).execute().asJsoup().getMangaId()

        fetchChapterList(mangaId)
    }

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException("fetchChapterList is implemented directly")

    /**
     * 通过 API 获取章节列表，解析 JSON 响应。
     */
    private fun fetchChapterList(mangaId: String): List<SChapter> {
        val response = client.newCall(
            GET("$apiBaseUrl/api/manga/get?mid=$mangaId&mode=all", headers)
        ).execute()
        val data = JSONObject(response.body.string()).getJSONObject("data")
        val mangaSlug = data.getString("slug")
        val chapters = data.optJSONArray("chapters") ?: JSONArray()
        val result = ArrayList<SChapter>(chapters.length())
        for (i in 0 until chapters.length()) {
            val chapter = chapters.getJSONObject(i)
            val chapterId = chapter.getInt("id")
            val attrs = chapter.getJSONObject("attributes")
            val slug = attrs.getString("slug")
            val title = attrs.getString("title")
            val updatedAt = attrs.getString("updatedAt")
            result.add(SChapter.create().apply {
                url = "$mangaSlug/$slug#$mangaId/$chapterId"
                name = title
                date_upload = parseDate(updatedAt)
            })
        }
        return result.asReversed()
    }

    override fun getChapterUrl(chapter: SChapter): String =
        "$baseUrl/manga/" + chapter.url.substringBeforeLast('#')

    // ==================== Pages ====================

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.substringAfterLast('#', "")
        val mangaId = id.substringBefore('/', "")
        val chapterId = id.substringAfter('/', "")
        if (mangaId.isEmpty() || chapterId.isEmpty()) throw Exception("请刷新漫画")
        // 用 API 接口获取章节信息（含混淆的图片列表）
        return GET("$apiBaseUrl/api/v2/chapter/getinfo?m=$mangaId&c=$chapterId", headers)
    }

    /**
     * 重写 fetchPageList，优先尝试 HTML 方式获取图片（图片为完整 URL），
     * 失败则回退到 API + 解码方式。
     */
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val id = chapter.url.substringAfterLast('#', "")
        val mangaId = id.substringBefore('/', "")
        val chapterId = id.substringAfter('/', "")
        if (mangaId.isEmpty() || chapterId.isEmpty()) throw Exception("请刷新漫画")

        // 方式一：HTML 接口，图片 URL 为完整地址
        try {
            val htmlResponse = client.newCall(
                GET("$baseUrl/chapter/getcontent?m=$mangaId&c=$chapterId", headers)
            ).execute()
            htmlResponse.use {
                val document = it.asJsoup()
                val images = document.select("#chapcontent > div > img")
                if (images.isNotEmpty()) {
                    return@fromCallable images.mapIndexed { index, element ->
                        Page(index, imageUrl = element.attr("data-src").ifEmpty { element.attr("src") })
                    }
                }
            }
        } catch (_: Exception) {
            // HTML 方式失败，继续尝试 API 方式
        }

        // 方式二：API + 解码方式
        val apiResponse = client.newCall(
            GET("$apiBaseUrl/api/v2/chapter/getinfo?m=$mangaId&c=$chapterId", headers)
        ).execute()
        apiResponse.use {
            val body = JSONObject(it.body.string())
           val info = body.getJSONObject("data").getJSONObject("info")
            val imagesObj = info.getJSONObject("images")
            val imagesStr = imagesObj.getString("images")
            val line = imagesObj.optInt("line", 1)
            val cdn = getCdnForLine(line)
            val decoded = ChapterImageDecoder.decode(imagesStr)
           val imageArray = JSONArray(decoded)
           val pages = ArrayList<Page>(imageArray.length())
           for (i in 0 until imageArray.length()) {
               val img = imageArray.getJSONObject(i)
               val url = img.getString("url")
               val order = img.getInt("order")
                pages.add(Page(order, imageUrl = "$cdn$url"))
           }
           return@fromCallable pages
        }
    }

    override fun pageListParse(response: Response): List<Page> =
        throw UnsupportedOperationException("fetchPageList is implemented directly")

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Pages provide direct image URLs")

    /**
     * 重写图片请求，确保 Referer 指向当前镜像域名。
     * 图片 CDN 可能校验 Referer，需要与 baseUrl 保持一致。
     */
    override fun imageRequest(page: Page): Request {
        val imageHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    // ==================== Filters / Genres ====================

    private var genres: Array<Pair<String, String>> = emptyArray()

    /** 从页面解析分类标签 */
    private fun parseGenres(document: Document) {
        if (genres.isNotEmpty()) return
        val box = document.selectFirst("h2")?.parent()?.parent() ?: return
        val items = box.select("a")
        genres = Array(items.size) { i ->
            val item = items[i]
            Pair(item.text().removePrefix("#"), item.attr("href"))
        }
    }

    override fun getFilterList(): FilterList = if (genres.isEmpty()) {
        FilterList(listOf(Filter.Header("点击\"重置\"刷新分类")))
    } else {
        FilterList(
            listOf(
                Filter.Header("分类（搜索文本时无效）"),
                UriPartFilter("分类", genres),
            )
        )
    }

    /** 分类筛选器 */
    class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = vals[state].second
    }

    /** 从链接中提取漫画 key */
    private fun getKey(link: String): String =
        link.substringAfter("/manga/").removeSuffix("/")

    // ==================== Preferences ====================

    /**
     * 图源设置界面：镜像域名选择、API 域名、图片 CDN 域名。
     * 修改后需重启应用或刷新图源才能生效。
     */
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        // 镜像域名选择
        ListPreference(context).apply {
            key = PREF_KEY_MIRROR
            title = "镜像域名"
            summary = "%s\n选择可用的镜像域名，修改后需刷新图源"
            entries = mirrorNames
            entryValues = mirrors
            setDefaultValue(mirrors[0])
        }.let(screen::addPreference)

        // API 域名
        EditTextPreference(context).apply {
            key = PREF_KEY_API_BASE
            title = "API 域名"
            summary = "章节列表 API 地址，默认：$DEFAULT_API_BASE"
            setDefaultValue(DEFAULT_API_BASE)
        }.let(screen::addPreference)

        // 图片 CDN 域名
        EditTextPreference(context).apply {
            key = PREF_KEY_IMAGE_CDN
           title = "图片 CDN 域名"
            summary = "留空则自动选择线路CDN，自定义时填写完整域名"
           setDefaultValue(DEFAULT_IMAGE_CDN)
        }.let(screen::addPreference)
    }

    // ==================== Date Parsing ====================

    companion object {
       private const val DEFAULT_API_BASE = "https://api-get-v3.mgsearcher.com"
        private const val DEFAULT_IMAGE_CDN = ""
        private const val DEPRECATED_IMAGE_CDN = "https://f40-1-4.g-mh.online"

        private const val PREF_KEY_MIRROR = "pref_key_mirror"
        private const val PREF_KEY_API_BASE = "pref_key_api_base"
        private const val PREF_KEY_IMAGE_CDN = "pref_key_image_cdn"

        private val dateFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            },
        )

        /** 解析 ISO 8601 日期字符串为时间戳 */
        fun parseDate(dateString: String): Long {
            for (format in dateFormats) {
                val date = format.parse(dateString, ParsePosition(0)) ?: continue
                return date.time
            }
            throw IllegalArgumentException("Unable to parse date: $dateString")
        }
    }
}

/**
 * 404 拦截器：漫画已被删除时提示用户重新迁移。
 */
private class NotFoundInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code != 404) return response
        response.close()
        throw IOException("请将此漫画重新迁移到本图源")
    }
}
