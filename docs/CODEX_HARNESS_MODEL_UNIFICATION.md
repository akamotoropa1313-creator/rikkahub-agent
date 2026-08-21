# Codex Harness model unification

## Goal

Treat Codex App Server as an **execution harness**, not as a separate model/provider UI.
The user should select models through the existing RikkaHub model picker and independently
choose whether the conversation executes through the normal provider path or the Codex harness.

The unified picker must preserve RikkaHub's existing interaction model: provider grouping,
search, favorites, model capability tags, and the same bottom-sheet presentation.

## User-facing model sources

### ChatGPT account

Models returned by Codex App Server `model/list` for the currently authenticated ChatGPT account.
These are dynamic and must not be hard-coded from the ChatGPT product UI. Only models actually
reported by the running App Server are selectable.

### Existing RikkaHub providers

Every existing enabled RikkaHub chat model remains visible exactly where it is today. The selected
RikkaHub `Model.id` and provider identity remain the source of truth; provider/API-key settings are
not duplicated into assistant records.

## Separation of concerns

Model selection and execution mode are orthogonal:

- `STANDARD`: current RikkaHub provider execution path.
- `CODEX`: Codex App Server owns the agent loop, tools, shell/file actions, approvals, skills, MCP,
  thread/turn lifecycle, and streaming events.

Do not expose "Codex Provider" and "Codex App Server" as two competing model concepts.
Codex-specific controls should contain only harness-specific settings (account, approvals, sandbox,
reasoning behavior, service tier, diagnostics, runtime state, MCP/skills/review). Model selection
belongs in the normal model picker.

## Durable selection model

Do not overload a dynamic App Server model into a synthetic persistent `ProviderSetting` entry.
That would corrupt provider identity, favorites, and restart behavior.

Persist an explicit model target instead:

```text
CodexModelTarget
  ChatGptAccount(model: String?)
  RikkaHubProvider(modelId: Uuid)
```

`ChatGptAccount(null)` means the App Server default model. RikkaHub-provider targets retain only the
existing model UUID; provider identity is resolved from current Settings at execution time so edits
to endpoint/API-key/provider configuration are automatically honored.

Migration rule: existing assistants with `codexModel` set migrate to `ChatGptAccount(codexModel)`;
existing assistants with null `codexModel` migrate to `ChatGptAccount(null)`. Existing non-Codex
assistants are unchanged.

## Unified picker presentation

The current `ModelSelector -> ModelListSheet` visual hierarchy remains authoritative.

The sheet data source becomes a list of UI groups rather than assuming every group is a persisted
`ProviderSetting`:

```text
PickerGroup
  PersistedProvider(provider, models)
  ChatGptAccount(modelsFromCodexCatalog)
```

Both groups use the same card, icon, search, selected-state, capability tag, and sticky-header
components. Long-press provider navigation is available only for persisted providers. ChatGPT
models must not create fake provider-settings pages.

Favorites should be extended with a typed key rather than forcing ChatGPT model strings into the
existing UUID-only `favoriteModels` list. Existing UUID favorites remain valid.

## Provider routing through Codex

Codex App Server supports separate `model` and `modelProvider` fields. RikkaHub should derive a
`CodexResolvedModelRoute` immediately before thread start/resume/turn start.

### ChatGPT account route

```text
model         = selected App Server model (or null for default)
modelProvider = null / Codex default OpenAI account provider
config        = no RikkaHub provider credentials
```

Authentication remains entirely in Codex account lifecycle APIs.

### Direct Responses-compatible route

For `ProviderSetting.OpenAI` entries that explicitly use the Responses API and whose endpoint is
compatible with Codex Responses semantics, generate a per-thread custom model-provider definition.
Do not write API keys to assistant persistence or logs. Prefer an app-private environment/token
handoff or local bridge even for these providers when practical.

### RikkaHub bridge route

All other providers (Anthropic Messages, Gemini native API, Chat-Completions-only endpoints, local
providers, or provider-specific adapters) go through an app-local RikkaHub Responses bridge:

