package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import me.rerere.rikkahub.data.codex.appserver.CodexSkillMetadata
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerAuthUrlLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerCommandApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.CodexAppServerFileChangeApprovalDecision
import me.rerere.rikkahub.data.codex.appserver.CodexRuntimeResolver
import me.rerere.rikkahub.data.codex.appserver.JsonRpcId
import me.rerere.rikkahub.data.codex.appserver.codexHarnessModelChangeRequiresSessionReset
import me.rerere.rikkahub.data.codex.appserver.effectiveCodexHarnessModelTarget
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.CodexConversationUiState
import me.rerere.rikkahub.service.CodexReviewAction
import me.rerere.rikkahub.service.CodexReviewServiceOwner
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.workspace.WorkspaceShellStatus
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val codexRuntimeResolver: CodexRuntimeResolver,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false)

    val inputState = ChatInputState()

    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val codexState: StateFlow<CodexConversationUiState> = chatService.getCodexStateFlow(_conversationId)
    val codexCapabilities = chatService.getCodexCapabilitiesStateFlow(_conversationId)
    val codexReview = chatService.getCodexReviewStateFlow(_conversationId)
    val codexOperationBusy = chatService.getCodexOperationBusyFlow(_conversationId)
    private val _codexPrepareJob = MutableStateFlow<Job?>(null)
    val codexPrepareJob: StateFlow<Job?> = _codexPrepareJob
    private val _selectedCodexSkill = MutableStateFlow<CodexSkillMetadata?>(null)
    val selectedCodexSkill: StateFlow<CodexSkillMetadata?> = _selectedCodexSkill
    fun selectCodexSkill(skill: CodexSkillMetadata?) { _selectedCodexSkill.value = skill }
    val codexEnabled: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(conversation, settingsStore.settingsFlow) { conversation, settings ->
        (settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()).codexAppServerEnabled
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _hasCodexBinding = MutableStateFlow(false)
    val hasCodexBinding: StateFlow<Boolean> = _hasCodexBinding
    fun resetCodexSession() { _selectedCodexSkill.value = null; viewModelScope.launch {
        chatService.resetCodexSession(_conversationId)
        _hasCodexBinding.value = false
    } }
    fun reconnectCodexSession() { viewModelScope.launch { runCatching { chatService.reconnectCodexSession(_conversationId) } } }

    private suspend fun ensureCodexConversationPersistedBeforeSession() {
        if (conversationRepo.existsConversationById(_conversationId)) return
        val snapshot = conversation.value
        runCatching {
            conversationRepo.insertConversation(snapshot, updateSearchIndex = false)
        }.getOrElse { failure ->
            // A first Send may win the insert race. That is fine: the only invariant needed by
            // CodexAppServerConversationSessionOpener is that the conversation now exists.
            if (!conversationRepo.existsConversationById(_conversationId)) throw failure
        }
    }

    private suspend fun ensureCodexRuntimeReadyBeforeSession() {
        val persistedConversation = conversationRepo.getConversationById(_conversationId) ?: conversation.value
        val currentSettings = settingsStore.settingsFlow.first()
        val assistant = currentSettings.getAssistantById(persistedConversation.assistantId)
            ?: currentSettings.getCurrentAssistant()
        if (!assistant.codexAppServerEnabled) return
        val workspaceId = assistant.workspaceId?.toString() ?: return
        val workspace = workspaceRepository.getById(workspaceId) ?: return
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return
        codexRuntimeResolver.ensureReady(workspace.root)
    }

    private fun launchCodexPreparation() {
        if (_codexPrepareJob.value?.isActive == true) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                // A normal blank RikkaHub chat is intentionally not persisted until first Send,
                // while App Server persistent thread bindings require a durable conversation row.
                // Initialize first so preset messages/assistant identity are not lost, then create
                // that row only when Codex actually needs to open a persistent session.
                chatService.initializeConversation(_conversationId)
                ensureCodexRuntimeReadyBeforeSession()
                ensureCodexConversationPersistedBeforeSession()
                chatService.prepareCodexSession(_conversationId)
            }
            _hasCodexBinding.value = chatService.hasCodexBinding(_conversationId)
        }
        _codexPrepareJob.value = job
        job.invokeOnCompletion { if (_codexPrepareJob.value === job) _codexPrepareJob.value = null }
        job.start()
    }
    fun retryCodexSetup() = launchCodexPreparation()
    fun cancelCodexSetup() { _codexPrepareJob.value?.cancel() }
    fun setCodexAppServerEnabled(assistant: Assistant, enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) it.copy(codexAppServerEnabled = enabled) else it
                    },
                )
            }
            if (enabled) launchCodexPreparation() else {
                cancelCodexSetup()
                _selectedCodexSkill.value = null
            }
        }
    }
    fun interruptCodexTurn() { viewModelScope.launch { chatService.stopGeneration(_conversationId) } }
    fun refreshCodexSkills() { viewModelScope.launch { runCatching { chatService.refreshCodexSkills(_conversationId) } } }
    fun refreshCodexModels() { viewModelScope.launch { runCatching { chatService.refreshCodexModels(_conversationId) } } }
    fun loadCodexThreadHistory(searchTerm: String? = null, loadMore: Boolean = false) { viewModelScope.launch { runCatching { chatService.loadCodexThreadHistory(_conversationId, searchTerm, loadMore) } } }
    fun readCodexHistoryThread(threadId: String) { viewModelScope.launch { runCatching { chatService.readCodexHistoryThread(_conversationId, threadId) } } }
    fun closeCodexHistoryThread() = chatService.closeCodexHistoryThread(_conversationId)
    fun refreshCodexConfigDiagnostics() { viewModelScope.launch { runCatching { chatService.refreshCodexConfigDiagnostics(_conversationId) } } }
    fun startCodexReview(action: CodexReviewAction) {
        when (action) {
            is CodexReviewAction.Start -> CodexReviewServiceOwner.start(chatService, _conversationId, action.target)
            CodexReviewAction.Stop -> CodexReviewServiceOwner.stop(chatService, _conversationId)
        }
    }

    /** Apply only a Codex preference delta against the newest Assistant inside SettingsStore.update. */
    fun updateCodexPreferences(transform: (Assistant) -> Assistant) {
        viewModelScope.launch { updateCodexPreferencesNow(transform) }
    }

    /**
     * A persisted Codex thread cannot safely change between account and gateway model routes.
     * Release that thread first, then persist the new target so the next send opens the right one.
     */
    fun updateCodexModelSelection(transform: (Assistant) -> Assistant) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.first()
            val activeConversation = conversation.value
            val currentAssistant = currentSettings.getAssistantById(activeConversation.assistantId)
                ?: currentSettings.getCurrentAssistant()
            val requestedAssistant = transform(currentAssistant)
            val requiresReset = codexHarnessModelChangeRequiresSessionReset(
                currentAssistant.effectiveCodexHarnessModelTarget(),
                requestedAssistant.effectiveCodexHarnessModelTarget(),
            )
            if (requiresReset && chatService.hasCodexBinding(_conversationId)) {
                chatService.resetCodexSession(_conversationId)
                _selectedCodexSkill.value = null
                _hasCodexBinding.value = false
            }
            updateCodexPreferencesNow(transform)
        }
    }

    private suspend fun updateCodexPreferencesNow(transform: (Assistant) -> Assistant) {
        settingsStore.update { settings ->
            val activeConversation = conversation.value
            val latestAssistant = settings.getAssistantById(activeConversation.assistantId)
                ?: settings.getCurrentAssistant()
            val updated = transform(latestAssistant)
            check(updated.id == latestAssistant.id) {
                "Codex preference update cannot replace assistant identity"
            }
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == latestAssistant.id) updated else assistant
                },
            )
        }
    }

    fun refreshCodexAccount() { viewModelScope.launch { runCatching { chatService.refreshCodexAccount(_conversationId) } } }
    fun refreshCodexMcp() { viewModelScope.launch { runCatching { chatService.refreshCodexMcp(_conversationId) } } }
    fun reloadCodexMcp() { viewModelScope.launch { runCatching { chatService.reloadCodexMcp(_conversationId) } } }
    private val codexAuthLauncher = CodexAppServerAuthUrlLauncher { url -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun setCodexSkillEnabled(skill: CodexSkillMetadata, enabled: Boolean) { viewModelScope.launch { runCatching { chatService.setCodexSkillEnabled(_conversationId, skill, enabled) } } }
    fun beginCodexAccountLogin() { viewModelScope.launch { runCatching { chatService.beginCodexAccountLogin(_conversationId, codexAuthLauncher) } } }
    fun cancelCodexAccountLogin() { viewModelScope.launch { runCatching { chatService.cancelCodexAccountLogin(_conversationId) } } }
    fun logoutCodexAccount() { viewModelScope.launch { runCatching { chatService.logoutCodexAccount(_conversationId) } } }
    fun beginCodexMcpOAuth(name: String) { viewModelScope.launch { runCatching { chatService.beginCodexMcpOAuth(_conversationId, name, codexAuthLauncher) } } }
    fun respondCodexCommandApproval(id: JsonRpcId, decision: CodexAppServerCommandApprovalDecision) {
        viewModelScope.launch { chatService.respondCodexCommandApproval(_conversationId, id, decision) }
    }
    fun respondCodexFileApproval(id: JsonRpcId, decision: CodexAppServerFileChangeApprovalDecision) {
        viewModelScope.launch { chatService.respondCodexFileApproval(_conversationId, id, decision) }
    }

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        chatService.addConversationReference(_conversationId)

        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
            _hasCodexBinding.value = chatService.hasCodexBinding(_conversationId)
        }
        viewModelScope.launch {
            codexEnabled.collect { enabled ->
                if (enabled) launchCodexPreparation()
                else {
                    cancelCodexSetup()
                    _selectedCodexSkill.value = null
                }
            }
        }
        viewModelScope.launch {
            codexState.collect { state ->
                if (state is CodexConversationUiState.Ready || state is CodexConversationUiState.Running ||
                    state is CodexConversationUiState.WaitingForApproval || state is CodexConversationUiState.Terminal
                ) _hasCodexBinding.value = chatService.hasCodexBinding(_conversationId)
            }
        }

        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        chatService.removeConversationReference(_conversationId)
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    val mcpManager = chatService.mcpManager

    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    val updateState = settingsStore.settingsFlow
        .map { settings ->
            !settings.init &&
                settings.displaySetting.updateCheckDisabledUntilEpochMillis <= System.currentTimeMillis()
        }
        .distinctUntilChanged()
        .flatMapLatest { enabled ->
            if (enabled) updateChecker.checkUpdate() else flowOf(UiState.Loading)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            UiState.Loading,
        )

    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        val skill = _selectedCodexSkill.value
        if (content.isEmptyInputMessage() && skill == null) return
        if (skill != null && answer) {
            val prompt = content.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            chatService.sendCodexSkillMessage(_conversationId, skill, prompt) { _selectedCodexSkill.value = null }
        } else chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return chatService.compressConversationAsync(
            conversationId = _conversationId,
            conversation = conversation.value,
            additionalPrompt = additionalPrompt,
            targetTokens = targetTokens,
            keepRecentMessages = keepRecentMessages,
        )
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException(context.getString(R.string.chat_stop_generation_before_delete)),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        scope: me.rerere.rikkahub.service.ChatService.ApprovalScope =
            me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
        toolName: String? = null,
    ) {
        chatService.handleToolApproval(
            conversationId = _conversationId,
            toolCallId = toolCallId,
            approved = approved,
            reason = reason,
            scope = scope,
            toolName = toolName,
        )
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    suspend fun rerunTool(toolCallId: String): me.rerere.rikkahub.service.ChatService.RerunToolResult =
        chatService.rerunTool(_conversationId, toolCallId)

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            val updatedConversation = conversationFull.copy(
                assistantId = targetAssistantId,
                folderId = null,
            )
            me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList.clearChat(conversation.id)
            if (conversation.id == _conversationId) {
                chatService.saveConversation(_conversationId, updatedConversation)
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
