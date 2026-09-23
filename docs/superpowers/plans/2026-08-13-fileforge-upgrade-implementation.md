# FileForge Optimizer Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the complete approved FileForge v0.2.0 upgrade through three independently verifiable milestones and one final public release.

**Architecture:** First establish the pure/testable streaming and recovery transaction core. Then attach Android service lifetime and Material You screens. Finally add the optional source-built arm64 toolchain, dual artifacts, release controls, and public-repository gate.

**Tech Stack:** Kotlin/Android SDK 35, Material Components, SAF, foreground services, JUnit, Android NDK/Rust, Gradle/GitHub Actions, GitHub Releases.

## Global Constraints

- The approved design at `docs/superpowers/specs/2026-08-13-fileforge-streaming-restore-native-design.md` is authoritative.
- Execute milestones in the order below; later plans consume interfaces produced by earlier plans.
- Use test-driven development for every behavior change and commit after each task.
- Keep one implementation branch and one pull request for the complete requested upgrade.
- Do not make the repository public until implementation is merged, CI is green, and the full-history exposure gate passes.

---

### Milestone 1: Streaming and Recovery Core

**Plan:** `docs/superpowers/plans/2026-08-13-fileforge-core-implementation.md`

- [ ] Execute Tasks 1-6 in order.
- [ ] Pass the core plan verification gate.
- [ ] Record the 301 MiB streaming regression result and zero-write dry-run evidence in the PR notes.

### Milestone 2: Foreground Service and Material You UI

**Plan:** `docs/superpowers/plans/2026-08-13-fileforge-android-ui-implementation.md`

- [ ] Execute Tasks 1-6 in order.
- [ ] Pass the Android UI plan verification gate.
- [ ] Record notification, cancellation, activity recreation, theme, navigation, restore, and update-check evidence.

### Milestone 3: Native arm64, CI, and Publication

**Plan:** `docs/superpowers/plans/2026-08-13-fileforge-native-release-implementation.md`

- [ ] Execute Tasks 1-6 in order.
- [ ] Pass the native and publication verification gate.
- [ ] Open and merge the single implementation PR.
- [ ] Change `Zfkirke0109/FileForgeOptimizer` from private to public only after the final clean exposure scan.
- [ ] Publish `v0.2.0` with standard and native-arm64 APKs and SHA-256 files.

### Final Acceptance

- [ ] Recheck all ten acceptance criteria in the approved design against fresh evidence.
- [ ] Verify the public README, SECURITY policy, license notices, GitHub profile/issue links, and unauthenticated latest-release API.
- [ ] Install or hand off `FileForgeOptimizer-native-arm64.apk` for Samsung Galaxy S23 Ultra validation.
