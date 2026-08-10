package com.sam.openclone.apk

import java.io.OutputStream
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Block ID of the APK Signature Scheme v2 block inside the APK signing block. */
private const val V2_BLOCK_ID = 0x7109871a

/** RSASSA-PKCS1-v1_5 with SHA2-256. */
private const val SIG_ALG_RSA_PKCS1_SHA256 = 0x0103

private val SIGNING_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

/** The spec's fixed chunk size: 1 MiB. */
private const val CHUNK_SIZE = 1024 * 1024

/** Domain separators keeping chunk digests distinct from the top-level digest. */
private const val CHUNK_PREFIX: Byte = 0xa5.toByte()
private const val ROOT_PREFIX: Byte = 0x5a.toByte()

/**
 * Writes an APK while computing its v2 content digest in the same pass.
 *
 * The digest covers the entry contents, the central directory and the
 * end-of-central-directory record — everything except the signing block that is
 * about to be inserted between the first and second of those. Since none of
 * those sections depend on the block's size, the whole APK can be hashed as it
 * streams past, with no temporary file and no second read.
 *
 * Chunks are hashed on a small pool: SHA-256 runs at roughly storage speed, so
 * doing it inline would halve throughput on a large app. The pool's queue is
 * bounded and saturating it makes the caller hash the chunk itself, which caps
 * memory at a few buffers no matter how fast the writer runs.
 */
internal class V2DigestSink(private val out: OutputStream) : ApkSink {

    private var written = 0L
    private val chunk = ByteArray(CHUNK_SIZE)
    private var chunkLen = 0
    private val chunkDigests = ArrayList<Future<ByteArray>>(256)

    private val workers = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() - 1))
    private val pool = ThreadPoolExecutor(
        workers, workers, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(workers * 2),
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    override val position: Long get() = written

    /** Writes bytes into the APK and folds them into the digest. */
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        written += len
        digest(b, off, len)
    }

    /** Folds bytes into the digest without emitting them. */
    fun digestOnly(b: ByteArray) = digest(b, 0, b.size)

    /** Emits bytes that the digest must not cover, i.e. the signing block. */
    fun writeOnly(b: ByteArray) {
        out.write(b, 0, b.size)
        written += b.size
    }

    private fun digest(b: ByteArray, off: Int, len: Int) {
        var p = off
        var remaining = len
        while (remaining > 0) {
            val take = minOf(remaining, CHUNK_SIZE - chunkLen)
            System.arraycopy(b, p, chunk, chunkLen, take)
            chunkLen += take
            p += take
            remaining -= take
            if (chunkLen == CHUNK_SIZE) flushChunk()
        }
    }

    /**
     * Closes off the current section. Chunking restarts at each of the three
     * section boundaries, so a section's final chunk is usually short.
     */
    fun endSection() {
        if (chunkLen > 0) flushChunk()
    }

    private fun flushChunk() {
        val copy = chunk.copyOf(chunkLen)
        chunkLen = 0
        chunkDigests.add(pool.submit<ByteArray> { digestChunk(copy) })
    }

    private fun digestChunk(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(CHUNK_PREFIX)
        md.update(littleEndian(data.size))
        md.update(data)
        return md.digest()
    }

    /** Combines the per-chunk digests into the single digest that gets signed. */
    fun finish(): ByteArray {
        endSection()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ROOT_PREFIX)
        md.update(littleEndian(chunkDigests.size))
        for (future in chunkDigests) md.update(future.get())
        pool.shutdown()
        return md.digest()
    }

    fun abort() {
        pool.shutdownNow()
    }

    private fun littleEndian(v: Int) = byteArrayOf(
        v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte(),
    )
}

/**
 * Assembles the APK signing block that carries a v2 signature.
 *
 * Android has rejected v1-only APKs since it started requiring v2 for
 * targetSdk 30, so this is the signature that actually matters; the original
 * `META-INF` v1 files are dropped by the caller rather than re-generated.
 */
internal object V2Signer {

    fun signingBlock(contentDigest: ByteArray, identity: SigningIdentity): ByteArray {
        // signed data := digests ++ certificates ++ additional attributes,
        // each a length-prefixed sequence of length-prefixed elements.
        val digests = sequenceOf(
            listOf(
                element {
                    it.i32(SIG_ALG_RSA_PKCS1_SHA256)
                    it.i32(contentDigest.size)
                    it.bytes(contentDigest)
                }
            )
        )
        val certificates = sequenceOf(listOf(identity.certificate))
        val attributes = sequenceOf(emptyList())

        val signedData = ByteWriter(digests.size + certificates.size + attributes.size)
            .bytes(digests).bytes(certificates).bytes(attributes)
            .toByteArray()

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(identity.keyPair.private)
            update(signedData)
            sign()
        }
        val signatures = sequenceOf(
            listOf(
                element {
                    it.i32(SIG_ALG_RSA_PKCS1_SHA256)
                    it.i32(signature.size)
                    it.bytes(signature)
                }
            )
        )

        val publicKey = identity.publicKeyInfo
        val signer = ByteWriter(signedData.size + signatures.size + publicKey.size + 12)
            .i32(signedData.size).bytes(signedData)
            .bytes(signatures)
            .i32(publicKey.size).bytes(publicKey)
            .toByteArray()

        // The v2 block value is a length-prefixed sequence of length-prefixed
        // signers, which is precisely what sequenceOf already produces.
        val v2Block = sequenceOf(listOf(signer))

        // APK signing block: size, ID-value pairs, size again, magic. The two
        // size fields both exclude the leading one, which is how a verifier
        // walks backwards to the block's start from the central directory.
        val pairLength = 4L + v2Block.size
        val blockSize = 8L + pairLength + 8L + SIGNING_BLOCK_MAGIC.size

        return ByteWriter((blockSize + 8).toInt())
            .i64(blockSize)
            .i64(pairLength)
            .i32(V2_BLOCK_ID)
            .bytes(v2Block)
            .i64(blockSize)
            .bytes(SIGNING_BLOCK_MAGIC)
            .toByteArray()
    }

    private inline fun element(build: (ByteWriter) -> Unit): ByteArray =
        ByteWriter(64).also(build).toByteArray()

    /** uint32 total length, then each item as uint32 length + bytes. */
    private fun sequenceOf(items: List<ByteArray>): ByteArray {
        val body = ByteWriter(64)
        for (item in items) body.i32(item.size).bytes(item)
        return ByteWriter(body.size + 4).i32(body.size).bytes(body.buf, 0, body.size).toByteArray()
    }
}
