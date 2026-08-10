package com.sam.openclone.clone

import android.content.Context
import com.sam.openclone.apk.CertGen
import com.sam.openclone.apk.SigningIdentity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * The one signing identity every clone on this device is signed with.
 *
 * It has to be stable. Android treats a package's signing certificate as its
 * identity, so re-cloning an app to pick up a newer version is an *upgrade*
 * only if the new APK is signed with the same key as the installed one. A
 * fresh key per clone would make every re-clone fail with a signature
 * mismatch and force an uninstall first, losing the clone's data.
 *
 * The key is generated once, lazily, and kept in app-private storage.
 */
internal object CloneKeyStore {

    private const val FILE_NAME = "clone-signing.bin"

    @Volatile
    private var cached: SigningIdentity? = null

    fun identity(context: Context): SigningIdentity {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load(context).also { cached = it }
        }
    }

    private fun load(context: Context): SigningIdentity {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            // A corrupt or partially written key is recoverable: nothing has
            // been signed with it yet that we could not sign again.
            runCatching { return read(file) }
        }
        val identity = CertGen.generate()
        write(file, identity)
        return identity
    }

    private fun read(file: File): SigningIdentity {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val private = input.readBlob()
            val public = input.readBlob()
            val certificate = input.readBlob()
            val factory = KeyFactory.getInstance("RSA")
            return SigningIdentity(
                KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(public)),
                    factory.generatePrivate(PKCS8EncodedKeySpec(private)),
                ),
                certificate,
            )
        }
    }

    private fun write(file: File, identity: SigningIdentity) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(temp.outputStream().buffered()).use { out ->
            out.writeBlob(identity.keyPair.private.encoded)
            out.writeBlob(identity.keyPair.public.encoded)
            out.writeBlob(identity.certificate)
        }
        // Rename only once the bytes are down, so a crash mid-write cannot
        // leave a half-key that later reads would accept.
        temp.renameTo(file)
    }

    private fun DataInputStream.readBlob(): ByteArray = ByteArray(readInt()).also { readFully(it) }

    private fun DataOutputStream.writeBlob(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }
}
