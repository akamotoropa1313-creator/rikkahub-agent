from pathlib import Path
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

try:
    runpy.run_path(".github/scripts/pr33_first_run_apply.py", run_name="__main__")
finally:
    # Restore the harmless disambiguation whitespace after the real patch runs.
    restored = files_picker.read_text().replace(alternate, needle)
    files_picker.write_text(restored)
