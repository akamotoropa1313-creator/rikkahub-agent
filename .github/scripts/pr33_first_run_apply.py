from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1))


# ChatService: prepare a conversation-owned runtime/thread before the first turn.
path = "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt"
marker = '''    private suspend fun sendCodexTurn(
'''
prepare = '''    suspend fun prepareCodexSession(conversationId: Uuid) {
        val owner = getOrCreateSession(conversationId)
        if (owner.codexRuntime != null) return
        check(owner.tryBeginCodexOperation()) { "Another Codex operation is already running" }
        try {
            codexOpenMutexes.getOrPut(conversationId) { Mutex() }.withLock {
                if (owner.codexRuntime != null) return@withLock
                ensureHydrated(conversationId)
                val conversation = owner.state.value
                val assistant = settingsStore.settingsFlow.first().let {
                    it.getAssistantById(conversation.assistantId) ?: it.getCurrentAssistant()
                }
                check(assistant.codexAppServerEnabled) { "Codex App Server is disabled" }
                validateCodexPreflight(conversationId, conversation, assistant, emptyList())
                val workspaceId = checkNotNull(assistant.workspaceId).toString()
                val cwd = conversation.workspaceCwd.orEmpty()
                val instructions = buildString {
                    append(assistant.systemPrompt)
                    if (assistant.allowConversationSystemPrompt && !conversation.customSystemPrompt.isNullOrBlank()) {
                        append("\\n\\n--- Conversation instructions ---\\n").append(conversation.customSystemPrompt)
                    }
                }.ifBlank { null }
                val personality = assistant.codexPersonality?.let { CodexAppServerPersonality.valueOf(it.name) }
                owner.publishCodexState(CodexConversationUiState.Opening)
                val opened = checkNotNull(codexSessionOpener) { "Codex App Server is unavailable" }.open(
                    conversationId.toString(),
                    workspaceId,
                    cwd,
                    CodexAppServerThreadStartParams(
                        model = assistant.codexModel,
                        serviceTier = assistant.codexServiceTier,
                        developerInstructions = instructions,
                        personality = personality,
                        sandbox = CodexAppServerSandboxMode.fromPreference(assistant.codexSandboxMode),
                        approvalPolicy = CodexAppServerApprovalPolicy.fromPreference(assistant.codexApprovalPolicy),
                    ),
                )
                when (opened) {
                    is CodexAppServerConversationSessionOpenResult.Started -> installCodexRuntime(conversationId, owner, opened.session)
                    is CodexAppServerConversationSessionOpenResult.Recovered -> installCodexRuntime(conversationId, owner, opened.session)
                    is CodexAppServerConversationSessionOpenResult.StaleBinding -> {
                        owner.publishCodexState(CodexConversationUiState.StaleBinding(opened.reason.toString()))
                        error("The existing Codex binding is stale; reset is required")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (owner.codexState.value is CodexConversationUiState.Opening) {
                owner.publishCodexState(CodexConversationUiState.Disconnected)
            }
            throw cancelled
        } catch (failure: Throwable) {
            if (owner.codexState.value is CodexConversationUiState.Opening) {
                owner.publishCodexState(CodexConversationUiState.Failed(failure.message ?: "Codex setup failed"))
            }
            throw failure
        } finally {
            owner.endCodexOperation()
        }
    }

'''
replace_once(path, marker, prepare + marker)

# ChatVM: own/cancel setup job, persist toggle before preparing, auto-prepare restored enabled chats.
path = "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt"
replace_once(path, "import kotlinx.coroutines.Job\n", "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.CoroutineStart\n")
replace_once(
    path,
    '''    val codexOperationBusy = chatService.getCodexOperationBusyFlow(_conversationId)
''',
    '''    val codexOperationBusy = chatService.getCodexOperationBusyFlow(_conversationId)
    private val _codexPrepareJob = MutableStateFlow<Job?>(null)
    val codexPrepareJob: StateFlow<Job?> = _codexPrepareJob
''',
)
insert_after = '''    fun reconnectCodexSession() { viewModelScope.launch { runCatching { chatService.reconnectCodexSession(_conversationId) } } }
'''
new_methods = '''    private fun launchCodexPreparation() {
        if (_codexPrepareJob.value?.isActive == true) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            runCatching { chatService.prepareCodexSession(_conversationId) }
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
'''
replace_once(path, insert_after, insert_after + new_methods)
replace_once(
    path,
    '''        viewModelScope.launch { codexEnabled.collect { if (!it) _selectedCodexSkill.value = null } }
''',
    '''        viewModelScope.launch {
            codexEnabled.collect { enabled ->
                if (enabled) launchCodexPreparation()
                else {
                    cancelCodexSetup()
                    _selectedCodexSkill.value = null
                }
            }
        }
''',
)

