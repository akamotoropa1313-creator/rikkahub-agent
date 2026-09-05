# Protected alpha baseline audit — 2026-09-05

This is a release-specific reconciliation record. The historical Stage25/26 audit and PR bodies are evidence, not proof that the current head passed its gate.

## Work capability evidence

The connected GitHub tool read both repositories, commit/branch/tag refs, PRs, reviews/comments, issues, Actions jobs/logs/artifacts. It successfully created checkpoint and RC branches, Git blobs/trees/commits, fast-forwarded only the new RC branch, and created Draft PR #37. The normal shell Git HTTPS path timed out, and no Git credential helper or `gh` executable was configured locally. Code editing and local Git commits work. Git objects hydrated from API metadata were hash-verified; code trees uploaded through the GitHub Git Data API were verified against local tree IDs. The browser Web editor was not used.

Actions were started by normal PR events. There is no exposed general workflow-dispatch or release-create tool; no secret listing/value endpoint was used. Permanent signing-key availability is unverified, and no signing identity was generated. The local Java 17/Gradle 9.5 runtime exists, but the required JetBrains Java 21 daemon download failed under network restrictions; no functional Android SDK is available locally. Local Android compilation did not reach source compilation. GitHub-hosted CI is the full build/test route.

## Repository and stack

| Ref | Exact commit | Treatment |
|---|---|---|
| Fork master | `74610cad7e1a93d546e11c5fef47c19378fe8b52` | Not the development baseline; one documentation/scaffolding commit beyond shared `fa89f366` |
| PR #32 | `e56fab919bec025515adf634e7f640af90139320` | Separate 22-commit localization branch; retain open |
| PR #33 | `eea83ffcfe52c36ffa3b8e0dcf5636a2359e2365` | Runtime provisioning; ancestor of #34 and #36; retain Draft |
| PR #34 | `3c228e408a48c989a0a40c5f36b59a238e348c35` | Based on #33; gateway/unified picker; retain Draft |
| PR #36 / release source | `9d8ecd643aafca3f27e218ddfeecd67a90930884` | Most complete development state; includes latest dynamic Assistant tools, not only the features listed in its older body |
| Checkpoint | `checkpoint/pre-release-20260905-9d8ecd64` | Exact source preserved remotely |
| RC / PR #37 | `release/2.4.10-akaro.1-alpha.1` | Based on #36, PR base explicitly #36; no history rewrite |
| Upstream stable/master | `0a463acc3e16c979678d073169d94a6a962146f4` | `v2.4.16-agent.0`; no post-release master commits at inspection |
| Development/upstream merge base | `e8f10b70cc915a7c879b0c078051e557236a8c2c` | 2.4.10 integration already present |

PR #36 vs stable: fork 401 ahead, upstream 101 ahead. Master vs PR #36: PR #36 217 ahead, master 1 ahead, base `fa89f366602338159d93a83f31061dccb51af8f0`. PR #33 → #34 is 94 commits, #34 → #36 is 91 commits, with no reverse-only commits in either comparison. Master vs upstream uses the older merge base `48b75c3e96ee2ede8a2b06b5304e1d425d2ab588`; the task's 245/161 observation must not be used for the development stack.

The previous local `30ed301a` and GitHub `9d8ecd64` have the identical tree `189f4c3c9afd9cc39e4110b2ef6ebc06c9c79772`. Five local commits were recreated by earlier GitHub API writes under different commit IDs; no additional unpushed feature exists in that tree.

### PR #32 is not fully contained

Comparison reports #36 217 ahead and #32 22 ahead from `fa89f366`. Its net seven files were inspected. #33 independently localized the UI in Japanese, and #36 subsequently replaced the separate approval cards with native timeline tool rows. Reapplying #32 wholesale would restore obsolete approval/account/model UI. The unique locale-switching helper `CodexUiLocale.kt` and English-preservation behavior are **not** present in #36; command/patch status labels are only partly localized there. Therefore #32 is neither merged nor declared fully superseded. It stays open for a focused localization port against current composables. This RC keeps the current #36 Japanese UI intact; it does not falsely claim complete bilingual coverage.

### Documentation reconciliation

`README.md`, chat-generation pipeline, context compaction reference, runtime guide, harness-unification document, Stage26 audit, 2026-08-31 handoff and all three original workflows were read. `CONTRIBUTING.md` and `docs/README.md` existed only on fork master; their current policy plus PR template and corrected clone instructions were carried over. Master's 16 example-test deletions were not imported. No test was removed to make a gate pass. The upstream contribution policy briefly added in `2dc50126` is absent from the chosen upstream target and does not replace this fork's rules.

