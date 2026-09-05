# ASTRA_RELEASE_HANDOFF

This is the continuation procedure for the first alpha, not a claim of release completion. The final exact-head CI record is maintained in [PR #37](https://github.com/akamotoropa1313-creator/rikkahub-agent/pull/37). Read it and current GitHub refs before any write; do not replay already-applied commits.

## Immutable baseline and branch strategy

- Repository: `akamotoropa1313-creator/rikkahub-agent`.
- Protected development source: `9d8ecd643aafca3f27e218ddfeecd67a90930884`, PR #36 / `codex/app-quality-ui-audit`.
- Checkpoint: `checkpoint/pre-release-20260905-9d8ecd64`, exactly the source above.
- Existing RC: `release/2.4.10-akaro.1-alpha.1`, existing Draft PR #37 targets #36. Continue this branch; do not create duplicate RC/PR/checkpoint refs.
- Fixed upstream target: `v2.4.16-agent.0` = `0a463acc3e16c979678d073169d94a6a962146f4`.
- Development/upstream merge base: `e8f10b70cc915a7c879b0c078051e557236a8c2c`. Source is 401 ahead / 101 behind upstream by ancestry. Semantic ports do not reduce ancestry counts.
- Existing remote commits: `5a7cf62793de573ac35ffcdea2cb6569418bdf57` adds generated Room 32; `3c04372d19116ba4aa0f5faf68844c140dc9a808` applies selective ports/version/signing inputs. Later RC descendants contain the release workflows, audit, release-gate tests and archive-test correction. Obtain the final immutable SHA from PR #37, not an old local checkout.
- Preserve #33 → #34 → #36 history. Merge only after review/device gates, bottom upward using normal merge commits; do not squash or rebase an active stack. Ported master contribution docs are already present. #32 remains open: its locale helper/English preservation is unique; obsolete UI files must not replace current UI. See BASELINE_AUDIT.md.

With authenticated Git on a capable machine:

```bash
git clone https://github.com/akamotoropa1313-creator/rikkahub-agent.git
cd rikkahub-agent
git fetch origin release/2.4.10-akaro.1-alpha.1 checkpoint/pre-release-20260905-9d8ecd64
git switch --track origin/release/2.4.10-akaro.1-alpha.1
git remote add upstream https://github.com/ExTV/rikkahub-agent.git
git fetch upstream tag v2.4.16-agent.0
git rev-parse HEAD
git rev-parse v2.4.16-agent.0^{commit}
git merge-base --is-ancestor 9d8ecd643aafca3f27e218ddfeecd67a90930884 HEAD
git submodule update --init --recursive
```

Compare the printed HEAD with GitHub PR #37's current head. Stop on an unexpected divergence, without force-pushing or overwriting local work. No upstream merge or repeated cherry-pick is required for this alpha.

## Applied ordering, files and protected contracts

1. Room `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/32.json`; existing migration 31→32 only adds nullable `dynamic_tools_fingerprint` and is preserved.
2. `app/.../data/ai/tools/local/TermuxTool.kt` + tests from `1ea13e5b79ff7f344b769c5aa73beebbeb2c3d6f`: recognize actual completion rather than the populated startup ACK.
3. `workspace/src/main/java/me/rerere/workspace/WorkspaceShellRunner.kt` + tests from **only the EOF portion** of `b62d29d16124a4f9a3b2c5ac72178e2cdc58e9a6`: close stdin for non-interactive no-input commands; keep App Server interactive stdin and fork PRoot DNS/CA/runtime mounts.
4. `ArchiveTools`, `SkillZipImporter` + tests from `9cf98e7f9ae6783fa7a495cf42411d7f23a0724a`: GBK names, plus local 8 MiB charset-probe and 24 MiB compressed Skill-input bounds, cleanup and failure logging. Existing expanded/path/entry limits remain.
5. `app/.../data/files/SkillManager.kt`, `app/.../data/datastore/PreferencesStore.kt`, `app/.../ui/pages/extensions/skills/SkillsVM.kt` from `1eb873abb8c5e76f790c3f9349b68091ef857ae8` paired with `dda4a55214d4472d6df0923df353fd0b74022dbc`: persisted deletion tombstones, await initialized settings, explicit bundled reinstall. Preserve multiple-Skill selection and `/skills` propagation.
6. `ai/.../openai/ChatCompletionsAPI.kt` + test from `e8293d358592dc074b91b52341b4f7b19fc5b9a6`: absent function parameters serialize to an empty object schema. Raw Responses path unchanged.
7. `ai/src/main/java/me/rerere/ai/ui/Message.kt` + test from `321443d87b16dfddb379c06e748fdf11fadc2957`: tool-only rows remain visible. Streaming metadata/serialization and harness ownership unchanged.
8. Luau aliases in highlight language detection from `0a463acc3e16c979678d073169d94a6a962146f4`.
9. Alpha metadata/signing inputs, build/publish workflows and fail-closed verifier, repository policy/audit.

The complete per-commit decision, contract dependency, non-adoption reason and local patch removal condition are in [UPSTREAM_RECONCILIATION.md](UPSTREAM_RECONCILIATION.md) and JSON. Do not turn deferred HIGH RISK items into an automatic merge. Preserve per-conversation process/auth/gateway ownership, single agent loop, model authority, provider-owned token refresh, native providers, 100 ms delta publishing, 250 ms activity updates, terminal flush, cancellation, Room/history/usage, multiple Skills, Workspace approval and dynamic tool identity.

## Automated gate

The original source failed solely on an uncommitted generated Room schema before app JVM tests ran. Schema-only commit `5a7cf627` passed full protocol run `33965154534`; benchmark `33965154523` also passed. Port commit `3c04372d` passed benchmark `33965478014`, but protocol `33965477966` failed because the new archive test omitted an import. The corrected final head needs its own success; do not infer it from the earlier checkpoint. Final results and run IDs are in PR #37.

```bash
python3 -m unittest discover -s .github/scripts -p 'test_release_tools.py' -v
./gradlew :app:compileDebugKotlin
git status --porcelain -- app/schemas
./gradlew testDebugUnitTest --continue
./gradlew lintDebug --continue
```

The benchmark variant is injected by the existing **Build optimized benchmark APK** workflow; it is not a checked-in Gradle build type. Use that workflow on the exact candidate ref for the optimized APK.

Require an empty schema status, all JVM tests (including Codex App Server and Workspace), successful lint, and an optimized benchmark artifact at the final candidate head. The protocol workflow already runs these full JVM/lint gates on PR events. New Python verifier tests simulate tool output; they are tests of gate rejection logic, not evidence of an actually signed release APK. Do not weaken tests, ignore schema changes or substitute an old-head APK. New source changes require a fresh exact-head gate.

Local Work compilation was blocked before source compilation by the unavailable Java 21 daemon download/Android SDK. This is an environment limitation, not a source PASS. Hosted Actions provide actual Android compilation and tests. Emulator/live-account/proot/Termux integration and Room data migration on the phone remain separate device checks.

## Android identity and signing

- Package: `excp.rikkahub` (unchanged).
- Code: `184`; source was 177 and inspected upstream was 183. Future fork codes must increase globally (next at least 185), independent of the upstream display version.
- Name: `2.4.10-akaro.1-alpha.1`; proposed tag `v2.4.10-akaro.1-alpha.1`, always GitHub prerelease.
- The 2.4.10 name identifies the full upstream baseline; release notes explicitly list selective fixes through 2.4.16. Do not claim a complete 2.4.16 merge.
- No permanent release key was confirmed usable in Work. **SIGNING BLOCKER**. No key was generated, requested in chat or committed.
- Use the key that signed the existing installed `excp.rikkahub`, with its existing alias. Determine its certificate SHA-256 from a trusted existing APK or the installed APK with `apksigner verify --print-certs`. A certificate fingerprint is public metadata; passwords/private keys stay outside chat, logs and artifacts.
- Configure GitHub Actions Secrets through an existing secure channel: `RIKKA_RELEASE_KEYSTORE_BASE64`, `RIKKA_RELEASE_STORE_PASSWORD`, `RIKKA_RELEASE_KEY_ALIAS`, `RIKKA_RELEASE_KEY_PASSWORD`. Configure repository variables `RELEASE_CERT_SHA256` (trusted existing certificate, 64 hex) and `LAST_RELEASE_VERSION_CODE` (actual greatest released/installed code; first default 183 is only the inspected floor). If installed code is already >=184, reserve a larger code and rerun CI before building.
- Local release builds still accept `local.properties` keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. CI environment names in app/build.gradle.kts take precedence.
- If the old identity is lost or the installed app has a different signature, stop and obtain an explicit signing-identity decision. Do not promise in-place update or uninstall user data to conceal the mismatch.
- Benchmark (`excp.rikkahub.benchmark`, runner debug certificate) and diagnostic debug artifacts are not release assets and cannot establish release update continuity.

## Build, validate and publish the identical bytes

`build-signed-alpha.yml` builds three ABI variants, verifies app ID/name/code/non-debuggable manifest, checks the certificate and v2 signature with Android build tools, and emits APK SHA256SUMS plus provenance (source SHA/tree, ABI, size, certificate). Only the temporary signing file is written under RUNNER_TEMP and removed; it is never uploaded. No debug-signing fallback is permitted by the release gate. Signing uses `--no-configuration-cache` so passwords are not serialized into the Gradle configuration cache. The verifier also rejects tracked source modifications.

`publish-validated-alpha.yml` accepts only an exact successful signed-build workflow run in this repository, verifies device attestation for that run's arm64 hash, downloads and re-verifies the original APKs, and creates an alpha prerelease without rebuilding. Existing tags are refused; never move or replace them.

GitHub requires a workflow_dispatch workflow to exist on the default branch for dispatch. These workflows initially live on the stacked RC. After review/device prerequisites permit integration, merge the stack bottom upward by normal merges so the workflow definitions exist on master. Do not prematurely merge Draft PRs just to enable dispatch. Alternatively, a trusted coding environment can build the exact RC locally with the existing key, perform the same gates and upload those validated bytes after review; do not change the signing policy.

When workflows are registered and secure signing is available, dispatch **on the RC branch while it still points to the chosen full SHA**. EXPECTED_COMMIT and GITHUB_SHA must match; a dispatch from master targeting an unrelated SHA is rejected.

```bash
# Set rc_sha to the exact, fully tested SHA recorded in PR #37; never leave a short SHA.
rc_sha="REPLACE_WITH_VERIFIED_FULL_RC_SHA"
gh workflow run build-signed-alpha.yml --repo akamotoropa1313-creator/rikkahub-agent \
  --ref release/2.4.10-akaro.1-alpha.1 -f expected_commit="$rc_sha"
```

Record the successful build run ID, download its `signed-alpha-<SHA>` artifact and confirm SHA256SUMS. Install the **signed arm64 release APK** with update mode on the Redmi Note 13 Pro+ only after checking the existing package, certificate and version code. Do not uninstall the current app. Keep a user-approved data backup. Record OS/build, installed-before identity, after identity and APK hash.

Device success criteria (currently **NEEDS DEVICE VALIDATION**):

- Existing conversations/settings/Workspace survive update and Room 31→32/reopen; clean install/provision is checked separately.
- Native provider and Codex provider chats work; App Server has no duplicated agent loop or cross-conversation/account credentials.
- Provider-owned login, external-token startup, 401 refresh and unchanged account identity; model/list, model switching and reasoning effort.
- Multiple Skills, `/skills` registration, deleted bundled Skill stays deleted after restart, explicit reinstall works.
- Shell EOF/completion, Termux real result, file operations, MCP, dynamic Android tools and ask_user; approved/denied/cancelled tool flows.
- Long text/reasoning/command output streams, final delta flush, navigation/background/restart, reconnect/stale binding, cancellation without leaked process, restored usage/statistics.
- No token in UI/log/export, no fallback to the wrong provider, no debug or benchmark package mistaken for release.

After all checks on those bytes pass:

```bash
# build_run and device_hash must identify the actual signed build and tested arm64 bytes.
gh workflow run publish-validated-alpha.yml --repo akamotoropa1313-creator/rikkahub-agent \
  --ref release/2.4.10-akaro.1-alpha.1 -f expected_commit="$rc_sha" \
  -f build_run_id="$build_run" -f arm64_apk_sha256="$device_hash" -f device_validated=true
```

Verify the resulting tag targets the same source, Release is prerelease, all three release APK hashes/certificates match provenance, and no debug/benchmark/keystore assets were uploaded. Update LAST_RELEASE_VERSION_CODE to the actual published code. Preserve the signed APK and certificate metadata for future continuity. Never print private signing values.

## Remaining work and future priorities

1. Obtain safe access to the existing signing identity, complete signed build and Redmi validation, then review/integrate/publish. Until then no release PASS/tag is claimed.
2. Port PR #32's remaining locale-switching/status labels into current composables without restoring obsolete approval UI.
3. Adapt high-value Gemini stream contract, compaction merge and restore/proxy changes in separate bounded PRs, each with native + Codex regression coverage. Keep title/model settings migration explicit.
4. Add repeatable device scenarios and performance measurements for reconnect/401/cancellation/multiple Skills/large output. Only after a successful release consider runtime/dependency upgrades separately.
