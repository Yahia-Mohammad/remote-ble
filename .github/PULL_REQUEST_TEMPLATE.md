<!-- Thanks for contributing! Keep the PR focused; unrelated reformatting makes review harder. -->

## What & why

<!-- What does this change, and what problem does it solve? Link any related issue. -->

Closes #

## Checklist

- [ ] `./gradlew build` passes (all klibs compile + JVM tests)
- [ ] If the wire protocol changed: `:protocol:jvmTest` **and** the `agent-rs`
      interop tests pass, and the change degrades gracefully (negotiated
      capability rather than a protocol-version bump, where possible)
- [ ] Docs updated if behavior/API changed (`docs/`, KDoc)
- [ ] `CHANGELOG.md` updated under `[Unreleased]` for user-visible changes
- [ ] Style matches the surrounding code (`.editorconfig`); no unrelated churn

## Notes for reviewers

<!-- Anything worth calling out: trade-offs, follow-ups, areas you're unsure about. -->
