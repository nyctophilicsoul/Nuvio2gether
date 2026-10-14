# Nuvio2gether Integration Framework

## Purpose

This document is the integration contract for experimental work in this repository. It prevents premature APK builds, uncontrolled workflow growth, broad Android-TV imports, and accidental changes to the stable WatchParty or mobile experience.

## Hard rules

1. **No APK build until integration is complete.** Do not dispatch or add build workflows as a substitute for analysis. APK packaging is a final verification gate, not the development loop.
2. **Keep experiments isolated.** Work on `Test-Build` (or a dedicated feature branch created from it). Do not push experimental commits to `cmp-rewrite` or `merge-watchparty`.
3. **No bulk TV-fork copy.** Treat the Android TV implementation as a reference. Import only a small, reviewed behavior or abstraction when the mobile call sites, lifecycle, Compose UI, player engine, and platform dependencies are compatible.
4. **One coherent integration per commit.** Do not mix player synchronization, local-file playback, WatchParty, tracking providers, UI/layout changes, and workflow maintenance in one patch.
5. **No duplicate workflows.** Reuse the existing workflow only when the final build gate is reached. Do not create per-feature APK workflows.
6. **Never claim verification that did not run.** Distinguish source inspection, static checks, unit tests, device testing, and APK packaging in reports.

## Branch and repository boundaries

- `cmp-rewrite`: protected baseline / official-update integration line.
- `merge-watchparty`: preserve the WatchParty integration; do not use as an experimental scratch branch.
- `Test-Build`: isolated integration workspace for the feature set and compatibility work.
- `av-sync-test`: existing AV-sync experiment; inspect and selectively port reviewed changes rather than merging its whole branch.
- Backup branches are recovery references, not active integration targets.

The branch comparison against `cmp-rewrite` shows that `Test-Build` and `av-sync-test` have large, diverged histories and contain unrelated WatchParty, poster, iOS, local-file, and workflow changes. Therefore, **do not merge either branch wholesale**. Review file-level changes and port only approved, coherent changes.

## Integration pipeline (mandatory order)

### Gate 0 — Freeze and inventory

- Record current branch and HEAD SHA before editing.
- Compare the target branch against the baseline and inspect changed files.
- Group changes by feature, dependency, platform, and risk.
- Identify duplicate implementations, workflow proliferation, generated files, unrelated UI changes, and files that should not be imported.
- Do not edit or build until the inventory and dependency map are understood.

### Gate 1 — Define contracts

For each feature, document:
- user-visible behavior and non-goals;
- source of truth and state ownership;
- lifecycle/cleanup behavior;
- public/internal interfaces and platform-specific code;
- interaction with ExoPlayer, libmpv, WatchParty, local URIs, and playback speed;
- tests and explicit acceptance criteria.

Prefer narrow interfaces over cross-feature conditionals. Keep common UI and business logic in `commonMain`; keep Android-only APIs in `androidMain`; add iOS implementations only when the feature requires them.

### Gate 2 — Implement in dependency order

1. **Playback state contract:** one authoritative playback snapshot for position, playing/buffering state, duration, and effective speed. Speed changes must propagate immediately to the active engine and any WatchParty session.
2. **AV-sync correction:** diagnose before changing behavior. Preserve the user's selected 1.0x/1.5x/2.0x speed; drift correction must not silently reset speed. Separate expected seek discontinuities from actual clock drift. Bound correction and avoid feedback loops. Treat ExoPlayer renderer/sink changes as experimental until Media3 API compatibility and fallback behavior are verified.
3. **Local-file playback:** use Android's document picker and persisted URI permission where required; pass a content URI through a typed playback request; do not assume a filesystem path or HTTP URL. Verify audio/video/subtitle tracks and lifecycle when the picker is cancelled or the URI becomes unavailable.
4. **WatchParty:** adapt the existing player through a narrow interface; propagate play, pause, seek, and speed consistently. Do not share local/torrent/loopback sources unless the transport can genuinely make them accessible to peers. Keep synchronization separate from optional chat.
5. **Tracking and other features:** audit Simkl, Trakt, MDBList and any other missing integrations independently after the playback foundation is stable.
6. **UI/layout:** confirm legacy and current player layouts expose the same intended actions without leaking controls across layouts. Avoid importing TV-only navigation, focus handling, dimensions, or assumptions into mobile.

