package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerLocalState
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSessionBindingRepository
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerSessionRecovery
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConversationSessionOpener
import me.rerere.rikkahub.data.codex.appserver.WorkspaceCodexAppServerConnectionFactory
import me.rerere.rikkahub.data.codex.appserver.RoomCodexAppServerLocalState
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerConnectionCreator
import me.rerere.rikkahub.data.codex.appserver.CodexRuntimeManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
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
    single<CodexAppServerConnectionCreator> {
        WorkspaceCodexAppServerConnectionFactory(get(), get(), BuildConfig.VERSION_NAME)
    }
    single { CodexAppServerSessionRecovery(get(), get(), get()) }
    single { CodexAppServerConversationSessionOpener(get(), get(), get(), get()) }

    single { ConversationRepository(get(), get(), get(), get(), get(), get(), get()) }
    single { FolderRepository(get(), get()) }
    single { MemoryRepository(get()) }
    single { GenMediaRepository(get()) }
    single { FilesRepository(get()) }
    single { FavoriteRepository(get()) }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
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