# FilesPicker: distinguish coding agent, show provisioning progress and explicit cancel/retry.
path = "app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt"
replace_once(
    path,
    "import me.rerere.rikkahub.data.db.entity.WorkspaceEntity\n",
    "import me.rerere.rikkahub.data.db.entity.WorkspaceEntity\nimport me.rerere.rikkahub.data.codex.appserver.CodexRuntimeManager\nimport me.rerere.rikkahub.data.codex.appserver.CodexRuntimePhase\nimport me.rerere.rikkahub.data.codex.appserver.CodexRuntimeProgress\nimport kotlinx.coroutines.flow.MutableStateFlow\n",
)
replace_once(
    path,
    '''    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
''',
    '''    onUpdateAssistant: (Assistant) -> Unit,
    onCodexAppServerEnabledChange: (Boolean) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
''',
)
replace_once(
    path,
    '''    codexOperationBusy: Boolean,
    onOpenCodexControls: () -> Unit,
''',
    '''    codexOperationBusy: Boolean,
    codexPrepareJobActive: Boolean,
    onCancelCodexSetup: () -> Unit,
    onRetryCodexSetup: () -> Unit,
    onOpenCodexControls: () -> Unit,
''',
)
replace_once(
    path,
    '''    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
''',
    '''    val workspaceRepository: WorkspaceRepository = koinInject()
    val runtimeManager: CodexRuntimeManager = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
''',
)
old_block = '''        val selectedWorkspace = assistant.workspaceId?.let { id -> workspaces.firstOrNull { it.id == id.toString() } }
        val codexPrerequisite = when {
            assistant.workspaceId == null -> "Select a workspace before enabling Codex App Server"
            selectedWorkspace == null -> "The selected workspace is unavailable"
            selectedWorkspace.shellStatus != WorkspaceShellStatus.READY.name -> "The workspace shell must be READY"
            else -> "Uses an App Server-managed thread; the normal provider model is not used"
        }
        ListItem(
            headlineContent = { Text("Codex App Server") },
            supportingContent = { Text(codexPrerequisite) },
            trailingContent = {
                Switch(
                    checked = assistant.codexAppServerEnabled,
                    enabled = assistant.codexAppServerEnabled || (selectedWorkspace?.shellStatus == WorkspaceShellStatus.READY.name),
                    onCheckedChange = { enabled ->
                        onUpdateAssistant(assistant.copy(codexAppServerEnabled = enabled))
                    },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )

        if (assistant.codexAppServerEnabled) {
            ListItem(
                headlineContent = { Text("Codex controls") },
                supportingContent = {
                    Column {
                        Text("Connection, Account, Codex Skills, Codex MCP, and safety")
                        codexSafetyIndicator(assistant)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { onOpenCodexControls() },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
'''
new_block = '''        val selectedWorkspace = assistant.workspaceId?.let { id -> workspaces.firstOrNull { it.id == id.toString() } }
        val fallbackRuntimeFlow = remember { MutableStateFlow(CodexRuntimeProgress()) }
        val runtimeFlow = remember(selectedWorkspace?.root, runtimeManager) {
            selectedWorkspace?.root?.let(runtimeManager::state) ?: fallbackRuntimeFlow
        }
        val runtimeProgress by runtimeFlow.collectAsState()
        val codexPrerequisite = when {
            assistant.workspaceId == null -> "Workspaceを選択するとCodex Coding Agentを有効にできます"
            selectedWorkspace == null -> "選択したWorkspaceを利用できません"
            selectedWorkspace.shellStatus != WorkspaceShellStatus.READY.name -> "WorkspaceのLinux環境をREADYにしてください"
            else -> "Workspace内でCodex App Server Harnessを使用します。設定 > Providers > Codexとは別機能です。"
        }
        ListItem(
            headlineContent = { Text("Codex Coding Agent (App Server)") },
            supportingContent = { Text(codexPrerequisite) },
            trailingContent = {
                Switch(
                    checked = assistant.codexAppServerEnabled,
                    enabled = assistant.codexAppServerEnabled || (selectedWorkspace?.shellStatus == WorkspaceShellStatus.READY.name),
                    onCheckedChange = onCodexAppServerEnabledChange,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )

        if (assistant.codexAppServerEnabled) {
            val activeSetup = runtimeProgress.phase in setOf(
                CodexRuntimePhase.CHECKING_WORKSPACE,
                CodexRuntimePhase.CHECKING_PLATFORM,
                CodexRuntimePhase.CHECKING_RUNTIME,
                CodexRuntimePhase.DOWNLOADING,
                CodexRuntimePhase.VERIFYING,
                CodexRuntimePhase.INSTALLING,
                CodexRuntimePhase.VALIDATING,
            ) || codexPrepareJobActive
            ListItem(
                headlineContent = { Text("Codex環境") },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(runtimeProgress.detail ?: if (runtimeProgress.phase == CodexRuntimePhase.READY) "準備完了" else "接続準備中")
                        if (runtimeProgress.phase == CodexRuntimePhase.DOWNLOADING && runtimeProgress.bytesRead > 0) {
                            val total = runtimeProgress.totalBytes
                            Text(if (total != null) "${runtimeProgress.bytesRead / 1_048_576} / ${total / 1_048_576} MiB" else "${runtimeProgress.bytesRead / 1_048_576} MiB")
                        }
                        runtimeProgress.version?.let { Text("検証済みRuntime: Codex $it${runtimeProgress.architecture?.let { arch -> " · $arch" }.orEmpty()}") }
                        runtimeProgress.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                trailingContent = {
                    when {
                        activeSetup -> TextButton(onClick = onCancelCodexSetup) { Text("キャンセル") }
                        runtimeProgress.phase == CodexRuntimePhase.FAILED -> TextButton(onClick = onRetryCodexSetup) { Text("再試行") }
                        else -> Unit
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
            ListItem(
                headlineContent = { Text("Codex App Server設定") },
                supportingContent = {
                    Column {
                        Text("接続、App Serverアカウント、モデル、Skills、MCP、安全性を設定します")
                        Text("通常のCodex Providerのログイン状態とは独立しています", style = MaterialTheme.typography.bodySmall)
                        codexSafetyIndicator(assistant)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { onOpenCodexControls() },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
'''
replace_once(path, old_block, new_block)

