package com.sam.openclone.apk

/**
 * Little-endian primitives. Every format this package touches — ZIP, binary
 * XML, the APK signing block — is little-endian, so these are the only
 * accessors used throughout.
 */

internal fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF

internal fun ByteArray.u16(o: Int): Int =
    (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.i32(o: Int): Int =
    (this[o].toInt() and 0xFF) or
        ((this[o + 1].toInt() and 0xFF) shl 8) or
        ((this[o + 2].toInt() and 0xFF) shl 16) or
        ((this[o + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.u32(o: Int): Long = i32(o).toLong() and 0xFFFFFFFFL

internal fun ByteArray.i64(o: Int): Long =
    (i32(o).toLong() and 0xFFFFFFFFL) or (i32(o + 4).toLong() shl 32)

/**
 * Append-only little-endian byte buffer.
 *
 * Used to build every structure this package emits. Exposes its backing array
 * so callers can hand it straight to a sink or a [java.security.MessageDigest]
 * without an intermediate copy.
 */
internal class ByteWriter(initialCapacity: Int = 256) {
    var buf: ByteArray = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
        private set
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        val need = size + extra
        if (need <= buf.size) return
        var cap = buf.size shl 1
        while (cap < need) cap = cap shl 1
        buf = buf.copyOf(cap)
    }

    fun u8(v: Int) = apply {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun u16(v: Int) = apply {
        ensure(2)
        buf[size++] = v.toByte()
        buf[size++] = (v ushr 8).toByte()
    }

    fun i32(v: Int) = apply {
        ensure(4)
        buf[size++] = v.toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 24).toByte()
    }

    /** Writes the low 32 bits of [v]; callers guarantee the value fits. */
    fun u32(v: Long) = i32(v.toInt())

    fun i64(v: Long) = apply {
        i32(v.toInt())
        i32((v ushr 32).toInt())
    }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size) = apply {
        ensure(len)
        System.arraycopy(b, off, buf, size, len)
        size += len
    }

    fun zeros(n: Int) = apply {
        ensure(n)
        java.util.Arrays.fill(buf, size, size + n, 0)
        size += n
    }

    /** Overwrites four bytes already written, for back-patching length fields. */
    fun patchI32(at: Int, v: Int) {
        buf[at] = v.toByte()
        buf[at + 1] = (v ushr 8).toByte()
        buf[at + 2] = (v ushr 16).toByte()
        buf[at + 3] = (v ushr 24).toByte()
    }

    /** Reserves four bytes for a length that is patched once its extent is known. */
    fun reserveI32(): Int {
        val at = size
        i32(0)
        return at
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
