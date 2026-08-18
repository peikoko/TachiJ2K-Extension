package eu.kanade.tachiyomi.extension.zh.baozimhorg

import android.util.Base64

/**
 * 章节图片列表解码器。
 *
 * /api/v2/chapter/getinfo 接口返回的图片列表是混淆后的字符串，
 * 而非标准 JSON 数组。此对象逆向站点前端解码逻辑
 * (assets/runtime/chapter-decoder.js) 还原原始 JSON。
 *
 * 流程：去除 "J7r" 前缀 / "nQ" 后缀 -> 按 "kD" 和 "W4s" 标记拆分为 3 段
 * -> 重排为 part3+part1+part2 -> 每 2 个 7 字符块反转一次
 * -> 自定义字母表映射回标准 base64url -> base64 解码 -> UTF-8 JSON。
 */
object ChapterImageDecoder {
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val CUSTOM = "_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val PREFIX = "J7r"
    private const val MARKER1 = "kD"
    private const val MARKER2 = "W4s"
    private const val SUFFIX = "nQ"
    private const val GROUP = 7

    // 预计算查找表：自定义字母表字符码 -> 标准 base64url 字符码（-1 = 无效）
    private val DECODE_TABLE = IntArray(128) { -1 }.apply {
        for (i in CUSTOM.indices) this[CUSTOM[i].code] = STD[i].code
    }

    /**
     * 解码混淆的图片列表字符串，返回原始 JSON 字符串。
     */
    fun decode(input: String): String {
        require(input.startsWith(PREFIX) && input.endsWith(SUFFIX)) { "未知的章节数据格式" }
        val body = input.substring(PREFIX.length, input.length - SUFFIX.length)
        val payloadLen = body.length - MARKER1.length - MARKER2.length
        require(payloadLen > 0) { "未知的章节数据格式" }

        val aLen = payloadLen / 3
        val bLen = (payloadLen - aLen) / 2
        val cLen = payloadLen - aLen - bLen

        val part1 = body.substring(0, bLen)
        val marker1 = body.substring(bLen, bLen + MARKER1.length)
        val part2 = body.substring(bLen + MARKER1.length, bLen + MARKER1.length + cLen)
        val marker2 = body.substring(bLen + MARKER1.length + cLen, bLen + MARKER1.length + cLen + MARKER2.length)
        val part3 = body.substring(bLen + MARKER1.length + cLen + MARKER2.length)
        require(marker1 == MARKER1 && marker2 == MARKER2 && part3.length == aLen) { "未知的章节数据格式" }

        val reordered = part3 + part1 + part2
        val standard = mapAlphabet(unzigzag(reordered))
        return String(base64UrlDecode(standard), Charsets.UTF_8)
    }

    /** 每 2 个 7 字符块反转一次（奇数块反转，偶数块不变） */
    private fun unzigzag(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        var block = 0
        while (i < s.length) {
            val chunk = s.substring(i, minOf(i + GROUP, s.length))
            sb.append(if (block % 2 == 1) chunk.reversed() else chunk)
            i += GROUP
            block++
        }
        return sb.toString()
    }

    /** 自定义字母表映射回标准 base64url */
    private fun mapAlphabet(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val mapped = if (ch.code < DECODE_TABLE.size) DECODE_TABLE[ch.code] else -1
            require(mapped >= 0) { "无效的章节数据字符" }
            sb.append(mapped.toChar())
        }
        return sb.toString()
    }

    /** Base64 URL 安全解码 */
    private fun base64UrlDecode(s: String): ByteArray {
        val pad = (4 - s.length % 4) % 4
        val padded = s + "=".repeat(pad)
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