```text
Codex App Server
  -> http://127.0.0.1:<ephemeral>/v1/responses
  -> RikkaHub CodexModelBridge
  -> existing ProviderManager / provider implementation
```

The bridge is a transport adapter only. The Codex harness remains responsible for the agent loop.
Provider credentials stay in RikkaHub's existing Settings and are never copied into Codex config.

The bridge must bind loopback only, use a per-process unguessable bearer token, reject non-local
clients, and stop with the owning app/runtime lifecycle.

## Settings compatibility

RikkaHub assistant settings must not be forwarded blindly to Codex.

| RikkaHub setting | Codex harness behavior |
| --- | --- |
| `workspaceId` / conversation CWD | Required; mapped to Codex cwd/workspace |
| system prompt | mapped to developer instructions using existing conversation override rules |
| reasoning level | map only when the selected Codex/model route supports a meaningful effort value |
| `temperature` | provider-path only unless the selected Codex provider explicitly supports it |
| `topP` | provider-path only unless explicitly supported; never silently inject unsupported config |
| `maxTokens` | provider-path only unless a verified Codex/provider setting exists |
| custom headers/body | remain provider-owned; bridge applies them through existing provider implementation |
| local tools | Codex harness owns shell/file/agent tools; do not duplicate equivalent tool definitions |
| MCP | keep Codex MCP lifecycle when executing through Codex; avoid registering the same MCP tool twice |
| web search | expose only the execution-path implementation that is actually active |
| sandbox/approval | Codex-native settings are authoritative in CODEX mode |
| service tier/personality/reasoning summary | Codex-native, capability-gated by `model/list` metadata |

When a setting has no Codex equivalent, the UI should explain that it applies only to normal
provider mode rather than pretending it is active.

## Runtime invariants

1. Switching execution mode must not change the selected ordinary RikkaHub model.
2. Switching between ChatGPT-account and RikkaHub-provider targets must require a new Codex thread
   or an explicit reset when the current binding cannot safely change provider/model identity.
3. Never send a persisted unsupported service tier/personality override until the current process
   has confirmed the capability from `model/list`.
4. Provider/API-key edits in Settings take effect on the next bridge/direct route resolution.
5. No provider secret appears in conversation DB rows, assistant serialization, UI logs, or Codex
   thread metadata.
6. Standard (non-Codex) chat behavior and existing assistant serialization remain backward compatible.

## Implementation slices

1. Add typed durable model target and migration compatibility for the current `codexModel` field.
2. Generalize the existing model sheet to render persisted-provider and ChatGPT-account groups with
   one visual implementation.
3. Remove the duplicate Codex model picker from `CodexControlSheet`; keep account/runtime and
   harness-specific controls there.
4. Add `CodexModelRouteResolver` and central settings normalization; use it for start, resume, and
   turn overrides so all paths agree.
5. Implement direct Responses-compatible routing.
6. Implement the loopback RikkaHub Responses bridge for all remaining existing providers.
7. Add unit/protocol tests, UI state tests, migration tests, and real-device smoke coverage.

## Acceptance criteria

- A user can enable Codex execution and choose a ChatGPT-plan model from the same model picker used
  everywhere else.
- A user can choose any existing enabled RikkaHub chat model from that same picker.
- Provider grouping/search/favorites/selection appearance matches the current RikkaHub picker.
- ChatGPT entries come from `model/list`, not hard-coded product assumptions.
- Existing provider credentials/configuration are reused without duplicate setup.
- Unsupported RikkaHub settings are not silently forwarded to Codex.
- Anthropic/Google/Chat-Completions-only providers work through the loopback bridge.
- Normal provider mode remains unchanged.
- Existing assistants migrate without losing their current Codex model preference.
- CI covers route resolution and bridge protocol behavior; physical-device smoke validates first-run,
  model switching, approval, Stop, background/foreground, and session recovery.
