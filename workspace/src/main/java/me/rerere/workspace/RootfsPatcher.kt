package me.rerere.workspace

import java.io.File
import java.nio.file.Files

class RootfsPatcher {
    fun patch(
        linuxDir: File,
        options: RootfsPatchOptions = RootfsPatchOptions(),
    ) {
        val etcDir = File(linuxDir, "etc")
        if (!etcDir.isDirectory) return

        ensureRootfsDns(etcDir, options.nameservers)
        ensureHosts(etcDir, options.hostname, options.managedHostMappings)
        ensureHostname(etcDir, options.hostname)
        ensureLocale(etcDir, options.locale)
        ensureGroupNames(etcDir, options.groupIds.ifEmpty { currentSupplementaryGroupIds() })
        ensureTempDirs(linuxDir)
    }

    private fun ensureRootfsDns(
        etcDir: File,
        nameservers: List<String>,
    ) {
        val resolvConf = File(etcDir, "resolv.conf")
        val requestedServers = nameservers
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_DNS_SERVERS)
        val existingText = if (resolvConf.isFile && !Files.isSymbolicLink(resolvConf.toPath())) {
            runCatching { resolvConf.readText() }.getOrDefault("")
        } else {
            ""
        }
        val existingServers = existingText
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("nameserver ") }
            .map { it.removePrefix("nameserver").trim() }
            .filter { it.isNotBlank() }
            .toList()
        val generatedByRikkaHub = existingText.lineSequence().firstOrNull()?.trim() == GENERATED_DNS_HEADER
        val onlyLocalResolvers = existingServers.isNotEmpty() && existingServers.all { it in LOCAL_RESOLVERS }
        val missingOrInvalid = Files.isSymbolicLink(resolvConf.toPath()) || !resolvConf.isFile

        // Refresh files that RikkaHub owns whenever Android's active-network DNS changes. This is
        // important on PRoot: glibc cannot use Android's netd resolver directly, so a stale/public
        // resolv.conf can fail on mobile/private-DNS networks even while native Android networking
        // works. Preserve an explicit non-RikkaHub resolver file so advanced workspace users keep
        // control of their Linux DNS configuration.
        val shouldWrite = when {
            missingOrInvalid -> true
            generatedByRikkaHub && requestedServers.isNotEmpty() -> existingServers != requestedServers
            generatedByRikkaHub -> existingServers.isEmpty()
            onlyLocalResolvers -> true
            existingServers.isEmpty() -> true
            else -> false
        }
        if (!shouldWrite) return

        if (resolvConf.exists() || Files.isSymbolicLink(resolvConf.toPath())) {
            resolvConf.delete()
        }

        val servers = requestedServers.ifEmpty { DEFAULT_DNS_SERVERS }

        resolvConf.writeText(
            buildString {
                appendLine(GENERATED_DNS_HEADER)
                servers.forEach { appendLine("nameserver $it") }
                appendLine("options edns0 trust-ad")
            }
        )
    }

    private fun ensureHosts(
        etcDir: File,
        hostname: String,
        managedHostMappings: Map<String, List<String>>?,
    ) {
        val hosts = File(etcDir, "hosts")
        val lines = if (hosts.isFile) hosts.readLines() else emptyList()
        val updatedLines = if (managedHostMappings == null) {
            lines.toMutableList()
        } else {
            removeManagedHostBlocks(lines).toMutableList()
        }
        val hasIpv4Localhost = updatedLines.any { line ->
            val normalized = line.substringBefore('#').trim().split(WHITESPACE_REGEX)
            normalized.firstOrNull() == "127.0.0.1" && "localhost" in normalized.drop(1)
        }
        val hasIpv6Localhost = updatedLines.any { line ->
            val normalized = line.substringBefore('#').trim().split(WHITESPACE_REGEX)
            normalized.firstOrNull() == "::1" && "localhost" in normalized.drop(1)
        }
        if (hasIpv4Localhost && hasIpv6Localhost && managedHostMappings == null) return

        if (!hasIpv4Localhost) {
            updatedLines += buildString {
                append("127.0.0.1 localhost")
                if (hostname.isNotBlank() && hostname != "localhost") {
                    append(" ")
                    append(hostname)
                }
            }
        }
        if (!hasIpv6Localhost) {
            updatedLines += "::1 localhost ip6-localhost ip6-loopback"
        }

        val normalizedMappings = managedHostMappings
            ?.mapNotNull { (hostname, addresses) ->
                val normalizedHostname = hostname
                    .trim()
                    .lowercase()
                    .removeSuffix(".")
                    .takeIf { HOSTNAME_REGEX.matches(it) }
                    ?: return@mapNotNull null
                val normalizedAddresses = addresses
                    .map { it.trim().substringBefore('%') }
                    .filter { NUMERIC_ADDRESS_REGEX.matches(it) }
                    .distinct()
                    .take(MAX_MANAGED_ADDRESSES_PER_HOST)
                normalizedHostname.takeIf { normalizedAddresses.isNotEmpty() }
                    ?.let { it to normalizedAddresses }
            }
            .orEmpty()
        if (normalizedMappings.isNotEmpty()) {
            if (updatedLines.isNotEmpty() && updatedLines.last().isNotBlank()) updatedLines += ""
            updatedLines += MANAGED_HOSTS_BEGIN
            normalizedMappings.forEach { (managedHostname, addresses) ->
                addresses.forEach { address -> updatedLines += "$address $managedHostname" }
            }
            updatedLines += MANAGED_HOSTS_END
        }

        val updatedText = updatedLines.joinToString(separator = "\n", postfix = "\n")
        if (hosts.isFile && hosts.readText() == updatedText) return
        hosts.parentFile?.mkdirs()
        hosts.writeText(updatedText)
    }

    private fun removeManagedHostBlocks(lines: List<String>): List<String> = buildList {
        var index = 0
        while (index < lines.size) {
            if (lines[index].trim() == MANAGED_HOSTS_BEGIN) {
                val endIndex = (index + 1 until lines.size)
                    .firstOrNull { lines[it].trim() == MANAGED_HOSTS_END }
                if (endIndex != null) {
                    index = endIndex + 1
                    continue
                }
            }
            add(lines[index])
            index++
        }
    }

    private fun ensureHostname(etcDir: File, hostname: String) {
        val target = File(etcDir, "hostname")
        if (target.isFile && target.readText().trim().isNotBlank()) return
        target.writeText("${hostname.ifBlank { DEFAULT_HOSTNAME }}\n")
    }

    private fun ensureLocale(etcDir: File, locale: String) {
        val defaultDir = File(etcDir, "default").apply { mkdirs() }
        val target = File(defaultDir, "locale")
        val lines = if (target.isFile) target.readLines().toMutableList() else mutableListOf()
        if (lines.any { it.trim().startsWith("LANG=") }) return
        lines += "LANG=$locale"
        target.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun ensureGroupNames(etcDir: File, groupIds: List<Long>) {
        val target = File(etcDir, "group")
        if (!target.exists()) {
            target.writeText("root:x:0:\n")
        }
        val lines = target.readLines().toMutableList()
        val existingIds = lines.mapNotNull { line ->
            line.split(':').getOrNull(2)?.toLongOrNull()
        }.toSet()
        val existingNames = lines.mapNotNull { line ->
            line.substringBefore(':').takeIf { it.isNotBlank() }
        }.toSet()
        val additions = groupIds
            .filter { it > 0 && it !in existingIds }
            .distinct()
            .map { id ->
                val baseName = "android_gid_$id"
                val name = if (baseName in existingNames) "${baseName}_workspace" else baseName
                "$name:x:$id:"
            }
        if (additions.isEmpty()) return

        target.appendText(
            buildString {
                if (target.length() > 0 && !target.readText().endsWith('\n')) {
                    appendLine()
                }
                additions.forEach { appendLine(it) }
            }
        )
    }

    private fun ensureTempDirs(linuxDir: File) {
        listOf("tmp", "var/tmp", "root").forEach { path ->
            File(linuxDir, path).mkdirs()
        }
        listOf(File(linuxDir, "tmp"), File(linuxDir, "var/tmp")).forEach { dir ->
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        }
        File(linuxDir, "root").apply {
            setReadable(true, true)
            setWritable(true, true)
            setExecutable(true, true)
        }
    }

    private fun currentSupplementaryGroupIds(): List<Long> {
        val status = File("/proc/self/status")
        if (!status.isFile) return emptyList()
        val groups = status.readLines().firstOrNull { it.startsWith("Groups:") } ?: return emptyList()
        return groups
            .removePrefix("Groups:")
            .trim()
            .split(WHITESPACE_REGEX)
            .mapNotNull { it.toLongOrNull() }
    }

    private companion object {
        private const val MAX_DNS_SERVERS = 3
        private const val DEFAULT_HOSTNAME = "localhost"
        private const val GENERATED_DNS_HEADER = "# Generated by RikkaHub workspace."
        private const val MANAGED_HOSTS_BEGIN = "# BEGIN RikkaHub managed network hosts"
        private const val MANAGED_HOSTS_END = "# END RikkaHub managed network hosts"
        private const val MAX_MANAGED_ADDRESSES_PER_HOST = 8
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val HOSTNAME_REGEX = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
        private val NUMERIC_ADDRESS_REGEX = Regex("[0-9A-Fa-f:.]+")
        private val LOCAL_RESOLVERS = setOf(
            "127.0.0.1",
            "127.0.0.53",
            "::1",
        )
        private val DEFAULT_DNS_SERVERS = listOf(
            "1.1.1.1",
            "8.8.8.8",
            "223.5.5.5",
        )
    }
}

data class RootfsPatchOptions(
    val nameservers: List<String> = emptyList(),
    /** null preserves the existing managed block; an empty map explicitly removes it. */
    val managedHostMappings: Map<String, List<String>>? = null,
    val hostname: String = "localhost",
    val locale: String = "C.UTF-8",
    val groupIds: List<Long> = emptyList(),
)