## Protected data flow and invariants

| Component / flow | Protected behavior and evidence |
|---|---|
| ChatService → ConversationSession → Codex runtime | One conversation owns one runtime/lease; send admission, turn/capability serialization, navigation cleanup and stale binding remain unchanged. Codex turns take `sendCodexTurn`; native chat takes `handleMessageComplete`. The gateway calls ProviderManager, not a second GenerationHandler agent loop. |
| RuntimeManager → WorkspaceCodexAppServerLauncher | Keep pinned 0.146.0, architecture/hash verification, atomic install, explicit executable path, immediate provisioning, cancellation and validation. Launcher still uses `startInteractiveProcess`, retaining stdin for JSON-RPC. |
| WorkspaceManager / ProotShellRunner | Keep managed runtime and `/skills` bind mounts, Android active-network DNS preparation, exported trust roots, CODEX_CA_CERTIFICATE/SSL_CERT_FILE, PRoot execution compatibility and cleanup registry. Upstream whole-file replacement loses fork hooks. Only `Process.readResult` EOF handling is ported. |
| RikkaHubCodexAppServerBridge → credential source | Provider repository owns login and encrypted storage; access tokens travel only through private stdio external-token mode. Refresh selects the same source account, enforces the prior ChatGPT identity and has a 9-second deadline; generic errors exclude credentials. No alternate browser login is introduced. |
| Harness route resolver → loopback gateway | Keep deterministic provider/model identities, stale-route refusal, per-conversation opaque token issue/revoke, loopback-only listener, current provider validation, authoritative model override and forbidden custom auth headers. Raw Responses bypass remains distinct from translated native providers. |
| Model list / unified picker / effort | Dynamic account-scoped model catalog and invalidation, active-turn lock, configured-provider routes, model-reported effort choices/order/default and account-preserving model switches remain unchanged. |
| Skills / Android tools / MCP | Keep multiple typed Skill inputs, stable path de-duplication, `/skills` extraRoots on every connection, Assistant-owned enablement, sanitized MCP names, schema fingerprint and execution identity checks, Android-side Termux dispatch and interactive ask_user. New deletion tombstones remove only the chosen deleted Skill from Assistant selections. |
| Runtime events → timeline → publisher → Room | Keep StringBuilder accumulation, 100 ms delta coalescing, terminal flush, 250 ms activity cadence with stage-priority transitions, one assistant message per Codex turn and saved reasoning/tool/duration/usage metadata. UIMessage tool visibility change affects UI/export callers only. |
| Approval / cancellation | Native Shell/file approval rows, Workspace-specific approval settings, hardline floor, request ID/thread/turn validation and per-turn execution budget remain unchanged. Shell EOF applies only to non-interactive commands; App Server cancellation retains process ownership. |
| Persistence / restore / statistics | Keep Room 32 auto-migration, nullable dynamic tool fingerprint, conversation/history restore, stale-route handling, harness usage aggregation and native non-Codex providers. No upstream Room/backup/startup schema downgrade or destructive migration is imported. |

The core source files listed above remain byte-identical to #36 except the narrow shared tool/schema/visibility/Skill settings ports in the ledger. Tests, not unchanged filenames alone, determine the automated gate.

## Upstream reconciliation

See [the 101-commit ledger](UPSTREAM_RECONCILIATION.md) and its machine-readable JSON. Primary categories: SAFE 2, ADAPT 14, HIGH RISK 35, SKIP 48, UPSTREAM BUG / REGRESSION 2. Eight commits are adopted (one partial); 93 are deliberately deferred, including intermediate/merge commits. This is selective synchronization, not a full stable merge.

Adopt order: Termux `1ea13e5b`; EOF portion of `b62d29d1`; ZIP `9cf98e7f` plus local bounds; Skill deletion `1eb873ab` plus `dda4a552`; empty schema `e8293d35`; tool visibility `321443d8`; Luau `0a463acc`. Fork schema repair precedes these. The local Git history retains this ordering; the remote review tree may combine the ports in one descriptive commit.

### Compatibility patches and removal conditions

