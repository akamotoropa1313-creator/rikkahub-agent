package me.rerere.rikkahub.data.codex.appserver

import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CodexNetworkEnvironmentPreparerTest {
    @Test
    fun `prepare refreshes CA DNS and Android-resolved OpenAI hosts`() = runBlocking {
        val directory = Files.createTempDirectory("codex-network-preparer-test").toFile()
        val manager = WorkspaceManager(File(directory, "workspaces"))
        val root = "workspace"
        manager.ensureWorkspace(root)
        File(manager.linuxDir(root), "etc").mkdirs()
        val caTarget = File(directory, "runtime/android-ca-certificates.pem")
        var snapshot = CodexNetworkSnapshot(
            nameservers = listOf("192.0.2.53"),
            hostAddresses = mapOf("auth.openai.com" to listOf("203.0.113.10")),
        )
        val preparer = CodexNetworkEnvironmentPreparer(
            workspaceManager = manager,
            caBundle = AndroidTrustStoreCaBundle(caTarget) { listOf(byteArrayOf(1, 2, 3)) },
            networkSnapshotProvider = { snapshot },
        )

        preparer.prepare(root)

        assertTrue(caTarget.readText().contains("BEGIN CERTIFICATE"))
        assertTrue(File(manager.linuxDir(root), "etc/resolv.conf").readText().contains("nameserver 192.0.2.53"))
        assertTrue(File(manager.linuxDir(root), "etc/hosts").readText().contains("203.0.113.10 auth.openai.com"))

        snapshot = CodexNetworkSnapshot(
            nameservers = listOf("198.51.100.53"),
            hostAddresses = mapOf("auth.openai.com" to listOf("203.0.113.11")),
        )
        preparer.prepare(root)

        val resolvConf = File(manager.linuxDir(root), "etc/resolv.conf").readText()
        val hosts = File(manager.linuxDir(root), "etc/hosts").readText()
        assertTrue(resolvConf.contains("nameserver 198.51.100.53"))
        assertTrue(!resolvConf.contains("nameserver 192.0.2.53"))
        assertTrue(hosts.contains("203.0.113.11 auth.openai.com"))
        assertTrue(!hosts.contains("203.0.113.10 auth.openai.com"))
    }

    @Test
    fun `Android network lookup failure falls back to the existing Rootfs resolver`() = runBlocking {
        val directory = Files.createTempDirectory("codex-network-fallback-test").toFile()
        val manager = WorkspaceManager(File(directory, "workspaces"))
        val root = "workspace"
        manager.ensureWorkspace(root)
        val etc = File(manager.linuxDir(root), "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText("nameserver 192.0.2.53\n")
        val caTarget = File(directory, "runtime/android-ca-certificates.pem")
        val preparer = CodexNetworkEnvironmentPreparer(
            workspaceManager = manager,
            caBundle = AndroidTrustStoreCaBundle(caTarget) { listOf(byteArrayOf(1, 2, 3)) },
            networkSnapshotProvider = { error("active network disappeared") },
        )

        preparer.prepare(root)

        assertTrue(caTarget.isFile)
        assertTrue(File(etc, "resolv.conf").readText().contains("nameserver 192.0.2.53"))
    }
}
