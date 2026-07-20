package eu.kanade.tachiyomi.extension.zh.happymh

import android.app.Application
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val PREF_KEY_CUSTOM_UA = "pref_key_custom_ua_"

/**
 * lib 1.4 implementation for TachiyomiJ2K/TachiJ2K.
 *
 * The upstream source moved to the lib 1.6 suspend API.  This class deliberately
 * keeps the current Happymh HTTP/decryption protocol while exposing the older
 * request/parse and RxJava entry points understood by TachiJ2K.
 */
class Happymh : HttpSource(), ConfigurableSource {

    override val name = "嗨皮漫画"
    override val lang = "zh"
    override val baseUrl = "https://m.happymh.com"
    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val decoder = Decoder()

    init {
        val oldUa = preferences.getString("userAgent", null)
        if (oldUa != null) {
            val editor = preferences.edit().remove("userAgent")
            if (oldUa.isNotBlank()) editor.putString(PREF_KEY_CUSTOM_UA, oldUa)
            editor.apply()
        }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .apply {
            preferences.getString(PREF_KEY_CUSTOM_UA, "")
                ?.takeIf(String::isNotBlank)
                ?.let { set("User-Agent", it) }
        }

    // Popular + Latest
    override fun popularMangaRequest(page: Int): Request {
        val requestHeaders = headers.newBuilder().set("Referer", "$baseUrl/latest").build()
        return GET("$baseUrl/apis/c/index?pn=$page&series_status=-1&order=views", requestHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage =
        response.body.string().parsePopularResponseDto().toMangasPage()

    override fun latestUpdatesRequest(page: Int): Request {
        val requestHeaders = headers.newBuilder().set("Referer", "$baseUrl/latest").build()
        return GET("$baseUrl/apis/c/index?pn=$page&series_status=-1&order=last_date", requestHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage =
        response.body.string().parsePopularResponseDto().toMangasPage()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val body = FormBody.Builder()
                .add("searchkey", query)
                .add("v", "v2.13")
                .build()
            val requestHeaders = headers.newBuilder().set("Referer", "$baseUrl/sssearch").build()
            return Request.Builder()
                .url("$baseUrl/v2.0/apis/manga/ssearch")
                .headers(requestHeaders)
                .post(body)
                .build()
        }

        val urlBuilder = "$baseUrl/apis/c/index".toHttpUrl().newBuilder()
        filters.filterIsInstance<UriPartFilter>().forEach {
            if (it.selected.isNotEmpty()) urlBuilder.addQueryParameter(it.key, it.selected)
        }
        val requestHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/latest/${urlBuilder.build().query}")
            .build()
        urlBuilder.addQueryParameter("pn", page.toString())
        return GET(urlBuilder.build(), requestHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.body.string().parsePopularResponseDto().toMangasPage()
        return if (response.request.url.encodedPath.endsWith("/ssearch")) {
            MangasPage(result.mangas, false)
        } else {
            result
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
        AreaFilter(),
        AudienceFilter(),
        StatusFilter(),
    )

    // Details + Chapters
    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("div.mg-property > h2.mg-title")!!.text()
        thumbnail_url = document.selectFirst("div.mg-cover > mip-img")!!.attr("abs:src")
        author = document.selectFirst("div.mg-property > p.mg-sub-title:nth-of-type(2)")!!.text()
        artist = author
        genre = document.select("div.mg-property > p.mg-cate > a").eachText().joinToString(", ")
        description = document.selectFirst("div.manga-introduction > mip-showmore#showmore")!!.text()
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Observable.fromCallable { fetchAllChapters(manga) }
            .subscribeOn(Schedulers.io())

    override fun chapterListRequest(manga: SManga): Request =
        throw UnsupportedOperationException("fetchChapterList is implemented directly")

    override fun chapterListParse(response: Response): List<SChapter> =
        throw UnsupportedOperationException("fetchChapterList is implemented directly")

    private fun fetchAllChapters(manga: SManga): List<SChapter> {
        val comicId = getMangaUrl(manga).toHttpUrl().pathSegments.last()
        val firstPage = fetchChapterByPage(comicId, 1)
        val chunkSize = firstPage.items.size
        val totalPages = if (chunkSize > 0) (firstPage.total + chunkSize - 1) / chunkSize else 1
        val responses = listOf(firstPage) + (2..totalPages).map { fetchChapterByPage(comicId, it) }

        return responses
            .flatMap { data -> data.items.map { it to data.curr } }
            .map { (chapter, pageNum) ->
                SChapter.create().apply {
                    name = chapter.chapterName
                    // Dummy URL: /comic_id/dummy_mark/chapter_id#source_page
                    url = "/$comicId/$DUMMY_CHAPTER_MARK/${chapter.id}#$pageNum"
                }
            }
            .reversed()
    }

    private fun fetchChapterByPage(comicId: String, page: Int): ChapterByPageResponseData {
        val requestId = System.currentTimeMillis().toString()
        val url = "$baseUrl/v2.0/apis/manga/chapterByPage".toHttpUrl().newBuilder()
            .addQueryParameter("code", comicId)
            .addQueryParameter("lang", "cn")
            .addQueryParameter("order", "asc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("_t", requestId)
            .build()
        val request = GET(url, ajaxHeadersBuilder(requestId).build())
        return client.newCall(request).execute().use {
            it.body.string().parseChapterByPageResponse().data
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String {
        val url = "$baseUrl${chapter.url}".toHttpUrl()
        return "$baseUrl/mangaread/${url.pathSegments[0]}/${url.pathSegments[2]}"
    }

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        if (!chapter.url.contains(DUMMY_CHAPTER_MARK)) throw Exception("请刷新章节列表")

        val requestId = System.currentTimeMillis().toString()
        val chapterUrl = "$baseUrl${chapter.url}".toHttpUrl()
        val comicId = chapterUrl.pathSegments[0]
        val chapterId = chapterUrl.pathSegments[2]
        val url = "$baseUrl/v2.0/apis/manga/reading".toHttpUrl().newBuilder()
            .addQueryParameter("code", comicId)
            .addQueryParameter("cid", chapterId)
            .addQueryParameter("v", "v4.300102")
            .addQueryParameter("_t", requestId)
            .build()
        val requestHeaders = ajaxHeadersBuilder(requestId, accept = "application/json")
            .set("Referer", "$baseUrl/mangaread/$comicId/$chapterId")
            .build()

        val gaTimestamp = generateGaTimestamp()
        val cookie = Cookie.Builder()
            .name("_ga_HVJMXGJXFJ")
            .value("GS2.1.s${gaTimestamp}\$o9\$g1\$t${gaTimestamp + 99999}\$j43\$l0\$h0")
            .domain(baseUrl.toHttpUrl().host)
            .path("/")
            .expiresAt(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
            .build()
        client.cookieJar.saveFromResponse(url, listOf(cookie))

        return GET(url, requestHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.body.string().parsePageListResponseDto()
        val pages = if (dto.data.isEncode) decoder.decodeScans(dto.data.scans) else dto.data.scans
        return pages.parsePageDtos()
            .filter { it.n == 0 }
            .mapIndexed { index, page -> Page(index, imageUrl = page.url.substringBefore("?q=")) }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Pages provide direct image URLs")

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context
        EditTextPreference(context).apply {
            key = PREF_KEY_CUSTOM_UA
            title = "User Agent"
            summary = "留空则使用应用设置中的默认 User Agent，重启生效"
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    Headers.headersOf("User-Agent", newValue as String)
                    true
                } catch (e: Throwable) {
                    Toast.makeText(context, "User Agent 无效：${e.message}", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }.let(screen::addPreference)
    }

    private fun ajaxHeadersBuilder(
        requestId: String,
        accept: String = "application/json, text/plain, */*",
    ): Headers.Builder = headers.newBuilder()
        .set("Accept", accept)
        .add("X-Requested-With", "XMLHttpRequest")
        .add("X-Requested-Id", requestId)

    private fun GET(url: String, requestHeaders: Headers): Request =
        Request.Builder().url(url).headers(requestHeaders).get().build()

    private fun GET(url: HttpUrl, requestHeaders: Headers): Request =
        Request.Builder().url(url).headers(requestHeaders).get().build()

    private fun generateGaTimestamp(): Long {
        val table = intArrayOf(335, 984, 248, 485, 524, 559, 486, 165, 114, 103)
        val seconds = (System.currentTimeMillis() / 1000).toString()
        val len = seconds.length
        val sum = table[seconds[len - 3] - '0'] +
            table[seconds[len - 2] - '0'] +
            table[seconds[len - 1] - '0']
        return (seconds + sum.toString().take(3)).toLong()
    }

    companion object {
        private const val DUMMY_CHAPTER_MARK = "dummy-mark"
    }
}
