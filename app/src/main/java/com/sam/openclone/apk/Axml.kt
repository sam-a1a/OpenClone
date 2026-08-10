package com.sam.openclone.apk

import java.io.IOException

// Chunk types from frameworks/base ResourceTypes.h.
private const val RES_STRING_POOL_TYPE = 0x0001
private const val RES_XML_TYPE = 0x0003
private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
private const val RES_XML_START_ELEMENT_TYPE = 0x0102
private const val RES_XML_END_ELEMENT_TYPE = 0x0103
private const val RES_XML_CDATA_TYPE = 0x0104
private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

private const val FLAG_SORTED = 1 shl 0
private const val FLAG_UTF8 = 1 shl 8

internal const val TYPE_NULL = 0x00
internal const val TYPE_STRING = 0x03

/** A string-pool reference that is absent. */
internal const val NO_STRING = -1

internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/**
 * The string pool backing a binary XML document.
 *
 * Strings can only be appended, never reordered or edited in place. Two things
 * depend on that: the resource-map chunk addresses attribute names purely by
 * position, and a single pool entry is routinely shared by unrelated
 * attributes, so mutating one would silently change the others.
 */
internal class AxmlStringPool(
    private val strings: MutableList<String>,
    private val utf8: Boolean,
    private val styleOffsets: IntArray,
    private val styleData: ByteArray,
) {
    private val index = HashMap<String, Int>(strings.size * 2).apply {
        for (i in strings.indices) putIfAbsent(strings[i], i)
    }

    operator fun get(ref: Int): String? = if (ref in strings.indices) strings[ref] else null

    /** Returns the index of [s], appending it to the pool if it is not present. */
    fun add(s: String): Int {
        index[s]?.let { return it }
        strings.add(s)
        val at = strings.size - 1
        index[s] = at
        return at
    }

    fun serialize(): ByteArray {
        val offsets = IntArray(strings.size)
        val data = ByteWriter(strings.size * 16)
        for (i in strings.indices) {
            offsets[i] = data.size
            if (utf8) encodeUtf8(data, strings[i]) else encodeUtf16(data, strings[i])
        }
        // Both data sections are padded to a 4-byte boundary.
        while (data.size and 3 != 0) data.u8(0)

        val headerSize = 28
        val stringsStart = headerSize + 4 * strings.size + 4 * styleOffsets.size
        val stylesStart = if (styleOffsets.isEmpty()) 0 else stringsStart + data.size

        val w = ByteWriter(stringsStart + data.size + styleData.size + 8)
        w.u16(RES_STRING_POOL_TYPE)
        w.u16(headerSize)
        val sizeAt = w.reserveI32()
        w.i32(strings.size)
        w.i32(styleOffsets.size)
        // SORTED is dropped: appended strings are not in sort order, and the
        // flag would license a binary search that no longer holds.
        w.i32(if (utf8) FLAG_UTF8 else 0)
        w.i32(stringsStart)
        w.i32(stylesStart)
        for (o in offsets) w.i32(o)
        for (o in styleOffsets) w.i32(o)
        w.bytes(data.buf, 0, data.size)
        if (styleData.isNotEmpty()) {
            w.bytes(styleData)
            while (w.size and 3 != 0) w.u8(0)
        }
        w.patchI32(sizeAt, w.size)
        return w.toByteArray()
    }

    private fun encodeUtf16(w: ByteWriter, s: String) {
        val len = s.length
        if (len > 0x7FFF) {
            w.u16((len ushr 16) or 0x8000)
            w.u16(len and 0xFFFF)
        } else {
            w.u16(len)
        }
        for (c in s) w.u16(c.code)
        w.u16(0)
    }

    private fun encodeUtf8(w: ByteWriter, s: String) {
        // Two lengths: the UTF-16 code-unit count first, then the byte count.
        val bytes = s.toByteArray(Charsets.UTF_8)
        encodeUtf8Length(w, s.length)
        encodeUtf8Length(w, bytes.size)
        w.bytes(bytes)
        w.u8(0)
    }

    private fun encodeUtf8Length(w: ByteWriter, len: Int) {
        if (len > 0x7F) {
            w.u8((len ushr 8) or 0x80)
            w.u8(len and 0xFF)
        } else {
            w.u8(len)
        }
    }

    companion object {
        fun parse(data: ByteArray, at: Int): AxmlStringPool {
            val headerSize = data.u16(at + 2)
            val chunkSize = data.i32(at + 4)
            val stringCount = data.i32(at + 8)
            val styleCount = data.i32(at + 12)
            val flags = data.i32(at + 16)
            val stringsStart = data.i32(at + 20)
            val stylesStart = data.i32(at + 24)
            val utf8 = flags and FLAG_UTF8 != 0

            val strings = ArrayList<String>(stringCount)
            for (i in 0 until stringCount) {
                val offset = at + stringsStart + data.i32(at + headerSize + 4 * i)
                strings.add(if (utf8) decodeUtf8(data, offset) else decodeUtf16(data, offset))
            }

            val styleOffsets = IntArray(styleCount) { data.i32(at + headerSize + 4 * (stringCount + it)) }
            // Style spans are byte offsets into their own section; both are kept
            // verbatim, which stays correct because strings are only appended.
            val styleData = if (styleCount == 0) ByteArray(0) else {
                data.copyOfRange(at + stylesStart, at + chunkSize)
            }
            return AxmlStringPool(strings, utf8, styleOffsets, styleData)
        }

        private fun decodeUtf16(data: ByteArray, at: Int): String {
            var p = at
            var len = data.u16(p); p += 2
            if (len and 0x8000 != 0) {
                len = ((len and 0x7FFF) shl 16) or data.u16(p); p += 2
            }
            val chars = CharArray(len)
            for (i in 0 until len) chars[i] = Char(data.u16(p + 2 * i))
            return String(chars)
        }

        private fun decodeUtf8(data: ByteArray, at: Int): String {
            var p = at
            // Skip the UTF-16 length; the byte length that follows is what
            // actually delimits the encoded data.
            if (data.u8(p) and 0x80 != 0) p += 2 else p += 1
            var byteLen = data.u8(p)
            if (byteLen and 0x80 != 0) {
                byteLen = ((byteLen and 0x7F) shl 8) or data.u8(p + 1); p += 2
            } else {
                p += 1
            }
            return String(data, p, byteLen, Charsets.UTF_8)
        }
    }
}

