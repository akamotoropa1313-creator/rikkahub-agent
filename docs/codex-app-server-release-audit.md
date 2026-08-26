# Codex App Server integration — Stage25 release audit

Audit date: 2026-08-16

## Baseline

RikkaHub's managed executable is pinned to `rust-v0.146.0`; its generated protocol schema is authoritative for every request sent by the released app. `rust-v0.147.0` and current `main` are forward-compatibility review references only and must not replace pinned-runtime enum spellings without upgrading the executable and its verified asset digests together.

In particular, the pinned runtime expects kebab-case strings for `thread/start.sandbox`, `thread/resume.sandbox`, and approval overrides, while the tagged `turn/start.sandboxPolicy.type` value remains camelCase.

The integration deliberately remains on the stable surfaces used by Stages 1–24. Experimental APIs are not enabled merely because a newer schema exposes them.

## Transport and initialization invariants

- Production transport remains stdio JSONL: one JSON-RPC message per line.
- RikkaHub emits the Codex wire shape without a mandatory `jsonrpc` property.
- The decoder also accepts an explicit `"jsonrpc":"2.0"` for interoperability.
- `initialize` is single-flight per connection and is followed by exactly one `initialized` notification.
- The stable/current initialize response fields used by RikkaHub are `userAgent`, `codexHome`, `platformFamily`, and `platformOs`.
- Unknown future response fields are ignored rather than making a compatible server unusable.
- Required stable fields retain strict type validation.

## Stable App Server surfaces used by the integration

The integrated control and conversation paths use stable App Server methods where available, including the established Stage1–24 surfaces for thread/turn lifecycle, approvals, account, model catalog, skills, MCP status/auth, configuration diagnostics, native inline review, token usage, and persisted thread history.

Stage23/24 history remains read-only and uses `thread/list` plus `thread/read`; it does not resume, fork, archive, rebind, or mutate the selected persisted thread.

## Forward-compatibility policy

RikkaHub follows these rules at the protocol boundary:

1. Unknown JSON object fields are tolerated unless a field is part of a known stable value that requires strict validation.
2. Open-string or future enum values are preserved/represented as unknown where the relevant DTO is intentionally forward compatible.
3. Unknown notifications and malformed unsolicited inbound messages are diagnostics; they do not terminate an otherwise healthy transport.
4. Unknown or late response ids are observable diagnostics and do not corrupt other in-flight requests.
5. A JSON-RPC error response is request-local. It fails the correlated caller but does not close the dispatcher or conversation connection.
6. `-32601 Method not found` therefore remains a local unsupported-capability result.
7. `-32001 Server overloaded; retry later.` remains a request-local, retryable server response. Stage25 does not add hidden automatic retry loops, which could duplicate non-idempotent operations; callers may retry only through an operation-specific policy.
8. Only terminal transport/read/write failure closes the dispatcher and fails all pending requests.

## Lifecycle and ownership invariants

Stage25 does not change the established ownership model:

- one conversation owns one Codex App Server runtime/session;
- no provider fallback is introduced;
- capability RPCs reuse the conversation-owned connection;
- capability operations and active turns continue to share the existing admission/lease rules;
- durable conversation/thread binding is not rewritten by read-only diagnostics or history browsing;
- navigation does not create a second App Server process;
- normal transport/coroutine cancellation must propagate rather than be converted into protocol success.

## Explicitly excluded from this release gate

The audit does not opt RikkaHub into experimental App Server features such as paginated thread history modes, thread settings mutation, permission profiles, memory APIs, thread sections, thread fork/archive/rollback controls, direct steering, or experimental transports. Those require separate product and protocol stages if adopted later.

## Stage25 release regressions

`CodexAppServerReleaseHardeningTest` locks the following release-level compatibility guarantees:

- a `-32601` response fails only its request and a subsequent request succeeds on the same dispatcher;
- a `-32001` overload response fails only its request and a subsequent request succeeds on the same dispatcher;
- an unknown future notification and malformed unsolicited line remain diagnostic-only, after which the dispatcher is still usable;
- an explicit JSON-RPC 2.0 response plus future envelope/result fields remains compatible.

These tests complement the existing connection, dispatcher, protocol, session, recovery, runtime, approval, capability, review, token-usage, configuration, model, multimodal, and thread-history regression suites.

## Release gate

Stage25 is ready to merge only when the exact PR head passes all existing GitHub Actions gates without weakening filters or ignoring failures:

- Android production Kotlin compilation / Room schema generation;
- committed Room schema cleanliness;
- Codex App Server protocol, UI-policy, service/runtime and Stage25 release tests;
- workspace interactive-process tests;
- PR review has no unresolved blocking findings;
- base-to-head audit contains only intentional Stage25 changes.

No Room database version or schema change is required by Stage25.
