# NexusCore — Release Checklist

Derived from §17.2, §17.3, §18, and §22. Each release runs the subset relevant to the
milestones it contains.

> The `v0.1` / `v0.5` / `v1.0-at-M11` ladder this document was originally written against was
> retired by [ADR-0007](docs/architecture/ADR-0007.md). Releases are now `1.0.0` → `1.5.0` →
> `2.0.0`, and the milestone sections below are keyed to the milestone, not to a version label
> that no longer exists.

**A box is ticked only with evidence.** "Should work" is not a status (§1.3). Where a check
cannot be performed, it is recorded as NOT MET with the specific reason — never quietly
dropped.

---

## Which checklist applies

[ADR-0012](docs/architecture/ADR-0012.md) numbers `x.y.0` as a **version** and `x.y.1`–`x.y.5`
as its **builds**. The version number alone says which ceremony applies.

### Build (`x.y.1`–`x.y.5`) — the short gate

Every build, hotfix or pre-release. Nothing here is optional; a build that fails one of these is
not cut, and the number is not spent.

- [ ] `mod_version` bumped in `neoforge/gradle.properties` **before** the work starts
- [ ] `./gradlew clean build` succeeds in **all three** loader projects — `neoforge/`,
      `fabric/`, `forge/`. A shared-source change compiles under three toolchains or it is not
      finished (ADR-0008).
- [ ] All tests pass on all three; no test ignored, disabled, or weakened
- [ ] `checkstyleMain` and `checkstyleTest` clean on all three
- [ ] `stubMarkerCheck` and `versionLadderCheck` pass
- [ ] Shared `CHANGELOG.md` entry written, heading labelled `— hotfix` or `— pre-release`
      (`versionLadderCheck` enforces this, but write it because it is true, not because a task
      demands it)
- [ ] The third component is 5 or less — a sixth build means the minor should have moved up
- [ ] Loader changelog entries written where the change was loader-specific
- [ ] `IMPLEMENTATION_STATUS.md` updated: the build's row reflects what actually happened, and
      any status that changed is re-evidenced
- [ ] The build's **exit condition**, as written in that table, is met — or the row says it is
      not, and why

### Handed to someone — the short gate, plus

Any build another person is asked to install, whichever number it carries.

- [ ] Packaged jar installed and started on all three test servers — `neoforgeserver/`,
      `fabricserver/`, `forgeserver/` — not IDE output
- [ ] All three jars copied to `archived/v<version>/`, **never rebuilt afterwards**
- [ ] `checksums.txt` generated from the archived jars themselves
- [ ] `archived/README.md` row added
- [ ] **Somebody was actually asked to install it.** An archived build nobody was given is
      ceremony with no content; if that is what happened, record it rather than archiving
      silently.

### Version (`x.y.0`) — everything below

`1.1.0`, `1.5.0`, `2.0.0`. The whole document, plus the sign-off table at the end.

### Milestones say when work landed; content says what to check

The milestone headings below record where a section came from, not when it applies.
A section applies if the release **contains** the thing it protects, whatever
milestone that thing was planned under. §7 is the worked example: a release that
moves value between players runs it in full, even if the economy milestone is
untouched.

The converse holds too, and matters just as much: a section is **not** run to look
thorough. Ticking value-moving boxes for a release that moves no value asserts
invariants about a feature that does not exist, which is a false claim like any
other. v1.2.0 is a stability release and does not run §7; v1.3.0 does.

---

## 0. Applies to every version (`x.y.0`)

- [ ] `./gradlew clean build` succeeds from a **clean checkout** on a clean environment
- [ ] All tests pass; no critical test ignored, disabled, or weakened
- [ ] `stubMarkerCheck` passes — no `TODO`, `FIXME`, "coming soon", or
      `UnsupportedOperationException` in `src/main/java`
- [ ] `checkstyleMain` and `checkstyleTest` pass with zero violations
- [ ] NexusCore-introduced compiler warnings reviewed; none hidden without written
      justification
- [ ] Artifact is exactly `<loader>/build/libs/NexusCore-<loader>-<version>-<mcVersion>.jar`,
      for all three loaders (ADR-0008)
