package com.sam.openclone.apk

import java.io.IOException
import java.io.OutputStream

private const val MANIFEST_NAME = "AndroidManifest.xml"
private const val COPY_BUFFER = 256 * 1024

/** Report progress at most this often, so callbacks never dominate the copy. */
private const val PROGRESS_INTERVAL = 1L shl 21

/**
 * Produces a signed, renamed copy of an APK.
 *
 * The whole clone is a single streaming pass: entries are copied still
 * compressed, hashed on the way past, and the signature is appended from the
 * digest that pass produced. Nothing is buffered to disk, so the caller can
 * point this straight at a package-installer session.
 */
internal object ApkCloner {

    fun clone(
        sourcePath: String,
        clonePackage: String,
        cloneLabel: String?,
        identity: SigningIdentity,
        out: OutputStream,
        onProgress: ((Long) -> Unit)? = null,
    ): String {
        ZipReader(sourcePath).use { zip ->
            val manifestEntry = zip.entries.firstOrNull { it.name == MANIFEST_NAME }
                ?: throw IOException("$sourcePath has no $MANIFEST_NAME")
            val rewritten =
                ManifestRewriter.rewrite(zip.readEntry(manifestEntry), clonePackage, cloneLabel)

            val sink = V2DigestSink(out)
            try {
                val writer = ZipWriter(sink)
                val buffer = ByteArray(COPY_BUFFER)
                var nextReport = PROGRESS_INTERVAL

                for (entry in zip.entries) {
                    when {
                        entry.name == MANIFEST_NAME ->
                            writer.addEntry(entry.name, rewritten.bytes, deflate = true)
                        // The original v1 signature covers a manifest that no
                        // longer exists. It is replaced by the v2 block below,
                        // so these files are dropped rather than regenerated.
                        isV1SignatureFile(entry.name) -> Unit
                        else -> writer.copyEntry(zip, entry, buffer)
                    }
                    if (onProgress != null && sink.position >= nextReport) {
                        onProgress(sink.position)
                        nextReport = sink.position + PROGRESS_INTERVAL
                    }
                }
                sink.endSection()

                // The signing block goes between the entries and the central
                // directory. Its size is not known yet, but nothing being
                // digested depends on it: the end-of-central-directory record
                // is hashed with its offset field pointing at the block's
                // start, exactly as the spec requires.
                val centralDirectory = writer.centralDirectory()
                val blockOffset = sink.position
                val cdSize = centralDirectory.size.toLong()

                sink.digestOnly(centralDirectory)
                sink.endSection()
                sink.digestOnly(writer.endOfCentralDirectory(cdSize, blockOffset))
                sink.endSection()

                val signingBlock = V2Signer.signingBlock(sink.finish(), identity)
                sink.writeOnly(signingBlock)
                sink.writeOnly(centralDirectory)
                sink.writeOnly(
                    writer.endOfCentralDirectory(cdSize, blockOffset + signingBlock.size)
                )
                onProgress?.invoke(sink.position)
            } catch (t: Throwable) {
                sink.abort()
                throw t
            }

            return rewritten.originalPackage
        }
    }

    /** True for a JAR-signature file directly under `META-INF/`. */
    private fun isV1SignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        if (name.indexOf('/', "META-INF/".length) >= 0) return false
        val upper = name.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC")
    }
}