internal class AxmlAttribute(
    var ns: Int,
    var name: Int,
    var rawValue: Int,
    var valueType: Int,
    var data: Int,
) {
    /** Points both the raw and typed value at [ref], as a compiled string attribute. */
    fun setString(ref: Int) {
        rawValue = ref
        valueType = TYPE_STRING
        data = ref
    }
}

internal sealed class AxmlChunk {
    class Namespace(
        val start: Boolean,
        val lineNumber: Int,
        val comment: Int,
        val prefix: Int,
        val uri: Int,
    ) : AxmlChunk()

    class StartElement(
        val lineNumber: Int,
        val comment: Int,
        val ns: Int,
        val name: Int,
        // 1-based back-references to the id/class/style attributes, 0 when
        // absent. They shift if an attribute is ever removed.
        var idIndex: Int,
        var classIndex: Int,
        var styleIndex: Int,
        val attributes: MutableList<AxmlAttribute>,
    ) : AxmlChunk()

    class EndElement(
        val lineNumber: Int,
        val comment: Int,
        val ns: Int,
        val name: Int,
    ) : AxmlChunk()

    class CData(
        val lineNumber: Int,
        val comment: Int,
        val data: Int,
        val valueType: Int,
        val value: Int,
    ) : AxmlChunk()

    /** Any chunk type this code does not model, preserved byte-for-byte. */
    class Raw(val bytes: ByteArray) : AxmlChunk()
}

/**
 * A parsed binary XML document, kept as a flat chunk list.
 *
 * Nesting is implicit in the start/end ordering, and rewriting never moves
 * elements around, so a list re-serializes to the same shape a tree would while
 * being cheaper to walk.
 */