- [ ] `NexusCore-neoforge-<version>-sources.jar` and `-javadoc.jar` produced
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
- [ ] `./verify-release-jar.sh <loader> <version>` passes on **all three** loaders.
      It refuses to run unless the installed jar's SHA-256 equals `archived/v<version>/`,
      so this box cannot be ticked against anything but the released bytes. Console
      evidence: version reported, `/execute as` against a summoned armor stand refused,
      audit chain intact, no NexusCore error.

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

## 6. From M5

- [ ] GameTests cover teleport safety, home/warp persistence, punishment enforcement,
      permission gating
- [ ] Packaged-JAR smoke test passes
- [ ] Restart persistence verified for homes, warps, permissions, punishments
- [ ] Non-operator player has no admin command access by default
- [ ] Operator bootstrap can grant native groups, then be disabled

## 7. Value-moving features — applies to any release that moves value

Keyed to what the release **contains**, not to a milestone label. If a release adds
any feature in which an item, a balance, or an ownership record moves between two
players — an economy, a shop, an auction house, player-to-player trade, a bounty, a
wager — every box below applies. It applies whether or not the release is called an
economy release, and whether or not M6 is otherwise complete. A feature that moves
value inherits these invariants on the day it ships, not on the day its milestone does.

Per the ladder (ADR-0007), this section is **not exercised by v1.2.0**, which is a
stability release — M5 completion, the write-ahead journal, and the defect backlog.
It is the acceptance gate for **v1.3.0**, which carries the economy and a
**fixed-price** market. Timed auction bidding is deferred to 1.3.x, so the boxes
below are keyed to the mechanism that actually ships; the bidding forms are parked
at the end of this section until it does.

- [ ] Fixed-point integer minor units; no `float` or `double` anywhere in currency code
- [ ] Atomic transfer: debit and credit in one transaction boundary
- [ ] Idempotency replay test: same key produces exactly one committed transaction
- [ ] Reversal creates a compensating entry; no old row is edited
- [ ] Shop purchase is all-or-nothing
- [ ] All six §10.2 invariants have passing **named** tests
- [ ] Restart persistence for balances and history
- [ ] A listing, purchase and settlement are one transaction boundary; a failed
      settlement returns the lot and the buyer's funds, with no partial state
- [ ] **Two players cannot both buy the same listing** — proven by a concurrent-
      **purchase** test, not by argument. This is the primary form of this invariant:
      a fixed-price buy is a plain check-then-act race with no auction clock to
      serialise it, so it is easier to write wrong and easier to miss in review than
      the bidding form — and its failure mode duplicates items rather than merely
      mis-awarding them
- [ ] An expired or cancelled listing returns the item **exactly once**; replay of the
      expiry job does not duplicate it
- [ ] **Conditional — assert only if trade survives the cut order.** Trade is
      all-or-nothing across both inventories, and a disconnect or crash mid-trade
      loses nothing and duplicates nothing, tested by killing the server inside the
      transaction. Trade is in scope but explicitly cuttable (kits first, then market
      GUI to commands-only, then trade). Do not silently skip or tick this box —
      record which of the two happened

**Parked until timed bidding ships (1.3.x).** These activate when bidding lands, and
must not be run before it — an invariant asserted about an absent mechanism is a
false claim like any other:

- [ ] An auction listing, **bid**, and settlement are one transaction boundary; a
      failed settlement returns the lot and **every losing bid**, with no partial state
- [ ] Two players cannot **win** the same lot — proven by a concurrent-**bid** test

## 7a. From M7 — moderation depth and chat

This section did not exist: the document ran M6 straight into M8, so M7 was the one milestone
with no checklist coverage at all. Added because v1.5.0 contains M7 (the 1.3.x builds)
and a release cannot be signed off against a section that is not there. Numbered `7a` rather
than renumbering sections 8 to 11, which are referenced by number elsewhere.

- [ ] Jail survives restart and relog; a jailed player cannot leave by any teleport path
- [ ] Reports and notes persist across restart and appear in the audit chain
- [ ] Channel routing delivers only to subscribers; private messages reach only the recipient
- [ ] **No chat path bypasses the existing mute check** — including channels and private
      messages
- [ ] Anti-spam limit is configurable and enforced, with a boundary test
- [ ] SmartGuard is **NOT MET by design** — excluded from v1.5.0, recorded in
      `IMPLEMENTATION_STATUS.md` under "Not on this ladder"