1. **Workspace EOF adaptation** — keep only readResult/test changes from `b62d29d1`. Its PRoot non-interactive environment additions share a builder used by App Server. Remove the adaptation only when upstream exposes separate interactive/non-interactive policies and all DNS/CA/runtime/process tests plus Redmi smoke pass.
2. **ZIP safety patch** — upstream `9cf98e7f` calls `ZipInputStream.closeEntry()` in a preliminary charset scan, which inflates entries outside the extraction budget. Skill import also uses unbounded `input.copyTo` before guarded extraction and leaves the temp file on copy failure. The RC caps charset probing at 8 MiB/1000 entries, caps compressed Skill input at 24 MiB, retains 20 MiB expanded/200-entry limits, closes the source and removes failed spool files. Large later GBK names may fall back to an explicit extraction error rather than an unbounded probe. Remove only after upstream has equivalent bounds, cleanup and passing malicious/input-failure tests. Related upstream issue #66 describes archive names; no new public issue was filed.
3. **Deleted bundled Skills** — initial `1eb873ab` alone is a known startup regression because Settings.dummy contains an empty deletion set. Always pair it with `dda4a552` (`first { !it.init }`). Do not drop that condition until settings initialization is authoritative before seeding.

### Known upstream limitations not imported as fixes

Stable release notes acknowledge OpenAI-compatible image-returning tools causing HTTP 400 and an optional animated gradient's heavy redraw cost. The former also appears in older release notes; it is a known baseline compatibility limitation, not caused by these ports. The alpha does not claim to fix it. Broad Gemini decoder, global proxy/OAuth, backup restore, terminal manager, compaction merge and retry changes are deferred with explicit contract maps in the ledger. Later title/suggestion-setting removal is not imported, so existing choices remain intact. Sub-agent concurrency is not raised from 16 to 30 on this phone.

## Baseline failures versus new changes

Current source #36 run `33371989165` compiled and linted successfully, but failed on missing `app/schemas/.../32.json`; JVM tests were skipped. Its successful benchmark run is `33371989160`. Older PR-body head `382652e7` is not evidence for current source.

The exact generated schema was downloaded from artifact `9750580957` and ZIP SHA-256 `e4ddbd50389ace4fa74bef5ea0f8c40bf3d48c1a1834df4a1736ce301cf9b48f` verified. It changes only the binding table by adding nullable TEXT `dynamic_tools_fingerprint`. Existing AutoMigration(31,32) remains. Schema 32 identity hash is `57fd03989a7edf54e67dc6b18c9b5b20`.

After this schema-only repair, head `5a7cf62793de573ac35ffcdea2cb6569418bdf57` passed the full protocol workflow `33965154534` (compile, schema cleanliness, all JVM tests, lint). This isolates the existing failure. Port head `3c04372d19116ba4aa0f5faf68844c140dc9a808` passed benchmark `33965478014` but protocol `33965477966` failed during app test compilation: the newly added archive probe test lacked the ByteArrayOutputStream import. This RC corrects the import; it also prevents diagnostic logging from masking extraction IO failures on the host JVM. No assertion is weakened. Subsequent candidate runs must pass independently; final exact-head results belong in the handoff and PR, not inferred from this historical checkpoint.

## Release status and physical gate

Release package stays `excp.rikkahub`; versionCode 184 exceeds source 177 and inspected upstream 183. VersionName/tag is `2.4.10-akaro.1-alpha.1` / `v2.4.10-akaro.1-alpha.1`; keep the 2.4.10 base visible until a complete later upstream baseline is adopted. Future fork releases increment a global Android versionCode, even when upstream version names change.

Permanent signing identity has not been safely established in this Work session: **SIGNING BLOCKER**. Local properties/keystore were not present; GitHub secret values were neither requested nor inspected. No key was generated. Existing benchmark artifacts use `excp.rikkahub.benchmark` and a runner debug certificate, so they cannot establish continuity for `excp.rikkahub`.

On Redmi Note 13 Pro+ / Android (record the actual OS/build) validate the exact signed arm64 APK: existing package/certificate/version update, data preservation through Room 31→32, fresh provisioning without a message, provider login/external token refresh, model/list/model/effort switching, multiple Skills and restart deletion/reinstall, Shell/file/Termux/MCP/ask_user, manual/automatic/denied approvals, long text/reasoning/tool streams, terminal flush, cancellation, app restart/reconnect/stale binding, statistics, normal native-provider chat and attachments. All remain **NEEDS DEVICE VALIDATION** until actual results exist.

No tag, signed release APK or GitHub prerelease is established by this audit. Draft #37 is the review boundary; use the handoff for remaining release steps.
