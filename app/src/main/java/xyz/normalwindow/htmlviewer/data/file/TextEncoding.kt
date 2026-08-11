package xyz.normalwindow.htmlviewer.data.file

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 检测结果 */
data class DecodedText(
    val content: String,
    /** 实际使用的编码名(UTF-8 / GBK / UTF-16LE / UTF-16BE) */
    val encoding: String
)

/**
 * 文本编码检测工具。
 * 策略:1) BOM 优先;2) 严格 UTF-8;3) GB18030(GBK 超集);
 * 4) 兜底 UTF-8 容错解码。兼容国内常见的 GBK 存量 HTML 文件。
 */
object TextEncoding {

    const val UTF_8 = "UTF-8"
    const val GBK = "GBK"
    const val UTF_16LE = "UTF-16LE"
    const val UTF_16BE = "UTF-16BE"

    private val utf8 = Charset.forName(UTF_8)
    private val gb18030 = Charset.forName("GB18030")

    /** 检测字节流的编码名,不做解码 */
    fun detect(bytes: ByteArray): String = when {
        hasBom(bytes, 0xEF, 0xBB, 0xBF) -> UTF_8
        hasBom(bytes, 0xFF, 0xFE) -> UTF_16LE
        hasBom(bytes, 0xFE, 0xFF) -> UTF_16BE
        canStrictDecode(bytes, utf8) -> UTF_8
        canStrictDecode(bytes, gb18030) -> GBK
        else -> UTF_8
    }

    /** 按 BOM/启发式检测结果解码(自动剥离 BOM) */
    fun decode(bytes: ByteArray): DecodedText {
        val encoding = detect(bytes)
        val (charset, offset) = when (encoding) {
            UTF_8 -> if (hasBom(bytes, 0xEF, 0xBB, 0xBF)) utf8 to 3 else utf8 to 0
            UTF_16LE -> Charset.forName(UTF_16LE) to 2
            UTF_16BE -> Charset.forName(UTF_16BE) to 2
            else -> gb18030 to 0
        }
        val content = if (offset > 0) {
            charset.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } else {
            // 已通过严格校验,直接按对应 charset 容错解码
            String(bytes, charset)
        }
        return DecodedText(content = content, encoding = encoding)
    }

    /** 按指定编码编码(UTF-8 时保留 BOM 由调用方决定;默认不写 BOM) */
    fun encode(content: String, encoding: String): ByteArray {
        val charset = Charset.forName(encoding)
        return when (encoding) {
            UTF_16LE, UTF_16BE -> {
                // ByteBuffer.array() 可能含超出 limit 的尾部字节,必须按 remaining 截取
                val buf = charset.encode(content)
                ByteArray(buf.remaining()).also { buf.get(it) }
            }
            else -> content.toByteArray(charset)
        }
    }

    private fun canStrictDecode(bytes: ByteArray, charset: Charset): Boolean {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun hasBom(bytes: ByteArray, vararg bom: Int): Boolean {
        if (bytes.size < bom.size) return false
        return bom.indices.all { (bytes[it].toInt() and 0xFF) == bom[it] }
    }
}
