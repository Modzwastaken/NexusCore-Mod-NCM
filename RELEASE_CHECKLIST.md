# NexusCore — Release Checklist

Derived from §17.2, §17.3, §18, and §22. Each release runs the subset relevant to its
milestone (§22): v0.1 at M5, v0.5 at M8, v1.0.0 at M11.

**A box is ticked only with evidence.** "Should work" is not a status (§1.3). Where a check
cannot be performed, it is recorded as NOT MET with the specific reason — never quietly
dropped.

---

## 0. Applies to every release

- [ ] `./gradlew clean build` succeeds from a **clean checkout** on a clean environment
- [ ] All tests pass; no critical test ignored, disabled, or weakened
- [ ] `stubMarkerCheck` passes — no `TODO`, `FIXME`, "coming soon", or
      `UnsupportedOperationException` in `src/main/java`
- [ ] `checkstyleMain` and `checkstyleTest` pass with zero violations
- [ ] NexusCore-introduced compiler warnings reviewed; none hidden without written
      justification
- [ ] Artifact is exactly `build/libs/NexusCore-<version>.jar`
- [ ] `NexusCore-<version>-sources.jar` and `NexusCore-<version>-javadoc.jar` produced
- [ ] `checksums.txt` with SHA-256 for every release artifact
- [ ] JAR contains the expected metadata, resources, and notices — **and no development
      secrets, absolute local paths, or test fixtures**
- [ ] Exactly one `META-INF/neoforge.mods.toml`; no legacy `mods.toml`
- [ ] Pinned versions in `gradle.properties` re-verified against
      `maven.neoforged.net`; ADR-0001 revision log updated
- [ ] Released from a **tagged commit**; Java, Gradle, NeoForge, OS, commit hash, and
      SHA-256 recorded
- [ ] `release-notes.md` written, including **honest known limitations**
- [ ] `CHANGELOG.md` updated
- [ ] `IMPLEMENTATION_STATUS.md` reflects reality, including everything `blocked` and
      `intentionally excluded`
- [ ] No unverified numeric performance claim appears anywhere in the repository
      (see the unverified-claims register in `IMPLEMENTATION_STATUS.md`)
- [ ] **Final acceptance test installs only the produced release JAR** and the required
      platform — never IDE output directories

## 1. Runtime verification

- [ ] Dedicated server reaches ready state using the **packaged JAR**
- [ ] Server shuts down cleanly
- [ ] No `ERROR` log caused by NexusCore during normal startup and use
- [ ] Dedicated server loads **zero** client classes
- [ ] Vanilla client joins a server-only install; everything works, no client-side error

## 2. From M1 — configuration and messages

- [ ] Defaults generated on first run
- [ ] A deliberately corrupted config produces a validation report naming file, key,
      invalid value, expected form, and chosen fallback — and does not crash the server
- [ ] `/nexus reload` revalidates and reports
- [ ] Safe mode starts with non-core modules disabled
- [ ] Every player-facing string resolves from the catalogue; none is a raw key

## 3. From M2 — data

- [ ] Every persistent schema carries an integer `schema_version`
- [ ] Migration fixtures load from every supported previous version with exact expected
      results
- [ ] Atomic write protocol verified (`.tmp` → `fsync` → `ATOMIC_MOVE`)
- [ ] Journal replay verified by a simulated crash mid-transaction
- [ ] Audit chain verification detects a tampered record
- [ ] Path traversal tests reject `../` and symlink escapes
- [ ] Shutdown flush bounded, and incomplete flushes recorded
- [ ] Restart persistence: data written, server restarted, data read back identical

## 4. From M3 — permissions

- [ ] Default-deny verified
- [ ] Group inheritance and cycle rejection tested
- [ ] Wildcard and deny precedence tested per ADR-0004
- [ ] Direct-subject-beats-group tested, **and documented in `docs/admin/permissions.md`
      and in explain output**
- [ ] Temporary expiry tested at the boundary
- [ ] Commands and (from M9) GUI use the **same** evaluator
- [ ] Bootstrap recovery path documented; recovery file grant is single-use
- [ ] Security tests attempt permission bypass and fail

## 5. From M4 — commands

- [ ] Canonical `/nexus` tree; alias collisions handled, and alias failure never prevents
      loading
- [ ] Permission checked on every mutation
- [ ] Bounds and rate limits enforced
- [ ] Confirmation required for destructive actions; a token for one action cannot
      authorise another
- [ ] Generated help matches the registry exactly
- [ ] No stack trace ever reaches a player

## 6. From M5 — v0.1 scope

- [ ] GameTests cover teleport safety, home/warp persistence, punishment enforcement,
      permission gating
- [ ] Packaged-JAR smoke test passes
- [ ] Restart persistence verified for homes, warps, permissions, punishments
- [ ] Non-operator player has no admin command access by default
- [ ] Operator bootstrap can grant native groups, then be disabled

## 7. From M6 — economy

- [ ] Fixed-point integer minor units; no `float` or `double` anywhere in currency code
- [ ] Atomic transfer: debit and credit in one transaction boundary
- [ ] Idempotency replay test: same key produces exactly one committed transaction
- [ ] Reversal creates a compensating entry; no old row is edited
- [ ] Shop purchase is all-or-nothing
- [ ] All six §10.2 invariants have passing **named** tests
- [ ] Restart persistence for balances and history

## 8. From M8 — automation, backups, diagnostics, benchmarks

- [ ] Backup manifest and checksums present
- [ ] Restore **dry run** validates manifest, checksum, and schema before applying
- [ ] Scheduler survives restart with correct missed-run behaviour
- [ ] Support bundle contains no secrets — verified by test
- [ ] Benchmark harness produces JSON; baseline committed; CI compares against it
- [ ] **§16 budget numbers are now measured**, and every document updated to say so

## 9. From M9 — networking and client

- [ ] Malformed and oversized payloads rejected safely, with audit evidence
- [ ] Per-player and per-payload rate limits enforced
- [ ] Protocol negotiation degrades features gracefully; never disconnects for version skew
- [ ] Stale-revision mutation triggers refresh, never overwrite of newer state
- [ ] Admin button appears only after **server** confirmation of `nexuscore.gui.dashboard`
- [ ] Client missing the mod: server stable, GUI reports or falls back appropriately

## 10. From M10 — GUI

- [ ] Every screen has a command equivalent
- [ ] No information conveyed by colour alone
- [ ] Keyboard focus order, Enter/Space activation, Escape/back, visible focus state
- [ ] Reduced motion respected; hover sounds off by default
- [ ] No unbounded list renders; pagination or virtualisation everywhere

## 11. From M11 — v1.0.0

- [ ] Public API surface stable and versioned; worked examples compile
- [ ] `docs/user`, `docs/admin`, `docs/api`, `docs/migration` complete
- [ ] Full §17.3 manual release matrix executed and recorded
- [ ] Security review completed
- [ ] `THIRD_PARTY_NOTICES.md` complete with full licence text for anything embedded
- [ ] Every box in §22 checked with evidence, **or removed by a written and signed release
      decision**

---

## Sign-off

| Field | Value |
|---|---|
| Version | |
| Tagged commit | |
| Build environment (OS, Java, Gradle) | |
| NeoForge / Minecraft | |
| Artifact SHA-256 | |
| Checked by | |
| Date | |
| Items NOT MET (with reasons) | |