internal class AxmlDocument(
    val pool: AxmlStringPool,
    private val resourceMap: ByteArray?,
    val chunks: List<AxmlChunk>,
) {
    fun toByteArray(): ByteArray {
        val body = ByteWriter(1 shl 14)
        for (chunk in chunks) writeChunk(body, chunk)

        val pool = pool.serialize()
        val w = ByteWriter(8 + pool.size + (resourceMap?.size ?: 0) + body.size)
        w.u16(RES_XML_TYPE)
        w.u16(8)
        val sizeAt = w.reserveI32()
        w.bytes(pool)
        resourceMap?.let { w.bytes(it) }
        w.bytes(body.buf, 0, body.size)
        w.patchI32(sizeAt, w.size)
        return w.toByteArray()
    }

    private fun writeChunk(w: ByteWriter, chunk: AxmlChunk) {
        if (chunk is AxmlChunk.Raw) {
            w.bytes(chunk.bytes)
            return
        }
        val start = w.size
        val type = when (chunk) {
            is AxmlChunk.Namespace ->
                if (chunk.start) RES_XML_START_NAMESPACE_TYPE else RES_XML_END_NAMESPACE_TYPE
            is AxmlChunk.StartElement -> RES_XML_START_ELEMENT_TYPE
            is AxmlChunk.EndElement -> RES_XML_END_ELEMENT_TYPE
            is AxmlChunk.CData -> RES_XML_CDATA_TYPE
            is AxmlChunk.Raw -> error("handled above")
        }
        w.u16(type)
        w.u16(16) // ResXMLTree_node header size
        val sizeAt = w.reserveI32()
        when (chunk) {
            is AxmlChunk.Namespace -> {
                w.i32(chunk.lineNumber); w.i32(chunk.comment)
                w.i32(chunk.prefix); w.i32(chunk.uri)
            }
            is AxmlChunk.StartElement -> {
                w.i32(chunk.lineNumber); w.i32(chunk.comment)
                w.i32(chunk.ns); w.i32(chunk.name)
                w.u16(20) // attributeStart, relative to this extension struct
                w.u16(20) // attributeSize
                w.u16(chunk.attributes.size)
                w.u16(chunk.idIndex); w.u16(chunk.classIndex); w.u16(chunk.styleIndex)
                for (a in chunk.attributes) {
                    w.i32(a.ns); w.i32(a.name); w.i32(a.rawValue)
                    w.u16(8) // Res_value size
                    w.u8(0)  // res0, must be zero
                    w.u8(a.valueType)
                    w.i32(a.data)
                }
            }
            is AxmlChunk.EndElement -> {
                w.i32(chunk.lineNumber); w.i32(chunk.comment)
                w.i32(chunk.ns); w.i32(chunk.name)
            }
            is AxmlChunk.CData -> {
                w.i32(chunk.lineNumber); w.i32(chunk.comment)
                w.i32(chunk.data)
                w.u16(8); w.u8(0); w.u8(chunk.valueType); w.i32(chunk.value)
            }
            is AxmlChunk.Raw -> error("handled above")
        }
        w.patchI32(sizeAt, w.size - start)
    }

    companion object {
        fun parse(data: ByteArray): AxmlDocument {
            if (data.size < 8 || data.u16(0) != RES_XML_TYPE) {
                throw IOException("Not a binary XML document")
            }
            val fileSize = minOf(data.i32(4), data.size)

            var pool: AxmlStringPool? = null
            var resourceMap: ByteArray? = null
            val chunks = ArrayList<AxmlChunk>(64)

            var p = data.u16(2) // skip the file header
            while (p + 8 <= fileSize) {
                val type = data.u16(p)
                val headerSize = data.u16(p + 2)
                val size = data.i32(p + 4)
                if (size < 8 || p + size > fileSize) break

                when (type) {
                    RES_STRING_POOL_TYPE -> pool = AxmlStringPool.parse(data, p)
                    RES_XML_RESOURCE_MAP_TYPE -> resourceMap = data.copyOfRange(p, p + size)
                    RES_XML_START_NAMESPACE_TYPE, RES_XML_END_NAMESPACE_TYPE -> chunks.add(
                        AxmlChunk.Namespace(
                            start = type == RES_XML_START_NAMESPACE_TYPE,
                            lineNumber = data.i32(p + 8),
                            comment = data.i32(p + 12),
                            prefix = data.i32(p + headerSize),
                            uri = data.i32(p + headerSize + 4),
                        )
                    )
                    RES_XML_START_ELEMENT_TYPE -> {
                        val ext = p + headerSize
                        val attrStart = data.u16(ext + 8)
                        val attrSize = data.u16(ext + 10)
                        val attrCount = data.u16(ext + 12)
                        val attrs = ArrayList<AxmlAttribute>(attrCount)
                        for (i in 0 until attrCount) {
                            val a = ext + attrStart + i * attrSize
                            attrs.add(
                                AxmlAttribute(
                                    ns = data.i32(a),
                                    name = data.i32(a + 4),
                                    rawValue = data.i32(a + 8),
                                    valueType = data.u8(a + 15),
                                    data = data.i32(a + 16),
                                )
                            )
                        }
                        chunks.add(
                            AxmlChunk.StartElement(
                                lineNumber = data.i32(p + 8),
                                comment = data.i32(p + 12),
                                ns = data.i32(ext),
                                name = data.i32(ext + 4),
                                idIndex = data.u16(ext + 14),
                                classIndex = data.u16(ext + 16),
                                styleIndex = data.u16(ext + 18),
                                attributes = attrs,
                            )
                        )
                    }
                    RES_XML_END_ELEMENT_TYPE -> chunks.add(
                        AxmlChunk.EndElement(
                            lineNumber = data.i32(p + 8),
                            comment = data.i32(p + 12),
                            ns = data.i32(p + headerSize),
                            name = data.i32(p + headerSize + 4),
                        )
                    )
                    RES_XML_CDATA_TYPE -> chunks.add(
                        AxmlChunk.CData(
                            lineNumber = data.i32(p + 8),
                            comment = data.i32(p + 12),
                            data = data.i32(p + headerSize),
                            valueType = data.u8(p + headerSize + 7),
                            value = data.i32(p + headerSize + 8),
                        )
                    )
                    else -> chunks.add(AxmlChunk.Raw(data.copyOfRange(p, p + size)))
                }
                p += size
            }

            return AxmlDocument(
                pool ?: throw IOException("Binary XML has no string pool"),
                resourceMap,
                chunks,
            )
        }
    }
}
