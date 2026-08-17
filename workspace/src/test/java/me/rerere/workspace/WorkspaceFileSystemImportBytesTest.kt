package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class WorkspaceFileSystemImportBytesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fileSystem(maxWriteBytes: Long) =
        WorkspaceFileSystem(WorkspaceConfig(maxWriteBytes = maxWriteBytes))

    @Test
    fun `importBytes rejects a stream larger than the write cap`() {
        val root = tempFolder.newFolder("workspace")
        val fs = fileSystem(maxWriteBytes = 10)
        val oversized = ByteArrayInputStream(ByteArray(11) { 'a'.code.toByte() })

        assertThrows(IllegalArgumentException::class.java) {
            fs.importBytes(root, "upload.bin", oversized)
        }
    }

    @Test
    fun `importBytes deletes the partial file after rejecting an oversized stream`() {
        val root = tempFolder.newFolder("workspace")
        val fs = fileSystem(maxWriteBytes = 10)
        val oversized = ByteArrayInputStream(ByteArray(11) { 'a'.code.toByte() })

        assertThrows(IllegalArgumentException::class.java) {
            fs.importBytes(root, "upload.bin", oversized)
        }

        assertFalse(File(root, "upload.bin").exists())
    }

    @Test
    fun `importBytes accepts a stream within the write cap`() {
        val root = tempFolder.newFolder("workspace")
        val fs = fileSystem(maxWriteBytes = 10)
        val withinCap = ByteArrayInputStream(ByteArray(10) { 'a'.code.toByte() })

        val entry = fs.importBytes(root, "upload.bin", withinCap)

        assertEquals(10L, entry.sizeBytes)
    }

    @Test
    fun `per-call import cap can exceed global write cap without changing later defaults`() {
        val root = tempFolder.newFolder("workspace")
        val fs = fileSystem(maxWriteBytes = 4)

        val staged = fs.importBytes(
            root,
            "tmp/rikkahub-codex-input/c/turn/media.bin",
            ByteArrayInputStream(ByteArray(8)),
            maxBytes = 8,
        )
        assertEquals(8L, staged.sizeBytes)

        assertThrows(IllegalArgumentException::class.java) {
            fs.importBytes(root, "ordinary.bin", ByteArrayInputStream(ByteArray(5)))
        }
        assertFalse(File(root, "ordinary.bin").exists())
    }

    @Test
    fun `streaming import cannot escape workspace root`() {
        val root = tempFolder.newFolder("workspace")
        val fs = fileSystem(maxWriteBytes = 10)

        assertThrows(IllegalArgumentException::class.java) {
            fs.importBytes(root, "../../outside.bin", ByteArrayInputStream(byteArrayOf(1)), maxBytes = 10)
        }
        assertFalse(File(root.parentFile, "outside.bin").exists())
    }
}
