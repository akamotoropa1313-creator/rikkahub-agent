package me.rerere.rikkahub.di

import android.content.Context
import android.net.ConnectivityManager
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConnectionCreator
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConversationSessionOpener
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerLocalState
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSessionBindingRepository
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSessionRecovery
import me.rerere.rikkahub.data.codex.appserver.AndroidTrustStoreCaBundle
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessConversationProjectionResolver
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessGatewaySessionRegistry
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessProviderSettingsSource
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessRawResponsesBackend
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessRawResponsesProxy
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessResponsesDispatcher
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessResponsesGatewayServer
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessThreadConfigurationResolver
import me.rerere.rikkahub.data.codex.appserver.CodexHarnessTranslatedResponsesBackend
import me.rerere.rikkahub.data.codex.appserver.CodexRuntimeManager
import me.rerere.rikkahub.data.codex.appserver.CodexRuntimeResolver
import me.rerere.rikkahub.data.codex.appserver.CodexNetworkEnvironmentPreparer
import me.rerere.rikkahub.data.codex.appserver.RoomCodexAppServerLocalState
import me.rerere.rikkahub.data.codex.appserver.SettingsStoreCodexHarnessProviderSettingsSource
import me.rerere.rikkahub.data.codex.appserver.WorkspaceCodexAppServerConnectionFactory
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        val context: Context = get()
        AndroidTrustStoreCaBundle(
            File(context.filesDir, "${CodexRuntimeManager.RUNTIME_HOST_DIR_NAME}/android-ca-certificates.pem"),
        )
    }
    single<CodexAppServerLocalState> { RoomCodexAppServerLocalState(get(), get()) }
    single { CodexAppServerSessionBindingRepository(get(), get()) }
    single {
        val context: Context = get()
        CodexRuntimeManager(
            workspaceManager = get(),
            httpClient = get(),
            runtimeBaseDir = File(context.filesDir, CodexRuntimeManager.RUNTIME_HOST_DIR_NAME).apply { mkdirs() },
        )
    }
    single<CodexRuntimeResolver> {
        CodexRuntimeResolver { root ->
            get<CodexRuntimeManager>().ensureReady(root)
        }
    }
    single {
        CodexNetworkEnvironmentPreparer(
            context = get(),
            workspaceManager = get(),
            caBundle = get(),
        )
    }
    single<CodexAppServerConnectionCreator> {
        WorkspaceCodexAppServerConnectionFactory(
            workspaceManager = get(),
            runtimeResolver = get(),
            appVersion = BuildConfig.VERSION_NAME,
            networkEnvironmentPreparer = get(),
        )
    }
    single { CodexAppServerSessionRecovery(get(), get(), get(), autoRefreshModelCatalog = true) }

    // Codex harness model-provider bridge. These are lazy Koin singletons: the loopback server is
    // not started until an Assistant actually selects an external RikkaHub provider model.
    single { CodexHarnessGatewaySessionRegistry() }
    single<CodexHarnessTranslatedResponsesBackend> { CodexHarnessResponsesDispatcher(get(), get(), get()) }
    single<CodexHarnessProviderSettingsSource> { SettingsStoreCodexHarnessProviderSettingsSource(get()) }
    single<CodexHarnessRawResponsesBackend> { CodexHarnessRawResponsesProxy(get(), get(), get()) }
    single { CodexHarnessResponsesGatewayServer(get(), get(), get()) }
    single { CodexHarnessThreadConfigurationResolver(get(), get()) }
    single { CodexHarnessConversationProjectionResolver(get(), get(), get()) }
    single { CodexAppServerConversationSessionOpener(get(), get(), get(), get(), get(), autoRefreshModelCatalog = true) }

    single { ConversationRepository(get(), get(), get(), get(), get(), get(), get()) }
    single { FolderRepository(get(), get()) }
    single { MemoryRepository(get()) }
    single { GenMediaRepository(get()) }
    single { FilesRepository(get()) }
    single { FavoriteRepository(get()) }

    single {
        val context: Context = get()
        val caBundle: AndroidTrustStoreCaBundle = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                nameserversProvider = {
                    val connectivity = context.getSystemService(ConnectivityManager::class.java)
                    val activeNetwork = connectivity?.activeNetwork
                    val linkProperties = activeNetwork?.let { connectivity.getLinkProperties(it) }
                    linkProperties?.dnsServers
                        ?.mapNotNull { it.hostAddress }
                        ?.filter { it.isNotBlank() }
                        .orEmpty()
                },
                environmentProvider = {
                    caBundle.ensureReady()
                    mapOf(
                        "CODEX_CA_CERTIFICATE" to "$CA_BUNDLE_ROOTFS_PATH",
                        "SSL_CERT_FILE" to "$CA_BUNDLE_ROOTFS_PATH",
                    )
                },
            ),
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, CodexRuntimeManager.RUNTIME_HOST_DIR_NAME).apply { mkdirs() },
                    target = CodexRuntimeManager.RUNTIME_BIND_ROOT,
                ),
            ),
        )
    }

    single { RootfsInstaller(get()) }
    single { WorkspaceRepository(get(), get(), get(), get()) }
    single { FilesManager(get(), get(), get()) }
    single { SkillManager(get(), get()) }
}

private const val CA_BUNDLE_ROOTFS_PATH =
    "${CodexRuntimeManager.RUNTIME_BIND_ROOT}/android-ca-certificates.pem"
