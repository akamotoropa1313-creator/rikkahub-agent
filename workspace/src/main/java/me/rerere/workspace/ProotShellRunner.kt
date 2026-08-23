package me.rerere.workspace

import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
    private val nameserversProvider: () -> List<String> = { emptyList() },
    private val environmentProvider: () -> Map<String, String> = { emptyMap() },
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patchRootfsForCurrentNetwork(context)
        val process = newProcessBuilder(context, proot, loader).start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    override fun start(context: WorkspaceShellContext): Process {
        if (!context.linuxDir.hasUsableRootfs()) {
            throw IllegalStateException("Rootfs is not installed")
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            throw IllegalStateException("proot executable not found: ${proot.absolutePath}")
        }
        if (!loader.isFile) {
            throw IllegalStateException("proot loader not found: ${loader.absolutePath}")
        }

        context.tempDir.mkdirs()
        patchRootfsForCurrentNetwork(context)
        return newProcessBuilder(context, proot, loader).start()
    }

    private fun patchRootfsForCurrentNetwork(context: WorkspaceShellContext) {
        val nameservers = runCatching { nameserversProvider() }
            .getOrDefault(emptyList())
        patcher.patch(
            context.linuxDir,
            RootfsPatchOptions(nameservers = nameservers),
        )
    }

    private fun newProcessBuilder(
        context: WorkspaceShellContext,
        proot: File,
        loader: File,
    ): ProcessBuilder =
        ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }

    internal fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        val effectiveCwd = resolveWorkspaceRootfsCwd(context.cwd)
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            effectiveCwd,
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        val additionalEnvironment = environmentProvider().toSortedMap().map { (name, value) ->
            require(ENVIRONMENT_NAME.matches(name)) { "Invalid workspace environment name: $name" }
            require('\u0000' !in value) { "Workspace environment value contains NUL: $name" }
            "$name=$value"
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
        )
        command += additionalEnvironment
        command += listOf(
            "/bin/bash",
            "-l",
            "-c",
            // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
            "cd -- \"\$1\" && eval \"\$2\"",
            "rikkahub",
            effectiveCwd,
            context.command,
        )
        return command
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    }
}
