# Codex App Server runtime

RikkaHub Agent manages the Codex App Server runtime automatically. Normal use does not require a Linux terminal, Node.js, npm, or a manual Codex CLI installation.

## Normal first-run flow

1. Open a chat and select a ready Workspace.
2. Enable **Codex コーディングエージェント（App Server）**. The switch itself immediately starts managed runtime provisioning when the runtime is not ready; sending a chat message is not required to begin installation.
3. Wait for **Codex環境** to reach the ready state. RikkaHub performs Workspace/platform checks, downloads the pinned supported OpenAI Codex standalone runtime when necessary, verifies its SHA-256 digest, installs it atomically in app-managed storage, and starts `codex app-server` for the conversation.
4. Open **Codex App Serverの設定**.
5. If required, configure or refresh **設定 > プロバイダー > Codex**. The App Server account control synchronizes that same RikkaHub provider credential; it does not create an independent browser-owned login.
6. Load the model catalog and select a model, or keep the server default.
7. Send a normal chat message.

No dummy first message is required to start runtime installation or to make Account, Model, Skills, MCP, thread history, or configuration capabilities available once the App Server is ready.

## Runtime policy

- RikkaHub uses a pinned, validated official OpenAI standalone Codex release rather than automatically following `latest`.
- The download source is the official OpenAI Codex GitHub release asset for the detected supported Linux architecture.
- The downloaded archive is verified against the expected SHA-256 before installation.
- Installation uses a temporary file/directory and atomic promotion so an interrupted download cannot become the active runtime.
- The managed runtime is kept outside the user's `/workspace` project and is bind-mounted into PRoot Workspaces through the dedicated RikkaHub runtime path.
- The normal provisioning path does not run `apt`, `npm`, `node`, `dpkg`, `debconf`, `tzdata`, or locale/timezone configuration. Therefore Codex setup cannot block on package-manager prompts such as geographic-area or timezone selection.
- The Workspace's existing timezone and locale are not modified just to install Codex.
- The launcher uses the validated managed executable path rather than an arbitrary `codex` found on `PATH`.

## Runtime progress and recovery

The Codex environment UI reports the current phase, including Workspace/platform checks, download, verification, installation, validation, ready, and failure states. Download progress is shown when available.

Setup can be canceled and retried. A failed or incomplete managed runtime is not treated as ready; the next preparation attempt revalidates the runtime before launching the App Server.

Because the primary path is a standalone binary install, Debian package-manager recovery (`dpkg --configure -a`, debconf repair, timezone prompting, and similar operations) is not part of normal Codex provisioning.

## Account ownership

**設定 > プロバイダー > Codex** is the credential owner for both conventional Codex provider requests and the managed App Server harness. App Server account state still comes from `account/read`, while sign-in/refresh uses RikkaHub's provider bridge and the App Server external-token protocol.

After an authoritative `account/read` snapshot or a completed login transition, obsolete temporary UI text such as a previous cancellation or waiting message must not remain alongside the current account state.

## Assistant Skills, MCP, and Android tools

The conversation-owned RikkaHub Assistant supplies the model-facing session: its prompts, Codex options, every enabled Skill, selected local Android/Termux tools, and selected MCP servers/tools. Skills are not limited to the first enabled entry.

Local and MCP tools are registered through App Server `thread/start.dynamicTools` and executed in RikkaHub's Android tool layer. Thus an enabled `termux_run_command` does not require `termux_run_command`, Node, npm, OpenClaw, or ClawHub binaries inside `/workspace`. Interactive `ask_user` calls return the in-chat answer to App Server rather than executing the headless fallback.

Assistant/tool/Skill/prompt changes refresh the in-memory runtime before the next turn. Since pinned App Server 0.146 persists dynamic tools on `thread/start`, RikkaHub stores a tool fingerprint in Room and replaces a durable thread whose tool surface is stale. Provider-only sampling/body/header fields and UI-only settings are not fabricated as App Server options.

## Models and compatibility

The App Server model catalog accepts optional/nullable metadata according to the supported schema. In particular, `defaultServiceTier` may be a string, `null`, or omitted. Unknown future metadata is preserved/ignored as appropriate instead of invalidating the entire catalog.

RikkaHub pins a validated Codex runtime version so protocol changes in an arbitrary future `latest` release cannot silently replace a known-compatible runtime. Request serializers and their regression tests must follow the schema generated by that exact pinned binary; documentation for a later App Server version is not a wire-format substitute.

## Usage limits

A turn-level usage-limit failure is presented as a quota/usage-limit error rather than a generic connection failure. A quota error does not, by itself, invalidate the App Server process, so non-generation capabilities such as account, model, Skills, MCP, configuration, and thread-history operations can remain available.

## Advanced troubleshooting

Terminal commands are not part of the supported normal setup flow. They are only useful when diagnosing a Workspace manually.

Examples of diagnostic checks include:

```sh
uname -m
cat /etc/os-release
```

A manually installed `codex`, Node.js, or npm installation is not required for RikkaHub's managed App Server runtime and should not be used as the primary fix for a provisioning problem. Prefer the in-app retry/diagnostic information so the managed runtime remains version-pinned and integrity-checked.
