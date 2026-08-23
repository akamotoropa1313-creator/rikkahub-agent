package me.rerere.rikkahub.data.codex.appserver

import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTrustStoreCaBundleTest {
    @Test
    fun `bundle atomically replaces stale file and caches one export per process`() {
        val directory = Files.createTempDirectory("android-ca-bundle-test").toFile()
        val target = directory.resolve("android-ca-certificates.pem").apply { writeText("stale") }
        val first = ByteArray(97) { it.toByte() }
        val second = ByteArray(131) { (255 - it).toByte() }
        var exports = 0
        val bundle = AndroidTrustStoreCaBundle(target) {
            exports++
            listOf(first, second, first)
        }

        assertEquals(target, bundle.ensureReady())
        assertEquals(target, bundle.ensureReady())
        assertEquals(1, exports)

        val blocks = CERTIFICATE_BLOCK.findAll(target.readText()).map { match ->
            Base64.getMimeDecoder().decode(match.groupValues[1])
        }.toList()
        assertEquals(2, blocks.size)
        assertArrayEquals(first, blocks[0])
        assertArrayEquals(second, blocks[1])
        assertTrue(target.readText().endsWith("-----END CERTIFICATE-----\n"))
        assertTrue(directory.listFiles().none { it.name.endsWith(".tmp") })
    }

    private companion object {
        val CERTIFICATE_BLOCK = Regex(
            "-----BEGIN CERTIFICATE-----\\n([A-Za-z0-9+/=\\n]+)-----END CERTIFICATE-----",
        )
    }
}