### Gate 3 — Validate incrementally without APK packaging

Run the cheapest relevant checks first, then expand:
- inspect the diff and ensure changes are limited to the intended feature;
- Kotlin formatting/static analysis where configured;
- compile/test the affected source sets and run focused unit/host tests;
- add regression tests for speed changes, seeks, drift thresholds, player switching, lifecycle disposal, and local content URI handling;
- verify common code has no Android-only dependencies and Android-only code has no accidental iOS impact;
- inspect workflow changes and ensure no new build workflow was introduced unnecessarily.

If a check cannot run in the available environment, mark it **not run**; do not infer success from code inspection.

### Gate 4 — Integration review

Before considering the feature set integrated:
- resolve duplicate or conflicting implementations;
- verify each feature against its acceptance criteria;
- review the complete diff against the baseline, not only the last commit;
- confirm stable branches remain untouched;
- confirm no secrets, local properties, keystores, generated APKs, or unrelated assets were committed;
- summarize known risks and unverified behavior.

### Gate 5 — Final build and device test

Only after Gates 0–4 pass:
- use the repository's existing Android build workflow; do not add another one;
- build once from the reviewed integration HEAD;
- inspect the build result and artifact path;
- install on a real Android mobile device and test both player engines where applicable;
- test 1.0x, 1.5x and 2.0x, seek forward/backward, pause/resume, audio output route changes, WatchParty host/guest behavior, and local-file playback;
- report pass/fail evidence and any limitations before merging.

## Feature acceptance criteria

### AV sync / playback speed
- The chosen playback speed remains unchanged when drift correction runs.
- Host speed changes propagate promptly to guests; late joiners receive the current speed.
- Seeking is not misclassified as drift.
- Correction is bounded, observable in diagnostics, and does not create oscillation or repeated seeks.
- Unsupported formats/routes use a safe fallback rather than breaking playback.
- ExoPlayer and libmpv behavior is tested separately; one engine's result is not assumed to validate the other.

### Local files
- The user can select a file through the Android picker and launch it in Nuvio's existing player.
- Content URIs are handled without converting them to guessed filesystem paths.
- Cancellation, revoked permission, unsupported format, and missing duration fail gracefully.
- This remains an Android implementation unless cross-platform requirements are explicitly added.

### WatchParty
- Play/pause/seek/speed commands update both local playback and remote session state.
- Session attachment and detachment follow player lifecycle; stale sessions do not survive disposal.
- Unsupported or non-shareable sources are clearly rejected rather than advertised as shareable.
- Existing mobile layouts remain usable and do not inherit TV-only UI assumptions.

## Current review findings

- `av-sync-test` contains dedicated playback-speed-aware renderer/sink code and changes to `PlayerEngine.android.kt`; this needs API/lifecycle review and targeted tests before it is considered integrated.
- The branch also contains WatchParty, local-file, poster, settings, iOS, and workflow changes. These are separate review units, not one safe-to-merge bundle.
- Existing debug-build workflow files are present on experimental history. Do not trigger them while the framework and integration are incomplete.
- No APK build is authorized by this framework until the final gate is explicitly reached.

## Required status format

For every work session, report only:
- **Branch / HEAD**
- **Scope changed**
- **Checks run** (with actual outcomes)
- **Checks not run**
- **Blockers / risks**
- **Next integration step**

Never report “fixed”, “integrated”, or “ready to test” unless the corresponding acceptance criteria and checks support that statement.
