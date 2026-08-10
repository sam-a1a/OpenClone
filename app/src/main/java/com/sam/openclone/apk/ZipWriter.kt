package com.sam.openclone.apk

import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Extra-field header ID reserved for alignment padding by the Android tools. */
private const val EXTRA_ID_ALIGNMENT = 0xd935

/** Padding record header: 2-byte ID + 2-byte length. */
private const val EXTRA_HEADER_SIZE = 4

/**
 * Destination for the APK being assembled, tracking how many bytes have gone by
 * so entries can be aligned against the real file offset.
 *
 * The signer supplies an implementation that digests on the way through, which
 * is what lets a clone be rewritten, hashed and streamed out in a single pass.
 */
internal interface ApkSink {
    val position: Long
    fun write(b: ByteArray, off: Int, len: Int)
}

/**
 * Assembles an APK from raw entry payloads.
 *
 * Copied entries keep their original compressed bytes, CRC and sizes untouched,
 * so cloning a 300 MB app costs one sequential read and one sequential write
 * rather than a decompress/recompress cycle.
 */
internal class ZipWriter(private val sink: ApkSink) {

    private val central = ByteWriter(1 shl 16)
    private var count = 0

    val entryCount: Int get() = count

    /** Streams [entry]'s stored bytes from [reader] straight through to the sink. */
    fun copyEntry(reader: ZipReader, entry: ZipEntry, buf: ByteArray) {
        val sourceOffset = reader.dataOffsetOf(entry)
        writeLocalHeader(entry, alignmentFor(entry, sourceOffset))

        reader.seek(sourceOffset)
        var remaining = entry.compressedSize
        while (remaining > 0) {
            val want = if (remaining < buf.size) remaining.toInt() else buf.size
            val n = reader.read(buf, 0, want)
            if (n <= 0) throw IOException("Truncated payload for ${entry.name}")
            sink.write(buf, 0, n)
            remaining -= n
        }
    }

    /** Adds a freshly built entry — in practice, the rewritten manifest. */
    fun addEntry(name: String, data: ByteArray, deflate: Boolean) {
        val crc = CRC32().apply { update(data) }.value.toInt()
        val payload = if (deflate) deflate(data) else data
        // Deflate can lose on tiny or incompressible input; store it if so.
        val stored = !deflate || payload.size >= data.size
        val body = if (stored) data else payload

        val entry = ZipEntry(
            name = name,
            nameBytes = name.toByteArray(Charsets.UTF_8),
            versionMadeBy = 0x0314,
            versionNeeded = if (stored) 10 else 20,
            flags = 0,
            method = if (stored) 0 else 8,
            dosTime = 0,
            dosDate = 0x21,
            crc = crc,
            compressedSize = body.size.toLong(),
            uncompressedSize = data.size.toLong(),
            localHeaderOffset = 0,
            internalAttrs = 0,
            externalAttrs = 0,
        )
        writeLocalHeader(entry, if (stored) 4 else 1)
        sink.write(body, 0, body.size)
    }

    /**
     * Uncompressed entries are read in place by the platform: `resources.arsc`
     * is mapped at 4 bytes and uncompressed `.so` files at a page boundary, so
     * losing that alignment would make the clone fail to install or load.
     */
    private fun alignmentFor(entry: ZipEntry, originalDataOffset: Long): Int {
        if (!entry.isStored) return 1
        if (entry.name.endsWith(".so")) {
            // Keep 16 KiB if the source had it, so clones stay valid on devices
            // with 16 KiB pages; otherwise the classic 4 KiB page is enough.
            return if (originalDataOffset % 16384L == 0L) 16384 else 4096
        }
        return 4
    }

    private fun writeLocalHeader(entry: ZipEntry, alignment: Int) {
        val localOffset = sink.position
        val nameLen = entry.nameBytes.size

        // Pad through the extra field so the payload lands on a multiple of
        // `alignment`, accounting for the 4-byte padding record header.
        var extraLen = 0
        var padding = 0
        if (alignment > 1) {
            val afterHeader = localOffset + LOCAL_HEADER_FIXED + nameLen + EXTRA_HEADER_SIZE
            padding = Math.floorMod(-afterHeader, alignment.toLong()).toInt()
            extraLen = EXTRA_HEADER_SIZE + padding
        }

        val header = ByteWriter(LOCAL_HEADER_FIXED + nameLen + extraLen)
        header.i32(SIG_LOCAL_HEADER)
        header.u16(entry.versionNeeded)
        header.u16(entry.flags)
        header.u16(entry.method)
        header.u16(entry.dosTime)
        header.u16(entry.dosDate)
        header.i32(entry.crc)
        header.u32(entry.compressedSize)
        header.u32(entry.uncompressedSize)
        header.u16(nameLen)
        header.u16(extraLen)
        header.bytes(entry.nameBytes)
        if (extraLen > 0) {
            header.u16(EXTRA_ID_ALIGNMENT)
            header.u16(padding)
            header.zeros(padding)
        }
        sink.write(header.buf, 0, header.size)

        appendCentralRecord(entry, localOffset)
    }

    private fun appendCentralRecord(entry: ZipEntry, localOffset: Long) {
        central.i32(SIG_CENTRAL_ENTRY)
        central.u16(entry.versionMadeBy)
        central.u16(entry.versionNeeded)
        central.u16(entry.flags)
        central.u16(entry.method)
        central.u16(entry.dosTime)
        central.u16(entry.dosDate)
        central.i32(entry.crc)
        central.u32(entry.compressedSize)
        central.u32(entry.uncompressedSize)
        central.u16(entry.nameBytes.size)
        central.u16(0) // alignment padding is a local-header concern only
        central.u16(0) // comment
        central.u16(0) // disk number
        central.u16(entry.internalAttrs)
        central.i32(entry.externalAttrs)
        central.u32(localOffset)
        central.bytes(entry.nameBytes)
        count++
    }

    fun centralDirectory(): ByteArray = central.toByteArray()

    fun endOfCentralDirectory(cdSize: Long, cdOffset: Long): ByteArray {
        if (count > 0xFFFF) throw IOException("Too many entries for a non-ZIP64 APK")
        val w = ByteWriter(EOCD_FIXED)
        w.i32(SIG_EOCD)
        w.u16(0) // this disk
        w.u16(0) // disk with start of central directory
        w.u16(count)
        w.u16(count)
        w.u32(cdSize)
        w.u32(cdOffset)
        w.u16(0) // comment length
        return w.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteWriter(data.size)
            val chunk = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                if (n <= 0) break
                out.bytes(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }
}
