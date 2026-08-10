package com.sam.openclone.apk

import java.io.Closeable
import java.io.IOException
import java.io.RandomAccessFile

internal const val SIG_LOCAL_HEADER = 0x04034b50
internal const val SIG_CENTRAL_ENTRY = 0x02014b50
internal const val SIG_EOCD = 0x06054b50
internal const val SIG_ZIP64_EOCD = 0x06064b50
internal const val SIG_ZIP64_LOCATOR = 0x07064b50

internal const val LOCAL_HEADER_FIXED = 30
internal const val CENTRAL_ENTRY_FIXED = 46
internal const val EOCD_FIXED = 22

/** ZIP general-purpose bit 3: sizes live in a trailing data descriptor. */
private const val FLAG_DATA_DESCRIPTOR = 1 shl 3

/**
 * One central-directory record, carrying everything needed to re-emit the entry
 * without touching its payload.
 */
internal class ZipEntry(
    val name: String,
    val nameBytes: ByteArray,
    val versionMadeBy: Int,
    val versionNeeded: Int,
    val flags: Int,
    val method: Int,
    val dosTime: Int,
    val dosDate: Int,
    val crc: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localHeaderOffset: Long,
    val internalAttrs: Int,
    val externalAttrs: Int,
) {
    val isStored: Boolean get() = method == 0
}

/**
 * Reads an APK's central directory and hands out raw, still-compressed entry
 * payloads.
 *
 * Cloning never needs to look inside an entry other than the manifest, so the
 * reader deliberately offers no inflation path: everything else is copied
 * byte-for-byte, which is what keeps cloning I/O-bound rather than CPU-bound.
 */
internal class ZipReader(private val file: RandomAccessFile) : Closeable {

    val entries: List<ZipEntry>

    init {
        entries = readCentralDirectory()
    }

    constructor(path: String) : this(RandomAccessFile(path, "r"))

    override fun close() = file.close()

    /**
     * Resolves where an entry's payload actually starts.
     *
     * The local header's name/extra lengths are authoritative and routinely
     * differ from the central record's (that is exactly where zipalign padding
     * lives), so it has to be read rather than assumed.
     */
    fun dataOffsetOf(entry: ZipEntry): Long {
        val header = ByteArray(LOCAL_HEADER_FIXED)
        file.seek(entry.localHeaderOffset)
        file.readFully(header)
        if (header.i32(0) != SIG_LOCAL_HEADER) {
            throw IOException("Bad local header for ${entry.name}")
        }
        return entry.localHeaderOffset + LOCAL_HEADER_FIXED + header.u16(26) + header.u16(28)
    }

    fun seek(offset: Long) = file.seek(offset)

    fun read(buf: ByteArray, off: Int, len: Int): Int = file.read(buf, off, len)

    fun readFully(offset: Long, buf: ByteArray, off: Int, len: Int) {
        file.seek(offset)
        file.readFully(buf, off, len)
    }