# ChatPage wires setup state/actions into FilesPicker and attempts prepare before opening controls.
path = "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"
replace_once(
    path,
    '''    val codexOperationBusy by vm.codexOperationBusy.collectAsStateWithLifecycle()
''',
    '''    val codexOperationBusy by vm.codexOperationBusy.collectAsStateWithLifecycle()
    val codexPrepareJob by vm.codexPrepareJob.collectAsStateWithLifecycle()
''',
)
replace_once(
    path,
    '''                onOpenCodexControls = { showFilesSheet = false; showCodexControls = true },
                codexOperationBusy = codexOperationBusy,
''',
    '''                onOpenCodexControls = { vm.retryCodexSetup(); showFilesSheet = false; showCodexControls = true },
                codexOperationBusy = codexOperationBusy,
                codexPrepareJobActive = codexPrepareJob?.isActive == true,
''',
)
replace_once(
    path,
    '''    onOpenCodexControls: () -> Unit,
    codexOperationBusy: Boolean,
) {
''',
    '''    onOpenCodexControls: () -> Unit,
    codexOperationBusy: Boolean,
    codexPrepareJobActive: Boolean,
) {
''',
)
replace_once(
    path,
    '''            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            hasCodexBinding = hasCodexBinding,
''',
    '''            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) it else assistant
                        }
                    )
                )
            },
            onCodexAppServerEnabledChange = { enabled -> vm.setCodexAppServerEnabled(assistant, enabled) },
            hasCodexBinding = hasCodexBinding,
''',
)
replace_once(
    path,
    '''            codexOperationBusy = codexOperationBusy,
            onOpenCodexControls = onOpenCodexControls,
''',
    '''            codexOperationBusy = codexOperationBusy,
            codexPrepareJobActive = codexPrepareJobActive,
            onCancelCodexSetup = vm::cancelCodexSetup,
            onRetryCodexSetup = vm::retryCodexSetup,
            onOpenCodexControls = onOpenCodexControls,
''',
)

