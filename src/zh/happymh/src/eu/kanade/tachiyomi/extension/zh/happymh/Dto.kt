package eu.kanade.tachiyomi.extension.zh.happymh

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import org.json.JSONArray
import org.json.JSONObject

class PopularResponseDto(val data: PopularData) {
    fun toMangasPage(): MangasPage {
        val items = data.items.map {
            SManga.create().apply {
                title = it.name
                url = it.url
                thumbnail_url = it.cover
            }
        }
        return MangasPage(items, data.isEnd.not())
    }
}

class PopularData(val items: List<MangaDto>, val isEnd: Boolean)

class MangaDto(
    val name: String,
    val code: String,
    val cover: String,
) {
    val url = "/manga/$code"
}

class ChapterByPageResponseDataItem(
    val id: Long,
    val chapterName: String,
    val order: Int,
)

class ChapterByPageResponseData(
    val items: List<ChapterByPageResponseDataItem>,
    val total: Int,
    val curr: Int,
)

class ChapterByPageResponse(val data: ChapterByPageResponseData)

class PageListResponseDto(val data: PageListData)

class PageListData(
    val scans: String,
    val isEncode: Boolean,
)

data class PageDto(val n: Int, val url: String)

fun String.parsePopularResponseDto(): PopularResponseDto {
    val data = JSONObject(this).getJSONObject("data")
    val jsonItems = data.optJSONArray("items") ?: JSONArray()
    val items = ArrayList<MangaDto>(jsonItems.length())
    for (index in 0 until jsonItems.length()) {
        val item = jsonItems.getJSONObject(index)
        items += MangaDto(
            name = item.getString("name"),
            code = item.getString("manga_code"),
            cover = item.getString("cover"),
        )
    }
    val isEnd = if (data.has("isEnd")) data.getBoolean("isEnd") else data.optBoolean("is_end")
    return PopularResponseDto(PopularData(items, isEnd))
}

fun String.parseChapterByPageResponse(): ChapterByPageResponse {
    val data = JSONObject(this).getJSONObject("data")
    val jsonItems = data.optJSONArray("items") ?: JSONArray()
    val items = ArrayList<ChapterByPageResponseDataItem>(jsonItems.length())
    for (index in 0 until jsonItems.length()) {
        val item = jsonItems.getJSONObject(index)
        items += ChapterByPageResponseDataItem(
            id = item.getLong("id"),
            chapterName = item.optString("chapterName", item.optString("chapter_name")),
            order = item.optInt("order"),
        )
    }
    return ChapterByPageResponse(
        ChapterByPageResponseData(
            items = items,
            total = data.getInt("total"),
            curr = data.getInt("curr"),
        ),
    )
}

fun String.parsePageListResponseDto(): PageListResponseDto {
    val data = JSONObject(this).getJSONObject("data")
    return PageListResponseDto(
        PageListData(
            scans = data.getString("scans"),
            isEncode = data.optBoolean("isEncode", data.optBoolean("is_encode")),
        ),
    )
}

fun String.parsePageDtos(): List<PageDto> {
    val array = JSONArray(this)
    val pages = ArrayList<PageDto>(array.length())
    for (index in 0 until array.length()) {
        val page = array.getJSONObject(index)
        pages += PageDto(
            n = page.optInt("n"),
            url = page.getString("url"),
        )
    }
    return pages
}
