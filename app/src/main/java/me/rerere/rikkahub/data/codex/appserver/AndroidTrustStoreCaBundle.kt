package me.rerere.rikkahub.data.codex.appserver

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Exports Android's current trusted CA certificates for native Codex processes inside PRoot.
 * Ubuntu Base intentionally contains no ca-certificates package, while the Codex musl binary uses
 * native TLS roots. The generated file stays in app-private storage and is refreshed once per app
 * process so Android trust-store changes are picked up after restart.
 */
class AndroidTrustStoreCaBundle internal constructor(
    private val targetFile: File,
    private val encodedCertificatesProvider: () -> List<ByteArray>,
) {
    constructor(targetFile: File) : this(targetFile, ::loadAndroidTrustedCertificates)

    @Volatile
    private var preparedFile: File? = null

    @Synchronized
    fun ensureReady(): File {
        preparedFile?.takeIf(File::isFile)?.let { return it }

        val pem = encodePemCertificateBundle(encodedCertificatesProvider())
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, ".${targetFile.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(pem.toByteArray(StandardCharsets.US_ASCII))
                output.fd.sync()
            }
            tempFile.setReadable(false, false)
            tempFile.setWritable(false, false)
            check(tempFile.setReadable(true, true) && tempFile.setWritable(true, true)) {
                "Could not restrict Android CA bundle permissions"
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            tempFile.delete()
        }
        return targetFile.also { preparedFile = it }
    }
}

internal fun encodePemCertificateBundle(encodedCertificates: List<ByteArray>): String {
    val certificates = encodedCertificates
        .map { Base64.getEncoder().encodeToString(it) }
        .distinct()
    require(certificates.isNotEmpty()) { "Android trust store contains no X.509 certificates" }
    val mimeEncoder = Base64.getMimeEncoder(64, "\n".toByteArray(StandardCharsets.US_ASCII))
    return buildString {
        certificates.forEach { canonicalBase64 ->
            val encoded = Base64.getDecoder().decode(canonicalBase64)
            append("-----BEGIN CERTIFICATE-----\n")
            append(mimeEncoder.encodeToString(encoded))
            append("\n-----END CERTIFICATE-----\n")
        }
    }
}

private fun loadAndroidTrustedCertificates(): List<ByteArray> {
    val trustStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    return trustStore.aliases().toList().sorted().mapNotNull { alias ->
        (trustStore.getCertificate(alias) as? X509Certificate)?.encoded
    }
}

private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList {
    while (this@toList.hasMoreElements()) add(this@toList.nextElement())
}