# Account projection: authoritative snapshots clear obsolete temporary statuses; cancellation does not linger.
path = "app/src/main/java/me/rerere/rikkahub/service/CodexChatRuntime.kt"
replace_once(
    path,
    '''            _capabilities.value = _capabilities.value.copy(account = session.accountApi.readAccount(refreshToken))
''',
    '''            val account = session.accountApi.readAccount(refreshToken)
            _capabilities.value = _capabilities.value.copy(
                account = account,
                accountStatus = null,
                accountError = null,
            )
''',
)
replace_once(path, '                    accountStatus = "Sign-in canceled",\n', '                    accountStatus = null,\n')
replace_once(
    path,
    '''        accountStatus = if (event.success) "Sign-in completed" else event.error ?: "Sign-in failed",
        accountError = event.error,
''',
    '''        accountStatus = if (event.success) null else event.error ?: "Sign-in failed",
        accountError = event.error,
''',
)
replace_once(
    path,
    '''        _capabilities.value = _capabilities.value.copy(account = session.accountApi.readAccount(), pendingLoginId = null, accountStatus = "Signed out")
''',
    '''        _capabilities.value = _capabilities.value.copy(
            account = session.accountApi.readAccount(),
            pendingLoginId = null,
            accountStatus = null,
            accountError = null,
        )
''',
)

# ChatList: surface structured quota/auth/network failures instead of generic "Codex failed".
path = "app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt"
replace_once(
    path,
    "import me.rerere.rikkahub.ui.components.codex.CodexTurnDiffCard\n",
    "import me.rerere.rikkahub.ui.components.codex.CodexTurnDiffCard\nimport me.rerere.rikkahub.ui.components.codex.codexTurnErrorPresentation\n",
)
replace_once(
    path,
    '''            Text("Codex activity", style = MaterialTheme.typography.titleSmall)
            CodexActivityContent(phaseActivity)
''',
    '''            Text("Codex activity", style = MaterialTheme.typography.titleSmall)
            CodexActivityContent(phaseActivity)
            if (state is CodexConversationUiState.Terminal) {
                codexTurnErrorPresentation(state.diagnostics)?.let { presentation ->
                    Text(presentation.title, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                    presentation.detail?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
''',
)
replace_once(
    path,
    '''        is CodexConversationUiState.Terminal -> "Codex ${state.status.wireValue}"
''',
    '''        is CodexConversationUiState.Terminal -> codexTurnErrorPresentation(state.diagnostics)?.let { presentation ->
            presentation.detail?.takeIf { it.isNotBlank() }?.let { "${presentation.title}: $it" } ?: presentation.title
        } ?: "Codex ${state.status.wireValue}"
''',
)

# Model compatibility regression fixtures: string/null/omitted + unknown metadata.
path = "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/CodexAppServerModelApiTest.kt"
marker = '''    @Test
    fun `visible pagination preserves server page and effort ordering`() = runBlocking<Unit> {
'''
test = '''    @Test
    fun `defaultServiceTier accepts string null and omitted while unknown metadata is preserved`() = runBlocking<Unit> {
        listOf<JsonElement?>(JsonPrimitive("default"), JsonNull, null).forEach { tier ->
            fixture().use { f ->
                val call = async { f.api.list() }
                var response = page(null)
                if (tier != null) response = response.replaceModelField("defaultServiceTier", tier)
                response = response.replaceModelField("futureModelMetadata", buildJsonObject { put("multiAgentVersion", 99) })
                f.respond(f.request(), response)
                val model = call.await().data.single()
                assertEquals((tier as? JsonPrimitive)?.takeIf { it.isString }?.content, model.defaultServiceTier)
                assertTrue(model.raw.containsKey("futureModelMetadata"))
            }
        }
    }

'''
replace_once(path, marker, test + marker)

# Account state pure regression checks piggy-back on existing integration test source.
path = "app/src/test/java/me/rerere/rikkahub/service/CodexChatRuntimeIntegrationTest.kt"
marker = '''    private fun assertActivity(activity: CodexConversationActivity) {
'''
test = '''    @Test
    fun `successful account completion does not leave contradictory temporary status`() {
        val state = CodexCapabilitiesUiState(pendingLoginId = "login-1", accountStatus = "Waiting for ChatGPT sign-in")
        val completed = applyAccountLoginCompletion(
            state,
            me.rerere.rikkahub.data.codex.appserver.CodexAppServerAccountEvent.LoginCompleted(
                loginId = "login-1",
                success = true,
                error = null,
                rawParams = kotlinx.serialization.json.buildJsonObject {},
            ),
        )
        assertEquals(null, completed.pendingLoginId)
        assertEquals(null, completed.accountStatus)
        assertEquals(null, completed.accountError)
    }

'''
replace_once(path, marker, test + marker)