    /** Reads and inflates a whole entry. Only used for the manifest. */
    fun readEntry(entry: ZipEntry): ByteArray {
        val raw = ByteArray(entry.compressedSize.toInt())
        readFully(dataOffsetOf(entry), raw, 0, raw.size)
        if (entry.isStored) return raw

        val out = ByteArray(entry.uncompressedSize.toInt())
        val inflater = java.util.zip.Inflater(true)
        try {
            inflater.setInput(raw)
            var done = 0
            while (done < out.size) {
                val n = inflater.inflate(out, done, out.size - done)
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput()) break
                } else {
                    done += n
                }
            }
            if (done != out.size) throw IOException("Truncated entry ${entry.name}")
        } finally {
            inflater.end()
        }
        return out
    }

    private fun readCentralDirectory(): List<ZipEntry> {
        val fileLen = file.length()

        // The EOCD sits at the very end unless a trailing comment pushes it up,
        // and the comment length field caps that displacement at 64 KiB.
        val tailLen = minOf(fileLen, (EOCD_FIXED + 0xFFFF).toLong()).toInt()
        val tail = ByteArray(tailLen)
        file.seek(fileLen - tailLen)
        file.readFully(tail)

        var eocd = -1
        for (i in tailLen - EOCD_FIXED downTo 0) {
            if (tail.i32(i) == SIG_EOCD && tail.u16(i + 20) == tailLen - i - EOCD_FIXED) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw IOException("Not a ZIP file: no end-of-central-directory record")

        var totalEntries = tail.u16(eocd + 10).toLong()
        var cdSize = tail.u32(eocd + 12)
        var cdOffset = tail.u32(eocd + 16)

        // Entry counts above 65535 (or the 4 GiB size/offset ceilings) spill into
        // a ZIP64 record that sits just before the EOCD.
        val locator = eocd - 20
        if (locator >= 0 && tail.i32(locator) == SIG_ZIP64_LOCATOR) {
            val z64Offset = tail.i64(locator + 8)
            val z64 = ByteArray(56)
            file.seek(z64Offset)
            file.readFully(z64)
            if (z64.i32(0) == SIG_ZIP64_EOCD) {
                totalEntries = z64.i64(32)
                cdSize = z64.i64(40)
                cdOffset = z64.i64(48)
            }
        }

        if (cdSize > Int.MAX_VALUE) throw IOException("Central directory too large")
        val cd = ByteArray(cdSize.toInt())
        file.seek(cdOffset)
        file.readFully(cd)

        val result = ArrayList<ZipEntry>(totalEntries.toInt().coerceAtMost(1 shl 16))
        var p = 0
        while (p + CENTRAL_ENTRY_FIXED <= cd.size && cd.i32(p) == SIG_CENTRAL_ENTRY) {
            val nameLen = cd.u16(p + 28)
            val extraLen = cd.u16(p + 30)
            val commentLen = cd.u16(p + 32)
            val nameBytes = cd.copyOfRange(p + CENTRAL_ENTRY_FIXED, p + CENTRAL_ENTRY_FIXED + nameLen)

            var compressed = cd.u32(p + 20)
            var uncompressed = cd.u32(p + 24)
            var localOffset = cd.u32(p + 42)
            if (compressed == 0xFFFFFFFFL || uncompressed == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                val extraStart = p + CENTRAL_ENTRY_FIXED + nameLen
                var q = extraStart
                val extraEnd = extraStart + extraLen
                while (q + 4 <= extraEnd) {
                    val id = cd.u16(q)
                    val len = cd.u16(q + 2)
                    if (id == 0x0001) {
                        var f = q + 4
                        if (uncompressed == 0xFFFFFFFFL && f + 8 <= extraEnd) {
                            uncompressed = cd.i64(f); f += 8
                        }
                        if (compressed == 0xFFFFFFFFL && f + 8 <= extraEnd) {
                            compressed = cd.i64(f); f += 8
                        }
                        if (localOffset == 0xFFFFFFFFL && f + 8 <= extraEnd) {
                            localOffset = cd.i64(f)
                        }
                        break
                    }
                    q += 4 + len
                }
            }

            result.add(
                ZipEntry(
                    name = String(nameBytes, Charsets.UTF_8),
                    nameBytes = nameBytes,
                    versionMadeBy = cd.u16(p + 4),
                    versionNeeded = cd.u16(p + 6),
                    // Sizes are known from the central directory, so the entry is
                    // re-emitted with a complete local header and no descriptor.
                    flags = cd.u16(p + 8) and FLAG_DATA_DESCRIPTOR.inv(),
                    method = cd.u16(p + 10),
                    dosTime = cd.u16(p + 12),
                    dosDate = cd.u16(p + 14),
                    crc = cd.i32(p + 16),
                    compressedSize = compressed,
                    uncompressedSize = uncompressed,
                    localHeaderOffset = localOffset,
                    internalAttrs = cd.u16(p + 36),
                    externalAttrs = cd.i32(p + 38),
                )
            )
            p += CENTRAL_ENTRY_FIXED + nameLen + extraLen + commentLen
        }
        return result
    }
}
