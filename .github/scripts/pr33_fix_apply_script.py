from pathlib import Path

p = Path('.github/scripts/pr33_first_run_apply.py')
text = p.read_text()
old = '''replace_once(
    path,
    ''' + "'''    onUpdateAssistant: (Assistant) -> Unit,\n    onUpdateConversation: (Conversation) -> Unit,\n'''" + ''',
    ''' + "'''    onUpdateAssistant: (Assistant) -> Unit,\n    onCodexAppServerEnabledChange: (Boolean) -> Unit,\n    onUpdateConversation: (Conversation) -> Unit,\n'''" + ''',
)
'''
new = '''replace_once(
    path,
    ''' + "'''internal fun FilesPicker(\n    conversation: Conversation,\n    assistant: Assistant,\n    state: ChatInputState,\n    mcpManager: McpManager,\n    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,\n    onUpdateAssistant: (Assistant) -> Unit,\n    onUpdateConversation: (Conversation) -> Unit,\n'''" + ''',
    ''' + "'''internal fun FilesPicker(\n    conversation: Conversation,\n    assistant: Assistant,\n    state: ChatInputState,\n    mcpManager: McpManager,\n    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,\n    onUpdateAssistant: (Assistant) -> Unit,\n    onCodexAppServerEnabledChange: (Boolean) -> Unit,\n    onUpdateConversation: (Conversation) -> Unit,\n'''" + ''',
)
'''
if text.count(old) != 1:
    raise SystemExit(f'expected one broad FilesPicker staging replacement, found {text.count(old)}')
p.write_text(text.replace(old, new, 1))