## 8. From M8 — automation, backups, diagnostics, benchmarks

- [ ] Backup manifest and checksums present
- [ ] Restore **dry run** validates manifest, checksum, and schema before applying
- [ ] Scheduler survives restart with correct missed-run behaviour
- [ ] Both `/nexus doctor` and `/nexus doctor storage` distinguish a seeded fault from a clean
      system
- [ ] Support bundle contains no secrets — verified by test
- [ ] Benchmark harness produces JSON; baseline committed; a local run compares against it
- [ ] CI builds all three loaders from a bare checkout on the release commit
      (`.github/workflows/build.yml`), green on the tagged commit
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

## 11. From M11

- [ ] Public API surface stable and versioned; worked examples compile
- [ ] `docs/user`, `docs/admin`, `docs/api`, `docs/migration` complete
- [ ] Full §17.3 manual release matrix executed and recorded
- [ ] Security review completed
- [ ] `THIRD_PARTY_NOTICES.md` complete with full licence text for anything embedded
- [ ] Every box in §22 checked with evidence, **or removed by a written and signed release
      decision**

## 12. Surface sign-off — applies to every version (`x.y.0`)

A version is not out when the jars are built. It is out when six surfaces agree:
the archived bytes, the mod repo's docs, GitHub Releases, mwtstudios.net, Modrinth,
and Discord. Run from `~/Documents/Projects/studio-control`.

- [ ] `manifest/releases/<version>.json` exists, and `studio.json` `currentRelease`
      points at it; the previous release's `latest` is cleared in the same change
- [ ] `published_defects` **re-derived** from IMPLEMENTATION_STATUS.md, never copied
      from the previous release — fixes that landed change the list
- [ ] `./studio stability` passes, and the check count did not **fall**
- [ ] `./studio sync` reports no `DRIFT`, `MISSING` or `ERROR`
- [ ] `./studio sync --deep` — the bytes GitHub actually serves hash to the archived values
- [ ] Modrinth and Discord published, then attested — see "Who may attest" below
- [ ] No surface left `UNVERIFIABLE` without the human route named in the sign-off table
- [ ] **No surface advertises a capability whose evidence does not yet exist.** This
      binds all six — the archived bytes, repo docs, GitHub Releases, mwtstudios.net,
      Modrinth and Discord — and the manifests that render them. Code present in the
      tree is not a claim that may be published: a capability ships outwardly when its
      exit condition is met, not when its code lands. Worked example: economy code is
      built across `1.2.1`–`1.2.5`, and no surface says NexusCore has an economy until
      `1.2.5`'s exit is met.

### Who may attest, and what makes an attestation count

`attest` records the SHA-256 of the **rendered** payload at the moment somebody says
they published. It does not read the live surface — there is no Modrinth or Discord
token on this machine — so it answers one narrow question: *has the manifest changed
since the publication was claimed?* An attestation is never observation, and
`SYNCED (attested, not observed)` must never be read as `SYNCED (observed)`.

Two parties may attest:

- **A human who published it.**
      `./studio attest <surface> <version> --by <name>`

- **An agent that performed the publication itself**, in the browser, in that session.
      `./studio attest <surface> <version> --by claude:<session> --note "<evidence ref>"`

  An agent attestation is **provisional until all three hold**:
  1. the agent performed the publication itself — never attesting someone else's work,
     and never attesting a surface it only inspected;
  2. **byte-equality evidence** was produced at publish time — the text committed to the
     live surface compared against `render/<version>/<artifact>`, character for character,
     read back from the live editor rather than from what was sent;
  3. that evidence was **delivered to Master Mode** and acknowledged. Until it is, the
     record does not count toward this checklist.

  The `--by` value must name the session, not merely say "claude". An agent that
  publishes and then reports its own success is the weakest link in this document, and
  the record must make that visible to whoever reads it later rather than hiding it
  behind a status that looks identical to human sign-off.

- [ ] Every agent attestation for this version names its session in `--by` and has its
      byte-equality evidence acknowledged by Master Mode
- [ ] Any attestation superseded by a later one records what it supersedes, in `--note`

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
| `./studio sync` verdict | |
| Attested surfaces (surface, by whom, human or agent, when) | |
| Agent attestations — evidence acknowledged by | |
| Items UNVERIFIABLE, with the human route for each | |
