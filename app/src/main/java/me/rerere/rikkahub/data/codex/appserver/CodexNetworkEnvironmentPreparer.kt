package me.rerere.rikkahub.data.codex.appserver

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.WorkspaceManager
import java.net.InetAddress

/**
 * Mirrors Android's active-network view into the Codex PRoot immediately before use.
 *
 * Android browsers resolve through netd (including Private DNS and VPN handling), while the
 * native Codex process resolves from the Rootfs' network files. Keeping a small, managed hosts block for
 * OpenAI endpoints prevents that resolver split from breaking the OAuth token exchange.
 */
class CodexNetworkEnvironmentPreparer internal constructor(
    private val workspaceManager: WorkspaceManager,
    private val caBundle: AndroidTrustStoreCaBundle,
    private val networkSnapshotProvider: () -> CodexNetworkSnapshot,
) {
    constructor(
        context: Context,
        workspaceManager: WorkspaceManager,
        caBundle: AndroidTrustStoreCaBundle,
    ) : this(
        workspaceManager = workspaceManager,
        caBundle = caBundle,
        networkSnapshotProvider = { context.codexNetworkSnapshot() },
    )

    suspend fun prepare(root: String) = withContext(Dispatchers.IO) {
        caBundle.ensureReady()
        val snapshot = runCatching(networkSnapshotProvider)
            .getOrDefault(CodexNetworkSnapshot(emptyList(), emptyMap()))
        workspaceManager.patchRootfs(
            root,
            RootfsPatchOptions(
                nameservers = snapshot.nameservers,
                managedHostMappings = snapshot.hostAddresses,
            ),
        )
    }
}

internal data class CodexNetworkSnapshot(
    val nameservers: List<String>,
    val hostAddresses: Map<String, List<String>>,
)

private fun Context.codexNetworkSnapshot(): CodexNetworkSnapshot {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val activeNetwork = connectivity?.activeNetwork
    val nameservers = activeNetwork
        ?.let { connectivity.getLinkProperties(it) }
        ?.dnsServers
        ?.mapNotNull { it.hostAddress }
        ?.filter(String::isNotBlank)
        .orEmpty()
    val hostAddresses = OPENAI_NETWORK_HOSTS.mapNotNull { hostname ->
        val addresses = runCatching {
            if (activeNetwork == null) InetAddress.getAllByName(hostname)
            else activeNetwork.getAllByName(hostname)
        }.getOrElse {
            runCatching { InetAddress.getAllByName(hostname) }.getOrDefault(emptyArray<InetAddress>())
        }.asSequence()
            .filterNot { address ->
                address.isAnyLocalAddress || address.isLoopbackAddress ||
                    address.isLinkLocalAddress || address.isMulticastAddress
            }
            .mapNotNull { it.hostAddress }
            .map { it.substringBefore('%') }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        hostname.takeIf { addresses.isNotEmpty() }?.let { it to addresses }
    }.toMap()
    return CodexNetworkSnapshot(nameservers, hostAddresses)
}

private val OPENAI_NETWORK_HOSTS = listOf(
    "auth.openai.com",
)
