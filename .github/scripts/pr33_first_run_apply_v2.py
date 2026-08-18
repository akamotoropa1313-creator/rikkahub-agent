from pathlib import Path
import re
import runpy

files_picker = Path("app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt")
needle = "    onUpdateAssistant: (Assistant) -> Unit,\n    onUpdateConversation: (Conversation) -> Unit,\n"
alternate = "    onUpdateAssistant: (Assistant) -> Unit,\n\n    onUpdateConversation: (Conversation) -> Unit,\n"

text = files_picker.read_text()
count = text.count(needle)
if count != 3:
    raise SystemExit(f"FilesPicker.kt: expected three legacy parameter anchors, found {count}")

first = text.index(needle) + len(needle)
text = text[:first] + text[first:].replace(needle, alternate)
files_picker.write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    current = p.read_text()
    count = current.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:120]!r}")
    p.write_text(current.replace(old, new, 1))


def adapt_stdio_factory(path: str) -> None:
    p = Path(path)
    current = p.read_text()
    pattern = re.compile(r'WorkspaceCodexAppServerConnectionFactory\(([^,()\n]+),\s*"([^"]+)"\)')
    updated, count = pattern.subn(
        r'WorkspaceCodexAppServerConnectionFactory(\1, CodexRuntimeResolver { CodexRuntimeReady("codex", "test", "test", managed = false) }, "\2")',
        current,
    )
    if count == 0:
        raise SystemExit(f"{path}: no legacy WorkspaceCodexAppServerConnectionFactory constructor found")
    p.write_text(updated)


try:
    runpy.run_path(".github/scripts/pr33_first_run_apply.py", run_name="__main__")

    # The runtime-aware production factory intentionally has no PATH fallback. Controlled stdio
    # fixtures provide an explicit synthetic runtime so protocol tests remain hermetic and never
    # download or provision a real Codex binary.
    for test_path in (
        "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/CodexAppServerAccountStdioIntegrationTest.kt",
        "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/CodexAppServerApprovalStdioIntegrationTest.kt",
        "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/CodexAppServerSessionRecoveryStdioIntegrationTest.kt",
        "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/CodexAppServerStage12StdioIntegrationTest.kt",
        "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/CodexAppServerThreadApiStdioIntegrationTest.kt",
        "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/CodexAppServerTurnStreamingStdioIntegrationTest.kt",
        "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/WorkspaceCodexAppServerLauncherTest.kt",
    ):
        adapt_stdio_factory(test_path)

    replace_once(
        "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/CodexAppServerAccountStdioIntegrationTest.kt",
        "    private fun controlled():Fixture {",
        "    private suspend fun controlled():Fixture {",
    )
    replace_once(
        "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/CodexAppServerStage12StdioIntegrationTest.kt",
        "    private fun controlled():Fixture{",
        "    private suspend fun controlled():Fixture{",
    )

    launcher_test = "app/src/test/java/me/rerere/rikkahub/data/codex/appserver/WorkspaceCodexAppServerLauncherTest.kt"
    runtime_expr = 'CodexRuntimeReady("codex", "test", "test", managed = false)'
    replace_once(
        launcher_test,
        'WorkspaceCodexAppServerLauncher(manager).launch("workspace", "project")',
        f'WorkspaceCodexAppServerLauncher(manager).launch("workspace", "project", {runtime_expr})',
    )
    replace_once(
        launcher_test,
        'assertEquals("exec codex app-server --listen stdio://", runner.context.command)',
        'assertEquals("exec \'codex\' app-server --listen stdio://", runner.context.command)',
    )
    replace_once(
        launcher_test,
        'WorkspaceCodexAppServerLauncher(manager).launch("workspace")',
        f'WorkspaceCodexAppServerLauncher(manager).launch("workspace", runtime = {runtime_expr})',
    )

    # The account event schema gained an optional onboardingEntrypoint field. Keep the generated
    # state-projection regression fixture explicit so schema additions fail loudly rather than being
    # hidden behind defaults.
    runtime_test = Path("app/src/test/java/me/rerere/rikkahub/service/CodexChatRuntimeIntegrationTest.kt")
    generated = runtime_test.read_text()
    old = '''                loginId = "login-1",\n                success = true,\n                error = null,\n                rawParams = kotlinx.serialization.json.buildJsonObject {},\n'''
    new = '''                loginId = "login-1",\n                success = true,\n                error = null,\n                onboardingEntrypoint = null,\n                rawParams = kotlinx.serialization.json.buildJsonObject {},\n'''
    if generated.count(old) != 1:
        raise SystemExit("CodexChatRuntimeIntegrationTest.kt: generated LoginCompleted fixture anchor changed")
    runtime_test.write_text(generated.replace(old, new, 1))
finally:
    # Restore the harmless disambiguation whitespace after the real patch runs.
    restored = files_picker.read_text().replace(alternate, needle)
    files_picker.write_text(restored)
