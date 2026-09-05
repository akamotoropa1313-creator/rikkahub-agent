# Contributing

Keep changes easy to review, test, and remove. This repository contains a large Android app and several supporting tools, so unrelated cleanup and product changes should not share a pull request.

## Branches and pull requests

- Use one branch for one active pull request.
- Delete the branch after its pull request is merged or closed.
- Use the manual APK workflows for test builds; do not keep temporary build-only branches.
- Stacked pull requests are allowed only when the child explicitly targets the parent branch. Merge them from the bottom of the stack upward.
- Keep a pull request as a draft while required device validation is still pending.
- Prefer a small number of descriptive commits. Squash exploratory or mechanical checkpoint commits before review when doing so will not disrupt an active stack.

## Before requesting review

Run the smallest relevant checks locally, then let GitHub Actions run the full gate.

```bash
# Compile the Android app and generate Room schemas
./gradlew :app:compileDebugKotlin

# Run the Codex integration tests
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.codex.appserver.*Test'

# Run workspace unit tests
./gradlew :workspace:testDebugUnitTest
```

Use the following workflows when an installable artifact is required:

- **Build diagnostic debug APK** for debugging and broad device compatibility.
- **Build optimized benchmark APK** for release-like performance checks.
- **Codex App Server protocol tests** for Codex transport, account, session, and UI-policy regressions.

Do not commit APKs, signing keys, service-account files, generated build output, or local agent notes. The repository `.gitignore` contains the canonical exclusions.

## Documentation

Start at [docs/README.md](docs/README.md). Update the relevant current document when behavior changes. Keep dated audits as historical records and label them clearly instead of treating them as the current implementation plan.
