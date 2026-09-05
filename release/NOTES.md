# RikkaHub Agent 2.4.10-akaro.1-alpha.1

First development alpha of this fork. It preserves the PR #33 → #34 → #36 Codex App Server stack.
The base is RikkaHub Agent 2.4.10; selected fixes were reviewed against 2.4.16-agent.0. This is not a complete 2.4.16 synchronization.

- Keep managed Codex 0.146.0 provisioning, conversation-owned sessions, provider-owned ChatGPT authentication, external-token refresh, unified model/effort selection, multiple Skills, `/skills`, Android/Termux/MCP dynamic tools and native chat timeline/usage persistence.
- Fix missing committed Room schema 32 for the existing nullable dynamic-tool fingerprint migration.
- Wait for real Termux command completion, and close stdin for non-interactive Shell commands while retaining interactive App Server stdio.
- Preserve deleted bundled Skills across restart and wait for loaded settings before seeding.
- Decode legacy GBK ZIP names, with bounded charset probing, bounded Skill archive input and cleanup on failure.
- Normalize empty Chat Completions tool schemas; retain controls for tool-only chat messages; recognize Luau files/code blocks.

Android package: `excp.rikkahub`. Version code: `184`. Use the arm64 release APK on Redmi Note 13 Pro+.
The published `provenance.json` records the exact source, signer fingerprint, ABI and APK SHA-256 values.

An in-place update requires the same package and signing certificate and a larger version code.
Debug (`.debug`) and benchmark (`.benchmark`) packages are separate installations. A previous transient CI debug key cannot be used as a permanent release identity.

This alpha is published only after the full automated gate, release signature checks and exact-APK physical-device validation pass. The draft PR and preparation documents alone do not establish those passes.
