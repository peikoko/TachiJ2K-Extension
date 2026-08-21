package eu.kanade.tachiyomi.extension.zh.manwa

import eu.kanade.tachiyomi.source.model.SManga
import org.json.JSONArray
import org.json.JSONObject

/** 图源信息 */
class ImageSourceInfo(
    val name: String,
    val param: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("param", param)
    }

    companion object {
        fun fromJson(json: JSONObject): ImageSourceInfo =
            ImageSourceInfo(json.optString("name"), json.optString("param"))
    }
}

/** 最新更新列表 DTO */
class LatestUpdatesDto(json: JSONObject) {
    val books: List<BookDto> = json.optJSONArray("books")?.let { arr ->
        (0 until arr.length()).map { BookDto(arr.getJSONObject(it)) }
    } ?: emptyList()
    val total: Int = json.optInt("total", 0)
}

/** 单本漫画 DTO */
class BookDto(json: JSONObject) {
    private val bookName: String = json.optString("book_name", "")
    private val id: Int = json.optInt("id", 0)
    private val coverUrl: String = json.optString("cover_url", "")

    fun toSManga(imgHost: String) = SManga.create().apply {
        title = bookName
        url = "/book/$id"
        thumbnail_url = imgHost + coverUrl
    }
}
