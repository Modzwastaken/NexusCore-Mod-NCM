# NexusCore — shared changelog

Changes to the **shared code** — the 22 of 24 source files every loader compiles. Loader-specific
changes live alongside each build:

| Loader | Changelog |
|---|---|
| NeoForge | [neoforge/CHANGELOG.md](neoforge/CHANGELOG.md) |
| Fabric | [fabric/CHANGELOG.md](fabric/CHANGELOG.md) |
| Forge | [forge/CHANGELOG.md](forge/CHANGELOG.md) |

User-facing summaries of major releases across all loaders are in
[release-notes.md](release-notes.md). Released builds are kept in [archived/](archived/).

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning is
`v1.0` → `v1.5` → `v2.0`, per [ADR-0007](docs/architecture/ADR-0007.md). Minor releases add
features and stay data-compatible; major releases may change data formats and must ship a
migration. **The version number carries no completeness promise** — see
[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for what actually exists.

Per [ADR-0012](docs/architecture/ADR-0012.md): **`x.y.0` is a version** — `1.0.0`, `1.1.0`,
`1.2.0` … — and **`x.y.1` through `x.y.5` are its builds**, each a *hotfix* or a *pre-release*.
Five builds fill a version, then the minor moves up: `1.0.5` is followed by `1.1.0`, never by
`1.0.6`. Every heading below says which kind it is.

## [1.1.1] — unreleased — pre-release

Work toward the next build.

### Added
- **The GameTests genuinely run on all three loaders.** The move to `common/` shared the twelve
  test bodies, but discovery is the part the loaders do not share, and for a window on `main`
  NeoForge and Forge each registered ZERO tests while their tasks exited green —
  `tools/verify-gametests.sh` caught it, and the fix is what that entry named: per-loader holder
  classes that *declare* the twelve methods (vanilla registers `getDeclaredMethods()` only,
  so nothing inherited counts) and delegate to the shared bodies.

  The holders differ because the loaders differ, each rule read from that loader's own sources:
  NeoForge namespaces a test from `@GameTestHolder` and needs `@PrefixGameTestTemplate(false)`
  or the structure name grows a class prefix; **Forge's `enabledGameTestNamespaces` property
  filters test NAMES, not template namespaces** (`ForgeGameTestHooks.addTest`), so the holder's
  `@GameTestHolder` value is what carries its tests past the filter, and its templates keep the
  full `nexuscore:empty` form because Forge uses a colon-bearing template verbatim; Fabric
  instantiates the shared classes directly as `fabric-gametest` entrypoints and needed no holder
  at all.

  The first Forge execution ever then failed two tests, and the failures were the tests' own:
  the suffocation and lava cases left part of the search space to harness geometry, which is
  loader-specific in BOTH directions — Forge's structures float over a platform with a two-block
  air gap below, and are encased in barrier blocks above, so a barrier two above the lava was
  solid ground for a candidate three up. Each geometry was probed rather than assumed, and in
  each case `SafeDestination` had correctly reported the pocket it found — the feature working,
  misread by the test as the check failing. Both tests now fill every position the search can
  visit, so they assert about the check rather than about the scenery. 12 / 12 / 12 via
  `tools/verify-gametests.sh --expect 12` on every loader.
- **The in-server harness now covers teleport safety, home persistence and the admin panel.**
  `NexusWorldGameTests` holds the tests that need a real world or a real player: a destination
  buried in stone and one standing in lava are both refused with a reason, ordinary solid
  footing is not; homes survive a storage round trip with their coordinates and world intact,
  the limit is enforced, and deleting frees a slot so the limit counts what exists rather than
  what was ever created.

  The one the 1.1.2 rung named specifically is `adminMenuRefusesEveryClick`. The panel is a real
  chest menu on an unmodified client, so the only thing between a decorative icon and a
  duplicated item is that the container refuses every click — and that could not be tested at
  all without a player who owns an inventory. It now asserts that a click leaves the panel
  untouched and the cursor empty, that shift-click moves nothing, and that items cannot be put
  in from the player's own row.

  Proven to fail, each mutation restored byte-identically: `quickMoveStack` delegating to super
  fails the panel test; `clicked()` delegating to super fails it with the panel's diamonds
  actually leaving, 5 → 0, which is the duplication case rather than a proxy for it; and
  `SafeDestination.find` always reporting safe fails both the suffocation and the lava test.
- **An in-server test harness.** `runGameTestServer` boots a headless server, runs every
  registered GameTest and exits non-zero on failure. `NexusGameTests` holds the first six,
  reaching the live service registry the server is running on rather than building a second one.
  This exists because `common/src/test` cannot construct a `ServerPlayer` or a
  `CommandSourceStack`, so several refusal paths were fixed but verified only by reading. The
  harness earned itself immediately by catching the argument-type defect above.

- **A write-ahead journal for updates that span more than one file (§11.1)** — the gap M2 left
  open, and the one this document has listed as "the first thing M6's economy will need" since
  v1.0.0. `JsonStore.write` makes *one* document's replacement atomic and says nothing about two:
  writing `accounts.json` and then `ledger.json` is two atomic writes with a window between them,
  and a crash in that window leaves books that do not balance — money debited and never credited,
  or credited twice. Nothing in the mod could detect that state afterwards, because both files are
  individually well-formed.

  `JournalService` closes the window. A transaction stages every document beside its target,
  writes one record naming each target and its staged file's SHA-256, forces that record to disk —
  **the commit point** — and only then moves the staged files into place. A record that still
  exists at startup means the process died mid-transaction, so `replayPending()` finishes it before
  any module reads data. Replay is idempotent by construction: a staged file that is gone was
  already moved, so the progress marker and the work it records are the same filesystem operation
  and cannot disagree.

  Two deliberate refusals. A staged file whose bytes do not match the hash its record committed to
  is **not** applied — replay throws, naming the file, and leaves the record in place so nothing is
  lost and the next start retries. And a document name containing the staging marker is rejected,
  because recovery deletes leftover files by that marker and a target carrying it would be real
  data caught in the sweep.

  It has **no caller yet.** M6's economy is the intended one and 1.2.2 builds atomic transfer on
  it; single-document writes should keep using `JsonStore.write`, which is already atomic. Built
  ahead of its 1.1.4 slot at the owner's direction, because it is a prerequisite under every
  candidate v1.2.0 design — recorded in
  [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md#the-road-to-v150) rather than by renumbering
  the ladder. See [ADR-0013](docs/architecture/ADR-0013.md) for why the design is a journal of
  intent rather than a lock or a single combined document.

- **`JournalTest` — 34 tests, built around crashing on purpose.** The exit condition for this work
  is "journal replay verified by a simulated crash mid-transaction", so the tests inject a failure
  point *inside* the apply loop rather than testing only the happy path.
  `crashMidTransactionIsRepairedByReplay` updates three files, dies after the first lands, and
  **asserts the state is genuinely torn** before recovering it — a test that skipped that assertion
  would still pass if the crash never happened. `crashAtEveryPointIsRecoverable` covers all four
  crash points, including the one past the last entry where the work is done but the record has not
  been cleared. `crashDuringReplayIsRecoverable` crashes recovery itself.

  **Proven to fail, reproducibly.** `tools/mutate-journal.sh` breaks the journal four specific ways
  — record written after applying instead of before, the already-applied skip removed, the
  quarantining read restored, the post-loop injection point removed — and asserts a named test
  catches each one, restoring the source and comparing it byte-for-byte afterwards. An earlier
  version of this entry claimed "proven to fail two ways" with nothing in the repository that
  reproduced it: the mutations were applied by hand and reverted, so the claim rested on my word.
  A review declined to accept that and was right to; the script is the artifact that was missing.

  **What the suite cannot prove, stated per §20.4.** Crashes are simulated in-process by throwing
  at an injected point. That establishes the *order* of operations and that recovery repairs what a
  stopped process leaves. It does **not** establish durability: deleting any `force()` or
  `forceDirectory()` call would leave every one of these tests green, because nothing here loses
  power. The fsync calls are argued for, not demonstrated, and that is still true at the end of this
  build.

  Two inherited platform ceilings compounded it when the journal landed — `forceDirectory`'s silent
  Windows no-op, and a non-atomic fallback in `JsonStore.move`. **Both were resolved later in this
  same build**, one closed and one ruled, under *Fixed* below. This paragraph is left describing the
  state the journal landed in rather than rewritten to look tidier, because "the ceilings were
  identified when the journal shipped and closed before anything rode on it" is the true sequence
  and a reader following the 1.2.2 gate needs it.

`JsonStore`'s `move`, `force` and `forceDirectory` became package-private so the journal reuses
them instead of carrying a second copy of the atomic-move protocol — including the
`AtomicMoveNotSupported` fallback and its warning, which is exactly the kind of detail that gets
fixed in one copy and not the other.

### Fixed
- **The chat and login paths no longer scan every punishment ever written.** `activeRecord()` runs on every chat message and every login, and it walked the whole
  punishment document to find the handful belonging to one player — so the cost of sending a
  chat message grew with the total history of the server, the one number that only ever goes
  up. Records are never deleted here, so the scan could not be bounded by pruning. An index of
  the ACTIVE subset, keyed by kind and player, bounds it instead; `activeBans()` walks that
  index rather than the document, which also makes its per-player deduplication structural
  rather than a guard against file order.

  The index holds the same `Record` objects as the document — never copies — so a flag flipped
  through one is visible through the other, and it is rebuilt from the document at construction
  rather than persisted. The document stays the only source of truth.

  **No speed claim is made.** No performance number in this repository is measured, and the
  benchmark harness with a committed baseline is a later deliverable. What is claimed is the
  complexity change and the behaviour, and the behaviour is what the tests pin.

  One correction worth recording: the first version of this change documented the index drops
  as a correctness guarantee — that a retired record left indexed would report a lifted ban as
  still in force. That is false. `reconcile()` re-checks `active` on every entry it reads, so a
  stale entry is skipped and cannot resurrect a ban. The drops are a **space** guarantee: without
  them the index accumulates every record ever written and becomes the scan it replaced, in
  memory. The claim was found to be wrong by mutation — removing a drop failed no behavioural
  test — and `activeIndexSize()` exists so the real property can be asserted directly. All four
  drop sites are now proven to fail: removing any one of them fails exactly one test.
- **A custom command argument type broke the command tree sent to joining players, and the new
  in-server test harness caught it within minutes of existing.** The wildcard fix earlier in this
  build introduced `PermissionNodeArgument` so `nexuscore.command.*` could be typed unquoted.
  Every custom Brigadier argument type must be registered in the command-argument registry, or
  the server cannot serialise its command tree — the first GameTest to create a player failed
  with `Unrecognized argument type`. That serialisation happens on every join, so the defect was
  strictly worse than the one it fixed.

  Registering it would mean per-loader registry wiring in a mod that deliberately registers
  nothing, and could only be runtime-verified on one of the three loaders from here. The node
  argument is instead a plain greedy string, which reads `*` unquoted and needs no registration:
  `/nexus permission set <player> <allow|deny> <node>` puts the verb before the node so the node
  is last. The argument order changed; the wildcard is still typable, which was the point.

  Proven to fail: reintroducing an unregistered argument type makes the harness report
  `Unrecognized argument type` instead of passing.
- The admin panel no longer acts on a player who has left. Each tile closed over the `ServerPlayer`
  resolved while the panel was drawn, so if the target logged out before the click arrived, the
  action ran against a detached session — doing nothing — and the audit log recorded it as
  `allowed`. A trail that says a heal happened when it did not is worse than one that says
  nothing, in a mod whose claim is that the trail can be trusted.

  An action body now **receives** its target, re-resolved by UUID when the click is handled, and a
  target who has gone is refused and audited as a failure. The tiles are drawn by a method that
  takes only values — a snapshot, a UUID, two booleans — so no session object is in scope for a
  body to capture by mistake. That is the part worth keeping: reverting the body signature does
  not fail a test, it **fails to compile**, at all five call sites. The defect is unwritable
  rather than merely fixed. `AdminGuiActionShapeTest` pins both shapes by reflection.

  The refusal path itself — that a departed target is audited as `failed` rather than `allowed` —
  needs a live player to exercise and is recorded against 1.1.2, with the two command refusal
  paths from earlier in this build.
- A confirmation is no longer staged for an action that cannot run. `/ban` in safe mode issued its
  prompt happily, because the only thing reaching the moderation module was the token's *body* —
  and a body does not run until the operator confirms. `/nexus confirm` then spent the token, the
  action failed, and the operator was left with no ban, no token, and no record of either.
  `proposeBan()` now reaches the module first, so the refusal happens before anything is staged;
  and a body that throws after its token is spent is audited and reported, because the token
  stays spent — single use is a security property, and returning it would let a partially applied
  action be retried.

  **Recorded honestly: this fix is not closed by a test.** Both changes sit at command call sites,
  which nothing can invoke without a `CommandSourceStack` harness; removing either leaves all 289
  tests green, which was verified by mutation rather than assumed. `SafeModeConfirmationTest`
  pins the mechanisms it can reach. The row stays in the defect table until 1.1.2's command tests
  can drive the propose and confirm paths.
- `/nexus permission set|unset|check` accept a wildcard pattern. The node argument used
  Brigadier's `word()`, whose character set excludes `*`, so it silently stopped at the dot and
  `nexuscore.command.*` — the form operators most often want — could not be typed at all. A
  `PermissionNodeArgument` reads the pattern whole and validates it with the same
  `PermissionNode.of()` the engine uses, so an argument that parses is one the permission system
  accepts, and a mid-pattern wildcard is refused at the keyboard with the engine's own reason.
- `/nexus permission check` explains the decision enforcement would actually reach. It called the
  evaluator directly, bypassing `authorise()`, so it could never show the operator-bootstrap
  grant: the command whose entire job is explaining a decision under-reported who could do what.
  Bootstrap application is now one implementation shared by the explain and enforcement paths,
  and it resolves operator level for offline subjects too.
- `/nexus reload` now applies `commandsPerMinute` and `permissionCacheSize`. Both kept their
  boot-time values while the command reported success — the first is a rate limit, so an operator
  lowering it after abuse was told it had worked when it had not. `ApplySettingsTest` drives the
  real reload path rather than the setters.
- `DurationParser.format()` renders `1s` rather than empty text for a positive duration under one
  second, so a cooldown or expiry in its final second no longer shows a blank where a time belongs.
- `/pardon` lifts pre-NexusCore vanilla bans too — the takeover pardon only lifted NexusCore
  records, leaving bans that predate the mod enforceable at login but liftable by nobody. It now
  lifts both systems and says which it found; vanilla's own entry also supplies the UUID, so a
  pre-takeover pardon works on the first attempt with no network lookup. `/banlist` now shows
  vanilla-only entries and vanilla IP bans instead of hiding enforced bans.

Six defects in the journal above — three of them serious enough to lose a committed transaction —
all found by an adversarial review before anything was built on it. None had shipped, and the
review's verdict was that the design held and the implementation did not yet. The point of finding
them here is that money and item custody were going to ride on this.

**The two durability ceilings are resolved — one closed, one ruled.** They are the gate before
1.2.2, because 1.2.2 is where money starts riding on this layer, and "we recorded the ceiling" is a
weaker position than it sounds once a balance depends on it.

- **`JsonStore.move` no longer completes a write it cannot make atomic.** Where `ATOMIC_MOVE` was
  refused it logged a warning and did a plain replace — so §11.1-R4, *"a reader after a crash sees
  the complete previous document or the complete new one, never a partial write"*, was quietly false
  on that filesystem while every javadoc, spec row and status line went on claiming it. A torn
  `permissions.json` is indistinguishable from a good one. The fallback is gone: the write fails,
  the caller cleans up its temporary file and reports, and R4 holds or nothing happens.

  A **same-directory precondition** is enforced first, and that is the part a test can reach. Both
  callers already satisfied it — `write()`'s `.tmp` and the journal's staging file are each a
  `resolveSibling` of their target — and it is what makes the move a rename, which is the only
  operation a filesystem performs atomically. The refusal branch itself cannot be produced portably,
  so it is argued rather than demonstrated, and `tools/mutate-storage.sh` says so in its header
  instead of letting a green run imply otherwise.

- **A rename is not flushed to disk on Windows, and that is now published rather than whispered.**
  `forceDirectory` opens the directory as a channel; Windows cannot open a directory as a channel at
  all, so every directory fsync NexusCore performs is a no-op there. The failure was logged at
  DEBUG — which nobody runs — so the weakening was invisible on the one platform where it always
  happens. It is now reported **once per run at WARN** and carries a named row on the published
  defect list. **Ruled, not fixed**: there is no directory-fsync equivalent to reach for. Documents
  are still replaced atomically; what is weakened is surviving a power loss immediately afterwards.
  Linux and macOS are unaffected.

- **`audit.log` grew without bound, and every start re-read all of it.** It is now sealed into
  numbered segments — `audit-000001.log`, oldest first — at `auditMaxSegmentBytes`, default 8 MiB,
  configurable and honoured on `/nexus reload`. Zero disables rotation, which is deliberately *not*
  routed through `clamp()`: clamp would read zero as out of range and correct it back to the
  default, quietly turning rotation on for an operator who turned it off.

  **The chain crosses a rotation untouched, which is the whole difficulty.** Rotation only renames
  a file: the next record's `previous_hash` is still the in-memory hash of the record before it, so
  the chain does not know a rotation happened. Nothing is rewritten, so nothing can be rewritten
  *wrongly* — a rotation that re-hashed or re-numbered anything would be indistinguishable from
  tampering, in the one file whose entire purpose is being tamper-evident.

  `verify()` now walks every sealed segment oldest-first and then the live log, and names the
  segment a break is in. Verifying only the live log would report *chain intact* over a history
  whose older half had been edited — answering the question wrongly, which is worse than not
  answering it. **Proven to fail two ways**: restricting `verify()` to the live log fails 2 tests,
  and resetting the chain on seal fails 3, including the restart case.

  Startup no longer re-reads the whole history: the resume point comes from the last record's own
  `sequence` field, so one bounded segment is read instead of everything ever written. `tail()`
  reads back from the newest log and stops as soon as it has enough.

- **One byte that was not UTF-8 in `audit.log` stopped the mod from starting.** `readAllLines`
  decodes strictly, so a `MalformedInputException` reached the audit module's constructor — and
  audit is a **core** module, so the whole mod refused to load. A torn final append from a power
  loss is enough to produce it, on an append-only file, with no recovery path inside the mod. The
  row called it `low`; a single byte denying boot with no way back in is not low, and it is retired
  rather than reclassified because it is now fixed.

  The audit log's reads decode leniently, replacing what will not decode. **The replacement is not a
  repair and must not be mistaken for one** — nothing on disk is touched, and a substituted character
  changes the line, so its SHA-256 changes, so `/nexus audit verify` reports the chain broken exactly
  there. That is *true*, and it is the outcome an operator needs: the damage surfaced by the tool
  built to surface it, rather than by a server that will not boot.

  Only the log reads changed. `JsonStore.read` still decodes strictly, because bytes that are not
  UTF-8 in a *document* mean it is not the thing it claims to be, and failing loudly is right there.
  The difference is whether the server can start without it. **Proven to fail**: restoring strict
  decoding fails all three new tests.

  **This makes a damaged log startable and reportable. It does not repair one**, and that gap is now
  its own defect row rather than absorbed into this fix. The consequence worth naming is not the
  corrupt line: it is that `verify` stays red forever afterwards, and a signal that is always red
  stops being read — tamper-evidence turned off by attrition rather than by failure. A repair path
  needs its own design, because anything that re-anchors a hash chain is indistinguishable from
  tampering unless it records what it changed and why.

- **`players.json` was rewritten in full on every join and every leave.** A rejoining player moves
  one timestamp; paying a whole-document serialise, a `.bak` copy and an fsync for that — twice per
  session, per player, forever — is the amplification. Last-seen updates are now damped behind a
  30-second window and flushed when the identity module stops.

  **Damping may cost a timestamp's precision. It must never cost a fact.** A player the document has
  never held, and a name change the index resolves by, are written through immediately; only pure
  observation waits. And the flush on shutdown is what makes any of it safe — without it, damping
  would trade a real write-amplification problem for a quiet data-loss one on every clean stop.

  **Two bounds, and neither alone is sufficient.** A shutdown flush does not fire on a crash, an
  OOM kill, or a host losing power — the cases an operator actually worries about. And the time
  bound never fires on an idle server, because damping runs on player events rather than on a
  ticking clock: with no further joins or leaves, "30 seconds have elapsed" is never checked. So a
  second bound caps the count: at most **20** damped updates may be outstanding, whatever the clock
  does. The residual is stated with its numbers on the 1.1.4 row rather than left for a reader to
  infer from the word "damped".

  The service also had **two clocks**: the damping window ran on the injected one while the
  timestamps it damped ran on `System.currentTimeMillis()`. That is now one clock throughout, and it
  is not a tidiness change — see below.

  **A test of mine passed against a no-op `flush()`.** The first version asserted the persisted
  timestamp was `>=` its previous value; when the damped write never landed, the old value satisfied
  that comparison and the suite stayed green. The two clocks are why it could not have caught it —
  no test could observe a damped write landing while the value being damped came from wall-clock.
  Fixing the clock made the assertion expressible as an exact value, and the injection now fails as
  it should. **A fix whose removal no test notices is not a closed defect** — the standard Master
  Mode applied to its own safe-mode row this session, met here by failing it first.

**And two read/write-path defects, which turned out to be one mistake wearing two hats:**

- **An unreadable file was treated as a corrupt one.** `read()` caught `IOException` alongside the
  parse failures, so a full disk, a changed permission, an interrupted read or an NFS blip renamed
  an operator's **intact** `permissions.json` to `.corrupt-<timestamp>`, told them it "could not be
  parsed", and refused to start until they put it back. The data was never bad. Quarantine now means
  what it says — bytes that were read and are not a document — and a failure to *read* reports and
  leaves the file exactly where it is.

  This is the same shape as the journal defect found in review a few hours earlier: both quarantined
  on the wrong signal. Twice in one layer makes it a pattern rather than a slip, so it is named as
  one: **quarantine is a judgement about content, and must never be reached by a path that never saw
  the content.**

- **A failed write left its scratch behind.** Cleanup lived inside `catch (IOException)`, and a
  serialisation failure is not an `IOException` — so the `.tmp` survived, the next write found stale
  scratch, and `noTemporaryFileLeftBehind` went on passing while being untrue. `JsonIOException` is
  now named in the catch, because Gson wraps writer failures in it and it extends
  `JsonParseException` rather than `IOException`, so it had been walking past untouched and reaching
  callers as a raw `RuntimeException`. But naming it only fixes the *reported type* — the cleanup
  moved to a `finally`, which is what actually closes it, because Gson only wraps failures coming
  from the writer and a serialisation error raised anywhere else matches no catch clause here at all.

  Worth recording how that was found: the first version of the test used a `Number` whose
  `toString` threw. Gson reflected over the anonymous subclass instead of calling it, serialised
  `{}`, and the test passed against code that still leaked. A test that passes against broken code is
  the exact failure these tests exist to catch, met while writing one.

- **The corrupt-record refusal destroyed the transaction it was protecting, on the second start.**
  `pendingRecords()` read each record through `JsonStore.read`, which moves an unparseable file to
  `<name>.corrupt-<timestamp>` **before** throwing. That renamed file no longer ends in `.json`, so
  it stopped matching the listing: the first start refused correctly, and the operator's reflexive
  restart found zero pending records, concluded nothing was owed, and let the sweep delete that
  transaction's staged files as ownerless garbage. A committed transaction vanished silently, with
  no log line connecting the two events — and the refusal's own message had promised the record was
  left in place. Records are now parsed directly, never quarantined; an unreadable record is a
  **hard** pending item that refuses at every start; a record quarantined by the older code is still
  recognised as owed work; and nothing is swept while any record is unreadable.

- **Staged files' directory entries were never fsynced.** `stage()` forced each staged file's
  contents but not its parent directory, so a power loss could leave the record durable and the
  staged file's *name* missing. Replay reads a missing staged file as already-applied — the very
  property that makes it idempotent — so a committed transaction would have silently half-applied.
  ext4's default ordering usually hides this; XFS and anything journaling dirents separately do not.
  The staging directories are now forced before the record is written.

- **The fourth crash point was untested, and the seam could not create it.** The injection fired for
  entries `0..size-1`, so with three entries `crashIndex=3` triggered nothing: `commit()` ran to
  completion and the case silently re-ran the happy path while its comment claimed to cover the
  window between the last move and clearing the record. No test ever replayed a record whose staged
  files were all gone — the exact state escrow will depend on tolerating. The seam now fires after
  the loop, all four crash points genuinely crash, and `clear()`'s failure branch is covered too.

- **The repair instruction told operators to forge the progress marker.** On a hash mismatch the
  error said to "repair or remove the staged file" — but a missing staged file *is* the
  already-applied marker, so removing it makes the next start skip that document and report the
  transaction complete when that write never happened. Deleting the record instead tears it the
  other way. Both escapes an operator reaches for first were traps. The message now names them as
  traps, lists which documents are already applied and which are still owed, and gives two routes
  that work: restore the staged file to its committed hash, or move the record and every remaining
  staged file aside **together**, with `<name>.bak` holding the previous contents.

- **`replayPending()` is public, and its "assumes it is alone" contract lived only in a javadoc.** A
  future admin repair command calling it mid-transfer would have swept an in-flight transaction's
  staged files, after which its record would point at files that no longer exist and replay would
  read every one as already-applied — the transfer reporting success having written nothing. The
  sweep now skips staged files belonging to transactions currently staging.

- **The reserved-name rule was enforced in one of two places.** `Transaction.put` refused a document
  name containing `.txn-`; `JsonStore.write` did not, so a plain write could still create a file
  that recovery would later delete as scratch. Both entry points now share one check.

- **The shared test suite ran on one loader of three.** `sourceSets.test.java.srcDirs +=
  '../common/src/test/java'` existed in `neoforge/build.gradle` only; Fabric and Forge added the
  shared *main* sources and never the tests. So every common test — storage, permissions, audit,
  the module manager — executed on NeoForge, while all three loaders compiled the code under test
  and reported "green". "214 tests, three loaders green" was 208 tests on one loader and 3 on each
  of the others, which reads like three-way coverage and is not. The same shape as 1.0.4's
  reproducibility finding, where the enforcing block sat in one build file and §18.5 held for one
  jar of three.

  Found reviewing the journal, whose crash-recovery proofs made it impossible to ignore: the
  substrate every future money and item transfer will stand on was exercised on one loader. The
  shared sources are identical bytes, so the *code* cannot diverge — but each loader supplies its
  own Gson and its own logging binding, and the storage layer is built on exactly those.

  Now wired into all three. **732 test executions** where there were 214: 242 shared tests on each
  loader, plus Fabric's and Forge's own 3. No test needed changing — the three that locate source
  roots already walk up to find `common/src/main/java`, which 1.0.4 fixed for a different reason.

- **Vanish now applies to players who join later, and survives death.** Two of the four faults
  in that row. Vanilla sends a new client the entire player list, so a staff member who vanished
  before that client connected appeared in their list anyway — vanish held only for whoever was
  already online when it was switched on, which read as it randomly failing. And respawning
  replaces the `ServerPlayer`, so the invisibility flag was lost while the vanished set still held
  the UUID: NexusCore filtered the staff member out of `/list` and `/near` while every client could
  see them standing there. The two halves of the state disagreed and the visible half was wrong.

  `hideVanishedFrom` runs on join and `reapplyVanish` on respawn, on **all three loaders**. Both
  are behind the `player-utilities` module check, because these run inside login handlers where an
  exception stops players joining at all.

  **The other two faults are not fixed and are not claimed to be.** The player-list desync that
  renders staff chat as a red chat-validation error, and un-vanish not restoring the entity for
  clients that received `AddEntity` while vanished, are both client-rendering behaviour. The first
  can only be fixed by changing how staff chat is delivered, which is a product decision rather
  than a defect fix. Both are scheduled for the 1.1.3 two-real-players sweep.

  **Proven to fail, not merely to pass:** `VanishParityTest` checks all three entry points, since
  they are the one file the builds do not share — exactly where a fix lands on one loader and
  misses the others, as the Fabric death-message row in the defect table already shows. Removing
  both hooks from Fabric alone failed two of its three tests; restoring them went green.

- **A second ban or mute no longer stacks on the first.** Three faults shared one cause:
  `/unban` lifted one record, reported success, and left the player banned; `activeBans()` counted
  a player once per active row, inflating `/banlist` and the admin panel; and `activeRecord()`
  returned the last-issued match, so an arbitrary position in the file decided how long somebody
  stayed banned. `ModerationService:108,150,205`.

  `issue()` now retires any punishment of the same kind already in force, stamping the old record
  with `supersededByRecordId` — punishments are still never deleted, so the history shows what
  replaced what. A named `reconcile()` retires lapsed records, keeps the **strictest** of whatever
  remains, and retires the rest; `lift()` calls it explicitly rather than relying on a query to
  mutate state as a side effect, because that dependency is exactly what a later refactor removes
  without noticing.

  The two rules answer different questions and both are needed. A deliberate re-ban is the
  operator's decision and takes the new terms, shorter or longer. Several records in force at once
  carry no such intent — they are what builds before this wrote — so there the strictest holds.

  **Proven to fail, not merely to pass:** each mechanism was reverted separately and the suite
  re-run. Removing the reconciliation failed three tests; removing strictest-selection failed two;
  removing supersede-on-issue failed two. An earlier revision also carried a lift-all sweep and a
  de-duplicated `activeBans()`; **neither could be made to fail any test**, because reconciliation
  already covered them, so both were removed rather than shipped as paths nothing exercises.

- **Identity lookup no longer stalls the server thread on a Mojang request.**
  `IdentityService.resolve()` fell through to `GameProfileCache.get(String)`, which performs a
  synchronous HTTP lookup whenever the name is not already cached. It runs on the server thread
  and any ordinary player could reach it by typing `/seen <unknown-name>`, so one slow or
  unreachable Mojang response parked the whole server for the length of the request. Eleven
  command sites resolved names through that path.

  `resolve()` is now local only — the online player list and NexusCore's own record of everyone
  who has joined. A new `resolveAsync()` performs the profile-cache lookup through
  `getAsync(String)`, which runs on the background executor and de-duplicates concurrent requests
  for the same name, then files the result through `observe()` **on the server thread**, because
  the identity document and its name index are not thread-safe and that completion arrives on a
  background thread. A name nobody here has played under is now refused with
  `error.unknown-player.looking-up`, which says a lookup has started and to try again — the retry
  resolves locally.

  This is a deliberate behaviour change: pre-banning a player who has never joined now takes two
  attempts instead of one. It buys the removal of a server-wide stall that any player could
  trigger at will.

  **Proven to fail, not merely to pass:** the blocking call was reinstated and
  `IdentityServiceTest.identityLookupNeverBlocksOnTheProfileCache` rejected it; the file was
  restored and the suite re-run clean. Five behavioural tests cover local resolution,
  case-insensitivity, renames, unknown names, and survival across a restart.

- **Both custom gates now run on all three loaders.** `stubMarkerCheck` and `versionLadderCheck`
  were registered only in `neoforge/build.gradle`. A `./gradlew build` in `fabric/` or `forge/`
  therefore passed with stub markers present in loader-specific sources, and at a `mod_version`
  that was malformed, past `.5`, already archived, or had no changelog entry — while "CI is green
  on three loaders" read as three independent confirmations. It was one, plus two green ticks.

  Both task classes moved to `gradle/gates.gradle`, applied by each loader build. Each build is
  independent with its own wrapper (ADR-0008) and there is no root build to hang a `buildSrc` off,
  so a script plugin is the sharing mechanism available. `mod_version` is read from
  `neoforge/gradle.properties` there, keeping the gate on the same single source of truth the
  builds use, and still on the **raw** value.

  **Proven to fail, not merely to pass:** a `TODO` injected into
  `fabric/src/main/java/.../NexusCoreFabric.java` — a file the gate could not previously see — was
  rejected: *"Stub-marker gate failed (§2.4): 1 violation(s) in 43 file(s)"*. The file was restored
  and the gate re-run clean. Fabric and Forge each now scan 43 files where they scanned none.

- **The repo-root `checksums.txt` is regenerated by a script instead of by hand.** Its first line
  claimed it was "regenerated on every build"; it was hand-run, so after the bump to `1.1.1` all
  three lines still named `1.1.0` jars and **not one path resolved**. A digest that hashes nothing
  is worse than no digest, because it reads as evidence. `tools/regen-checksums.sh` now derives the
  version from `gradle.properties`, refuses to run if a build output is missing, and writes the
  header itself.

- **The shipped jar descriptions no longer advertise economy**, which
  `IMPLEMENTATION_STATUS.md` M6 records as not built and not promised, and
  `neoforge.mods.toml` no longer says **"v0.1 is server-only"** inside a 1.1.0 jar. `fabric.mod.json`
  already carried the correct wording; the three had drifted apart. This text is what a launcher
  renders in a mod list, and it is archived with the release — it cannot be corrected afterwards.

- **`SECURITY.md` no longer directs users to `1.0.1`**, which has no tag, no GitHub Release and no
  `archived/v1.0.1/`. The supported-versions table now lists only versions that can actually be
  obtained, and says why `1.0.x` is absent.

- **`release-notes.md`'s generic Install section named the 1.0.0 jars**, so anyone following it
  installed the unsupported version. It also still called v1.0.0 "the production version until
  v1.5.0 arrives", two releases out of date. Both corrected.

- **`archived/README.md`'s pre-publication exception has been closed out.** It still said v1.1.0
  was archived "before publication" and held "bytes nobody has downloaded"; the Release was
  published 2026-07-27 from those exact files. It also typed v1.0.0 as `Release`, a word its own
  vocabulary section does not define.

- **README now links the website, Modrinth and Discord.** It linked GitHub only, while every other
  surface carried all four.

All of the above were found by a cross-surface reconciliation rather than by review: see
`studio-control` (`./studio sync --deep`), which compares the archived bytes, the repo docs,
GitHub Releases, mwtstudios.net, Modrinth and Discord against one manifest and reports where they
disagree.

### Verified

- **The v1.1.0 release bytes are now runtime-verified on all three loaders, not just NeoForge.**
  An audit on 2026-07-27 found that the jars sitting in `fabricserver/mods/` and
  `forgeserver/mods/` were **2h41m older than the archived release** and, on disassembly, did not
  declare `NexusServices.isSubstituted` at all — so the substituted-source refusal had only ever
  been demonstrated on NeoForge, whose installed jar did match the archive byte-for-byte. This was
  an evidence gap rather than a defect: the fix was present in all three *published* jars
  throughout.

  Closed by installing the archived bytes and re-running each loader. `verify-release-jar.sh`
  automates it: it confirms the installed jar's SHA-256 equals `archived/v<version>/`, boots the
  server, and drives `nexus version` → `summon armor_stand` →
  `execute as @e[type=armor_stand,limit=1] run nexus version` → `nexus audit verify` through the
  console. All three loaders now pass all five checks, including the refusal. The superseded jars
  were moved to `<loader>server/_superseded-jars/` rather than deleted.

  Note for future runs: Fabric's console keeps the raw `§` colour codes, so a matcher for
  `NEXUS » You do not have permission` fails there while the refusal has in fact occurred. Match
  the uncoloured span only.

`mod_version` moved to `1.1.1` the moment `v1.1.0` was archived, because
`versionLadderCheck` refuses to build at a version already sitting in `archived/` — archived bytes
are immutable, so a tree that could still rebuild them would be a way to make the archive lie. The
bump is the intended response, not a workaround.

Planned, per [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md#the-road-to-v150): the blocking
Mojang lookup in `IdentityService.resolve()`, the four vanish faults, and the
multiple-active-punishments group.

## [1.1.0] — 2026-07-26 — the M4-complete version

**A version, not a build** ([ADR-0012](docs/architecture/ADR-0012.md)): the 1.0 line is full at
five builds, so the minor moves up. This rolls up `1.0.1`–`1.0.5` and verifies them together on all
three loaders. Its builds are `1.1.1`–`1.1.5`.

**M4 is complete.** M1's safe mode is complete. The version carries no completeness promise beyond
that — see [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

### What this version contains

Everything from the 1.0 line, in one artifact:

| Build | Contents |
|---|---|
| `1.0.1` | Security hotfix: `/teleport` bypass, `/execute as` root, `/gamemode` wildcard escalation, audit short write, missing `core.confirm` grant |
| `1.0.2` | ADR-0012's version scheme and `versionLadderCheck` |
| `1.0.3` | `ModuleManager` — the §7.3 module contract |
| `1.0.4` | Shared sources to `common/`; archive reproducibility on all three loaders |
| `1.0.5` | Safe mode; generated command reference |

### Fixed

Found while verifying the line together, all in code 1.0.5 introduced or exposed:

- **The admin GUI could not open at all in safe mode, and its handlers had no crash barrier.**
  `openDashboard` read `services.moderation()` to build one tile, so the entire panel — the screen
  an operator would use to look around a degraded server — refused. `openPlayerList` and
  `openPlayerActions` read `services.players()` the same way. All three now degrade, showing a
  barrier tile that names the unstarted module.

- **Nothing caught an exception thrown by a GUI tile handler.** `AdminMenu.clicked` called
  `action.accept(player)` with no `try` anywhere above it, so a failure inside a handler propagated
  out into vanilla's `handleContainerClick`. §12.2's "no stack trace ever reaches a player" was true
  of the command pipeline and **not** of this path — and it predates safe mode, applying to any
  handler bug. Clicks now run guarded: the failure is logged with a correlation id, the container is
  closed rather than left showing half-built state, and the player gets the reference.

  `act()` also caught only `ActionRefused`, so a disabled module escaped even the guarded action
  path. It now catches `ModuleException` and audits the attempt as a failure like any other.

- **A descriptor attributed a command to the wrong module.** `(GUI) players page` claimed module
  `core` while its page calls `services.players()`, so `/nexus help` would have advertised it in
  safe mode and running it would have failed. Corrected to `player-utilities`, and the generated
  reference updated.

### Added

- **`SafeModeGuardTest`** — the guards get guarded. `NexusServices.has` ends in
  `default -> true`, which is correct for a core module and silent breakage for a typo:
  `has("moderaton")` returns true and the guard around a disabled service does nothing. A test
  scans every `has("…")` literal in the sources and fails on any id the switch does not know —
  **proven to fail** on an injected `moderaton`. It also checks that every catalogue descriptor
  names a real module, that safe mode disables exactly the non-core set, and that the property
  parser rejects the near-misses an operator actually types (`yes`, `on`) while still reporting
  `OFF` so the mistake is visible in the log.

- **`/teleport` was deleted, not replaced — a regression I shipped in 1.0.1.** The 1.0.1 hotfix
  added `registerVanillaReplacement(dispatcher, "teleport", VanillaCommands.teleport(services))`,
  but that builder's root literal is named **`tp`**. So the call removed vanilla's `/teleport` and
  then handed Brigadier another node called `tp`; `addChild` finds the existing `tp` child and
  **merges into it** rather than creating a `teleport` child. Net effect for two builds:
  **`/teleport` did not exist**, every command block, datapack function and `/execute run teleport`
  using the canonical name failed — while the log printed *"NexusCore took over /teleport"*.

  The `rename()` helper needed to do this correctly was already in the same file, five lines away,
  with a comment explaining exactly why it exists. Now used. Two tests pin it down, including one
  asserting Brigadier's merge behaviour directly so the reason lives in a test and not only a
  comment.

  **I had evidence of this at 1.0.1 and did not chase it**: `help teleport` returned
  "Unknown command or insufficient permissions" in that session's own test output. I noted it as
  odd and moved on.

- **Safe mode left the server unable to kick a griefer.** Alias registration ran with no module
  gating, so with `overrideVanillaCommands=true` safe mode reflectively **deleted** vanilla's
  `/kick`, `/ban`, `/banlist`, `/pardon` and `/list` and installed NexusCore nodes that then
  refused — leaving an operator with neither NexusCore's moderation nor vanilla's, in the one mode
  that exists for recovering a broken server. `ModuleManager.disable`'s own javadoc predicted this
  exact step and the 1.0.5 caller was written without it. Aliases are now registered only for
  modules that started; vanilla's nodes stay in place. Verified: in safe mode NexusCore now takes
  over only `/tp`, `/teleport` and `/gamemode`, none of which touch an optional service.

- **`/nexus reload` threw a NullPointerException in safe mode.** `applySettings()` read the raw
  `teleport` field instead of the guarded accessor, so the null escaped as a plain NPE rather than
  the `ModuleException` the pipeline knows how to report — a core command crashing because an
  optional module was off, and half-applying the new settings on the way.

- **`/nexus help` claimed vanilla's commands as NexusCore's.** It printed the descriptor's short
  alias *alone*, so it listed `/ban`, `/kick`, `/list` and `/banlist` — names that are only
  NexusCore's when `overrideVanillaCommands` is on **and** the takeover succeeded **and**
  `registerAliases` is on. It now shows the canonical form with the alias in parentheses, and only
  when aliases are actually registered.

- **The vanilla takeover was gated on the wrong setting.** It lived inside
  `if (registerAliases)`, so `overrideVanillaCommands=true` did nothing when `registerAliases=false`
  — an operator wanting NexusCore's `/ban` without short aliases silently got neither. The two
  settings are now independent, as their names imply.

- **Four descriptors described commands that do not exist as written**: `/nexus confirm` omitted its
  required `<token>`; `speed` and `vanish` promised a `[player]` argument the tree does not have;
  and `/tphere` was listed as an alias of `/nexus teleport tp` when it is a different command with
  different arguments. `/delwarp` had no descriptor at all and shares the `setwarp` node — so the
  reference understated what that permission grants. All corrected; `/tphere` and `/delwarp` now
  have their own entries, taking the reference to 50 commands.

### Presentation and repository hygiene

The project is now shaped for someone arriving at it cold rather than for the person who wrote it.

- **CI had never run, for two reasons rather than the one recorded.** The workflow sat at
  `neoforge/.github/workflows/build.yml` — GitHub only reads `.github/` at the **repository root**
  — and it invoked `./gradlew` from the root, where no wrapper exists, because each loader is an
  independent build (ADR-0008). It is now at the root, builds all three loaders as a matrix with
  `fail-fast: false` (a change compiling on two loaders and not the third is the exact defect
  ADR-0008 exists to catch, so cancelling the others would hide which were fine), and uploads each
  jar plus the test reports on failure.

- **`README.md` rewritten.** It repeated the same falsehood the command reference had: *"`/ban`
  and `/kick` … NexusCore does not override them"*, untrue since v1.0. It also still said the
  shared sources live in `neoforge/src/main/java`, moved to `common/` at 1.0.4, and reported
  1.0.0 / 178 tests. Now leads with what the mod does, a runnable quick start, the takeover
  explained accurately, safe mode, and an **honest-state section that names what has never been
  tested** — with the known unfixed defects linked rather than omitted.

- **`release-notes.md` restructured** to be newest-first across versions rather than a single v1.0
  page, with a v1.1.0 entry. It carried the same `/ban` falsehood in its *Known limitations* list,
  which is the worst place for it — corrected to the one that is actually true: `/ban-ip` and
  `/pardon-ip` are not covered.

- **`SECURITY.md` added** — private reporting route, supported versions (v1.0.0 explicitly
  **not** supported), what is in and out of scope, and a pointer to the published list of known
  defects. A mod that gates permissions and writes an audit log needs one.

- **`IMPLEMENTATION_STATUS.md`**: the M4 alias row still described the pre-takeover behaviour
  as `tested`.

- **`git gc`** — the repository's history was 167 MB of unpacked objects for 125 tracked files and
  a largest blob of 0.2 MB. Now **1.3 MB**.

### Security — closed after the second sweep

- **`/execute as <player>` no longer borrows that player's permissions**, and this turned out to be
  portably fixable after all. I had recorded it as impossible without a per-loader access widener,
  because the real issuer lives in `CommandSourceStack.source`, which is private in vanilla. That
  was true of the *field* and wrong about the *information*: vanilla ships a public method whose
  contract leaks it by identity.

  ```java
  public CommandSourceStack withSource(CommandSource source) {
      return this.source == source ? this : new CommandSourceStack(...);
  }
  ```

  So `stack.withSource(x) == stack` is true exactly when `stack.source == x`. `Entity` implements
  `CommandSource` and `Entity.createCommandSourceStack()` passes `this` as **both** the source and
  the entity — while `withEntity`, which `/execute as` uses, replaces the entity and preserves the
  source. The identities therefore diverge precisely when the source has been substituted. No access
  widener, no access transformer, no mixin, no reflection, and no allocation on the ordinary path.

  A substituted source is now **refused**, and the audit actor and rate-limit subject resolve to the
  real issuer rather than the impersonated player.

- **The same fix closes two paths nobody had looked at.** `SignBlockEntity` and `LecternBlockEntity`
  build a command source with `CommandSource.NULL` as the issuer and the **clicking player** as the
  entity, at permission level 2 (verified in vanilla source). So a `run_command` click event on a
  sign, or in a written book on a lectern, authorised and audited as whoever clicked it — an
  operator could craft one and have a privileged player unwittingly run an administrative command in
  their own name, with the audit naming that player. A confused-deputy hole, closed by the same
  check.

- **Console detection is now exact, not a heuristic.** It tested `hasPermission(4)`;
  `MinecraftServer` implements `CommandSource` and is its own source, so the same identity trick
  identifies the console precisely. `hasPermission` remains only as the RCON fallback.

- **Hand-edited permission values failed OPEN.** The loader decided
  `allow = !"DENY".equalsIgnoreCase(value)`, so every value that was not exactly `DENY` became a
  **grant**: `null`, `"denied"`, `"DENY "` with a trailing space, `"no"`, a JSON `false`. An
  unparseable pattern was skipped silently, removing a deny. **Both directions favoured access**, on
  the file `docs/admin/permissions.md` tells operators to edit. Only `ALLOW` and `DENY` are accepted
  now; anything else is skipped and logged. `PermissionValueTest` covers eleven typo values and is
  **proven to fail** on the old logic — `value 'denied' must not grant nexuscore.command.moderation.ban`.

- **`permissions.json` is re-read on `/nexus reload`.** It was read once at construction, so a hand
  edit was ignored and then **overwritten by the next permission change** — silent data loss, while
  the reload reported success. Verified on a real server: an injected group appears as
  `reloaded permissions.json: 4 group(s)` and survives shutdown.

### Verified

Everything below is stated for **all three loaders** unless said otherwise.

- **214 tests, 0 failures.** All three loaders build clean and reproducibly.
- **Eight runtime runs** across the version: normal and safe mode on each of NeoForge 21.1.235,
  Fabric and MinecraftForge 52.1.16, plus targeted runs for the substituted-source refusal and the
  permissions reload. Correct module counts (11 normal, 8 with 3 disabled), `/nexus reload` working
  in safe mode, `/nexus system status` degrading, help correctly filtered, audit chain intact after
  every run, clean shutdown, and **zero NexusCore errors anywhere**.
- `/execute as` against a summoned armor stand is refused, and the vanilla takeover reports all
  eight names truthfully in normal mode and only the three module-free ones in safe mode.
- Every markdown link in the repository resolves.
- **The archives are reproducible across machines, not just across runs.** All three jars built here
  are byte-identical to the ones GitHub's runner built from the `v1.1.0` tag. Getting there took a
  fix: the first comparison showed the Forge jar differing in **112 bytes, every one of them the ZIP
  central directory's external-attributes field** — `0x81b4` (0664, this machine's `umask 0002`)
  against `0x81a4` (0644, the runner's). Same size, same entries, byte-identical contents; the
  builder's umask was inside the archive. Only Forge was affected, because it merges resources into
  the classes directory and that copy preserves the source file's mode.

  §18.5 therefore held only *within* a machine, which is not the property that matters for an archive
  whose stated purpose is that a bug report can be reproduced against the same bytes. All three
  builds now pin `filePermissions`/`dirPermissions`, and **v1.1.0 was re-cut** — it had not been
  published, which is precisely the situation `archived/README.md`'s "archive only after publication"
  rule exists to protect.

- **CI is green on all three loaders from a bare checkout** — the first time a genuine cold build
  of this project has been demonstrated anywhere. It took two runs: the first failed on NeoForge at
  `neoFormTransformSource` because the workflow ran `clean build` in one invocation while
  `org.gradle.parallel=true`, and NeoGradle's expanded-zip cache lives under `build/tmp`, so a
  parallel `clean` deleted it mid-read. Locally this had always passed only because NeoGradle
  restores those outputs from a cache outside `build/`, so the decompile step had never actually
  executed cold on any machine. Release-checklist item 1 is now satisfied by evidence rather than
  by assumption.

**Not verified, and not claimed:** no human player has joined a dedicated server, so the admin panel
rendering, vanish, chat muting and ban-at-login remain `implemented` rather than `tested`. A player
impersonating *another player* via `/execute as` is closed by the same code path that the
armor-stand case exercises, but has not been reproduced with two real players.

## [1.0.5] — 2026-07-26 — pre-release — safe mode, and a generated command reference

The last build of the 1.0 line, and the two things M1 and M4 left `planned`. **M4 is complete.**
Per [ADR-0012](docs/architecture/ADR-0012.md) the next build is `1.1.0`.

### Added

- **Safe mode (§M1).** Starting with `-Dnexuscore.safemode=true` leaves the three optional modules
  — `teleport`, `player-utilities`, `moderation` — unstarted. Their commands refuse with an
  explanation rather than vanishing, and every event handler that touches them is guarded.

  **A system property, deliberately, not a config key.** Safe mode exists for when the server does
  not start properly, and a bad `config.json` is one of the things it recovers from — so reading
  the decision out of that file would make safe mode unavailable exactly when it is needed. The
  dependency direction settles it too: safe mode decides which modules start, and `disable()` must
  be called before `start()`, so a config key would mean starting the configuration module to find
  out which modules to start.

  The mode is logged **either way**, because an operator who mistyped the flag needs to see that
  safe mode is *not* on rather than assume it is.

- **`docs/admin/commands.md` is generated (§12.5).** `CommandCatalogue` holds all 48 descriptors;
  `CommandDocs` renders the document; `./gradlew generateCommandDocs` writes it. `/nexus help` is
  rendered from the same descriptors, so help and the reference cannot disagree — previously they
  were two hand-maintained lists describing one command surface, and both were wrong in different
  ways.

  §12.5 warned this would drift, and it had: the committed document told operators that NexusCore
  does **not** override `/ban`, `/kick` and `/banlist` — in the section headed "read this first" —
  for the two releases since the v1.0 takeover made that false.

- **`CommandDocsTest`** — five tests. The committed document must equal what the catalogue renders;
  every permission node the code checks must have a descriptor; **every descriptor must name a node
  the code actually checks** (so the document cannot promise commands that do not exist); no
  descriptor may lack a summary or claim an unknown module; and rendering must be deterministic, so
  a regenerated document diffs only on real changes.

### Fixed

- **Three mangled string literals in the Fabric entry point, introduced at 1.0.3 and shipped in
  1.0.3 and 1.0.4.** A regular expression used to repoint local variables at the service registry
  also rewrote string contents: `render("teleport.done")` became
  `render("services.teleport().done")`, likewise `teleport.failed`, and the audit action
  `moderation.ban.enforced` became `services.moderation().ban.enforced`.

  Effect on those two builds, Fabric only: a player saw the raw string instead of the styled
  message after every teleport, and a ban enforced at login wrote a wrong action name into the
  audit log — a data-correctness fault in the record that is meant to be authoritative.

- **`MessageCatalogueTest` could not see the bug above, and now can.** Its key pattern only matched
  key-shaped literals, so a malformed key was silently skipped rather than reported as missing —
  the suite passed while the mangled key shipped twice. A new test asserts every literal passed to
  `render`/`raw` **is** key-shaped. Proven to fail: re-injecting the exact bug produces
  `these are passed to render()/raw() but are not valid message keys: [services.teleport().done]`.

- **`/nexus system status` refused outright in safe mode.** It read
  `services.moderation().totalRecords()` and `services.teleport().warpNames()` directly, so the one
  command an operator runs to find out what state a server is in was unavailable exactly when they
  would need it. It now degrades — `Punishments: disabled`, `Warps: disabled` — and prints a
  `SAFE MODE — disabled: [...]` banner, so the mode is visible without reading the boot log. Found
  by running safe mode rather than by reading the diff.

### Verified

Safe mode exercised on **all three** real servers: `started 8 module(s), 3 disabled`, core commands
working (`/nexus version`, `/nexus audit verify` → chain intact), `/nexus system status` degrading
with `Punishments: disabled` and `Warps: disabled`, `/nexus moderation banlist` refusing with
`That feature is disabled: the server is running in safe mode`, clean shutdown, and **zero errors
or per-tick exceptions**.

`/home` from the console still reports `That command must be run by a player` — it fails the player
check before reaching the disabled service, which is the correct order.

203 tests pass. All three loaders build clean and reproducibly.

## [1.0.4] — 2026-07-26 — pre-release — shared sources move to common/

A pure layout change. Nothing about the mod's behaviour changes, and the test for that is strict:
**the same version, built from the old layout and the new one, produces byte-identical jars.**

Comparing a 1.0.4 jar against a 1.0.3 jar would not test anything — the version string is
token-expanded into `neoforge.mods.toml`, `mods.toml` and `fabric.mod.json`, so those bytes differ
by design. The comparison is therefore done by building the **new** layout at `-Pmod_version=1.0.3`
and diffing against the 1.0.3 artifacts, which isolates the layout change from the version bump.

### Changed

- **Shared sources moved from `neoforge/src/` to `common/src/`.** ADR-0008 documented this as a
  wart and did not fix it: 37 of the 39 shared source files lived inside the NeoForge project, and
  Fabric and Forge reached across into `../neoforge/src/main/java` and then **excluded the two
  NeoForge-only files by path string**. Two consequences, both real:

  - The layout said "NeoForge owns the shared code", which is false — all three loaders compile it
    equally.
  - The excludes were string paths in two build files. Renaming or adding a NeoForge-only class
    meant editing `fabric/build.gradle` and `forge/build.gradle` too, and forgetting to would
    compile a NeoForge-only class into a Fabric jar, where it would fail at class-load rather
    than at build time.

  After the move there are **no excludes at all**: `common/` holds only what every loader compiles,
  and each loader project holds only its own entry point and platform code. The rule is now
  expressed by directory rather than by a list of exceptions.

- Shared tests moved to `common/src/test/`. They are compiled by the NeoForge project, which is
  where they already ran; the move is so they sit beside the code they test.

- `neoforge/gradle.properties` **stays where it is**, and remains the single source of version
  truth. Gradle requires per-project settings like `org.gradle.jvmargs` and the Parchment
  coordinates to live in the project directory, so this file cannot move to `common/` — and
  splitting it into a shared half and a NeoForge half to satisfy tidiness would create exactly
  the two-places-to-edit problem this build is removing.

### Fixed

- **Two of the three jars were never reproducible.** §18.5 requires stable entry order and no
  embedded build timestamps, and `IMPLEMENTATION_STATUS.md` recorded it as `tested` — but the
  `AbstractArchiveTask` block that enforces it existed only in `neoforge/build.gradle`. Two clean
  builds of an unchanged Forge tree produced **different** SHA-256 sums. The Fabric jar happened to
  match because Loom's `remapJar` rewrites it, which is luck rather than a guarantee.

  Found by this build's own exit condition: the byte-identity comparison failed on Forge, and
  chasing why turned up a defect that had nothing to do with the move. Both build files now carry
  the block, and all three loaders produce identical jars across two clean builds.

- **`MessageCatalogueTest` assumed a single source root.** It walked up from the working directory
  to the first folder containing `src/main/java` and scanned that — the NeoForge project, which
  used to hold all the shared code. After the move it holds two files, so every one of the 94
  catalogue keys looked like dead wording and the test failed. It now scans **every**
  `<module>/src/main/java`, which is also more correct than before: a key referenced only from a
  loader's entry point was previously invisible to it.

### Verified

- **Byte-identity, properly isolated.** The old layout was rebuilt from its own commit in a git
  worktree with the same reproducibility fix applied, at the same version, and compared:
  **identical on all three loaders.** Comparing against the originally-shipped 1.0.3 jars would
  have proved nothing, since those were built before the reproducibility fix.
- All three jars have the same 109 entries, and **zero** NeoForge-only classes or metadata leak
  into the Fabric and Forge jars — the property the deleted excludes used to enforce by hand.
- 197 tests pass. Packaged jars run on all three real dedicated servers: `NexusCore started 11
  module(s)`, `/nexus version` reports 1.0.4, audit chain intact, clean shutdown, zero errors.

## [1.0.3] — 2026-07-26 — pre-release — ModuleManager

The §7.3 module contract, which M4 left `planned`. No behaviour change: every service starts in
the same order, with the same settings, and the 178 existing tests pass untouched.

### Added

- **`ModuleManager`, `NexusModule`, `ModuleContext`** — services are now registered as modules and
  resolved from a registry instead of being constructed by hand. A module declares its id, whether
  it is `core`, and what it depends on; the manager topologically orders them, rejects dependency
  cycles and missing dependencies, and stops them in reverse order.

- **`NexusBootstrap`** — one shared place that registers the eleven standard modules and starts
  them. **This removes a triplication that was a real defect risk**: `buildServices()` existed
  verbatim in all three loader entry points, differing only in the flight controller, so any
  change to service wiring had to be made three times or the loaders would silently diverge in
  behaviour that no test compares.

- **`NexusPlatform`** — the two things that genuinely differ per loader (the data root and the
  flight controller) behind one interface, so the bootstrap itself is loader-agnostic.

- **`ModuleManagerTest`** — 19 tests: dependency ordering (registered deliberately in the wrong
  order), deterministic tie-breaking over 20 runs, diamonds starting a shared dependency once,
  cycle and self-cycle rejection, unknown and disabled dependencies, duplicate ids, duplicate
  service types, refusal to disable a core module, reverse-order shutdown, and shutdown
  continuing after one module throws.

### Verified

- **11 modules start on all three loaders.** Packaged jars on the real NeoForge 21.1.235, Fabric
  and MinecraftForge 52.1.16 dedicated servers: `NexusCore started 11 module(s)`, `/nexus version`
  reports 1.0.3, the `/execute as` refusal still fires, audit chain intact, clean shutdown, zero
  NexusCore errors. **197 tests pass** — the 178 that existed plus the 19 new ones.

### Changed

- Modules are classified `core` or optional, which is what M1's safe mode needs to have anything
  to disable. **Nothing is disabled yet** — that is 1.0.5, and it needs command gating too, or a
  disabled module leaves commands pointing at a service that was never started. `ModuleManager`
  therefore throws a named `ModuleException` for an unstarted module rather than returning null,
  so a premature attempt fails loudly instead of surfacing as an NPE in a command handler.

- `NexusServices` keeps its accessor API unchanged — it is now a view over the registry. Its
  twelve-argument constructor is retained because the tests build services directly, and forcing
  a registry into a unit test would make the tests worse to prove a point about production wiring.

## [1.0.2] — 2026-07-26 — pre-release — the version scheme and its gate

No runtime change. This build settles how NexusCore is numbered and adds the gate that keeps the
numbering honest. `/nexus version` reports `1.0.2`; nothing else behaves differently.

### Added

- **[ADR-0012](docs/architecture/ADR-0012.md) — the version scheme.** `x.y.0` is a **version**;
  `x.y.1` through `x.y.5` are its **builds**, each a hotfix or a pre-release; `x.y.6` does not
  exist. Five builds fill a version and the minor moves up:

  ```
  1.0.0  version (released)
    1.0.1 … 1.0.5   builds
  1.1.0  version
    1.1.1 … 1.1.5   builds
  1.2.0  version …
  ```

  Every minor behaves identically — there is no special minor and no minor without a `.0`. The
  road to v1.5.0 is simply this sequence run out to `1.4.5`, then `1.5.0`.

- **`versionLadderCheck`** — a `check` gate in the version-owning NeoForge project. It fails the
  build when:

  - `mod_version` is not exactly `MAJOR.MINOR.PATCH`, **including surrounding whitespace**. The
    raw property is validated, never a trimmed copy — Gradle does not trim it either, so
    `mod_version=1.0.2 ` would otherwise pass the gate and then produce
    `NexusCore-neoforge-1.0.2 -1.21.1.jar` and a `fabric.mod.json` that Fabric's semantic-version
    parser rejects.
  - the version has no entry in this changelog, or the only match sits inside a fenced code
    block. This changelog documents the scheme and will grow examples of headings; a quoted
    sample is not an entry.
  - the third component exceeds 5 — the minor should have moved up.
  - a build's heading does not say `hotfix` or `pre-release`, or says **both**, or a version's
    heading claims to be one of them.
  - the version is already present in `archived/`, which is immutable by rule, so building at one
    means the bump was forgotten.

  Proven to fail on every one of those, not merely to pass — see the evidence table in
  [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

- **"The road to v1.5.0"** in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) — what each
  build is planned to contain. It lives there rather than in a new `ROADMAP.md` because §2.4
  gives that document sole authority over naming unfinished work, and a second location would
  drift from the first.

### Changed

- **Three earlier attempts at this scheme are retired.** ADR-0009 introduced a build counter,
  ADR-0010 added five-rung lines with `.5` as a pre-release, and ADR-0011 split minors into
  release lines and development lines to make room for a hotfix. Each solved a problem the
  previous one created, and together they made `1.1.0` an *illegal* version string. ADR-0012
  supersedes ADR-0010 and ADR-0011 outright and states the whole rule in one place. The ADRs
  stay in the tree as the record of how the scheme was arrived at.

- **`1.0.0.1` and `1.0.0-1` were tested against the real parsers and rejected.** Maven's
  `DefaultArtifactVersion` reads `1.0.0.1` as `major=0` — it supports three numeric components,
  not four — and Fabric's `VersionParser` sorts `1.0.0-1` **below** the release it fixes, because
  semver reads `-1` as a pre-release identifier. That is the worst possible failure for a hotfix.
  Recorded in ADR-0011, and the reason `1.0.1` is the hotfix number.

- **`RELEASE_CHECKLIST.md`** keys its ceremonies to *build* and *version*, and gains a missing M7
  section — the document ran M6 straight into M8, so a milestone v1.5.0 contains had no coverage
  at all. Stale `v0.1`/`v0.5` labels retired by ADR-0007 are corrected, as is an artifact
  filename pattern predating the three-loader split.

- **`THIRD_PARTY_NOTICES.md`** described a single `NexusCore-<version>.jar` and dated itself "as
  of version 0.1.0 (M0)" — an artifact shape retired by ADR-0008 and a version label retired by
  ADR-0007. It now names the three-loader pattern and adds the two build tools missing from its
  table. Still nothing is embedded in any jar.

### Fixed

- **The Forge dev-run limitation was resolved and nobody noticed.** `runClient` and `runServer`
  were documented as failing with `constructed 0 mods`; both work. The `resourcesDir` merge
  written at 1.0.0 as an attempted fix *was* the fix, the limitation notice was left beside it,
  and the dev tasks were never re-run. The note outlived the bug by a whole release — which is
  the argument for re-testing documented limitations rather than copying them forward.

### Compatibility

No data format changed. Every `schemaVersion` is unchanged, and no code branches on the mod
version — it is recorded (`generatedByVersion` in `config.json`, `nexuscore_version` on every
audit record) and displayed, never compared.

Loading an older data directory restamps `config.json` through the usual atomic protocol, keeping
the `.bak`, and the audit chain spans both versions in one continuous hash chain. Both verified
by running the packaged jar against a `1.0.0` data directory on real servers.

## [1.0.1] — 2026-07-26 — hotfix — security fixes

**Fixes only. No new features, no data-format change.** Everyone running v1.0.0 should take
this build. It contains none of the in-progress v1.5 work — that is the entire reason the
patch lane in [ADR-0011](docs/architecture/ADR-0011.md) exists.

### Fixed

- **`/teleport` bypassed NexusCore completely.** Vanilla registers `teleport` as the real
  command and `tp` as a *redirect* to it. NexusCore took over only `tp`, so `/teleport` kept
  running as pure vanilla — **no permission check, no rate limit and no audit record** — while
  every document claimed NexusCore owned the command. Both names are now taken over.

  `ban-ip` and `pardon-ip` are separate vanilla commands for the same reason and are **still
  not covered**; they remain pure vanilla and are recorded as a known gap rather than fixed
  here, because covering them is new behaviour rather than a fix.

- **Any non-player command source was granted root.** Authorisation read
  `CommandSourceStack.getEntity()`, so anything that was not a `ServerPlayer` was treated as
  the console and given **root**. NexusCore's commands carry no vanilla `requires()`, so its own
  check is the only gate — which meant `/execute as @e[type=armor_stand] run nexus …` ran with
  full privileges, and `operatorBootstrap=false` constrained nobody.

  A source presenting a **non-player entity is now refused outright**, which closes the
  `/execute as` path, and a source with no entity is treated as the console only if it holds
  permission level 4.

  The obvious fix — read `CommandSourceStack.source`, the real issuer, which `/execute as` does
  not change — was written first and **had to be abandoned**: that field is private in vanilla
  and public only under NeoForge's access transformer, so it compiled on NeoForge and Forge and
  failed on Fabric. `getPlayer()` and `isPlayer()` are no help either, since both read `entity`.
  Permission level is the only portable discriminator: console and RCON run at 4, command blocks
  and datapack functions at 2.

  **Residual limitation:** a refused attempt made by a level-4 operator through `/execute as` is
  still *attributed* to `CONSOLE` in the audit record, because the real issuer cannot be
  recovered without that private field. The attempt is refused and recorded — the escalation is
  closed — but the name on the denial is wrong. Verified at runtime, and not fixable portably
  today.

  **Behaviour change:** command blocks and datapack functions are no longer privileged. Command
  blocks previously inherited root, which handed administration to anything that could power a
  block — on a server with `enable-command-block=true`, any level-2 builder.

  **Datapack functions are collateral, and that is a real cost.** A function's source is the
  server itself, so unlike a command block it is genuinely server-owner-controlled and was not
  an escalation. But a function and a command block are indistinguishable through public API —
  both present no entity at permission level 2 — and the only accessor that would separate them,
  `CommandSourceStack.source`, is private in vanilla and public only under NeoForge's access
  transformer, so shared code cannot read it. Denying both is the only option that closes the
  command-block hole. A datapack that drove NexusCore commands will stop working; run them from
  the console or `server.properties` startup instead.

- **A short write silently truncated an audit record.** `JsonStore.appendLine` ignored
  `FileChannel.write`'s return value, so a partial write corrupted the hash chain and still
  reported success — in the one file whose purpose is being tamper-evident. The write now loops
  until the buffer is drained and fails loudly if it stalls.

- **Every moderator could give themselves creative mode.** The `/gamemode` takeover checked
  `nexuscore.command.player.gamemode`, and the shipped `moderator` group is granted
  `nexuscore.command.player.*`. So the moment v1.0 took ownership of `/gamemode`, every
  moderator gained an ability that had previously required vanilla operator level 2 — a
  privilege escalation introduced by the takeover itself, not inherited from vanilla.

  The node moved to `nexuscore.command.staff.gamemode`, which `player.*` does not match.
  **Moving the node rather than editing the shipped group is deliberate:** default groups are
  only written when `permissions.json` is absent, so editing them would fix new installs and
  leave every existing server exposed indefinitely. Moving the node fixes both.

- **Nobody below `admin` could confirm a destructive action.**
  `nexuscore.command.core.confirm` was granted to no group, so a moderator could open a
  permanent ban and then be unable to complete it. It is now a `default` grant, which is safe
  because the confirmation token is the real authorisation — bound to actor, action, target and
  parameters, and single-use.

  **This one does need operator action on an existing server**, for the reason above: your
  `permissions.json` already exists, so the new default will not appear in it. Run
  `/nexus permission group add default nexuscore.command.core.confirm`, or add the node by hand.

### Known, not fixed here

`ban-ip` / `pardon-ip` are not taken over. A moderator can still issue an IP ban that NexusCore
neither audits nor shows in `/banlist`. Scheduled on the ladder, not in this patch.

Several further defects were found in the same sweep and are **not** in this patch, because
fixing them is behaviour change rather than repair: `/unban` and `/unmute` lift only one of
several active records, so a second ban leaves the player banned while the command reports
success; `activeBans()` double-counts; `/nexus reload` silently ignores `commandsPerMinute`.
All are recorded in `IMPLEMENTATION_STATUS.md` and scheduled on the ladder.

## [1.0.0] — 2026-07-26 — first public release: NeoForge, Fabric, and Forge

The v1.0 release ships all three loaders at once. Earlier interim tags were collapsed into
this release; nothing had been published externally, so no released version is being
rewritten.

### Mod icon

The NexusCore logo now ships in all three jars at 128x128 and is wired into each loader's
metadata: `logoFile` in `neoforge.mods.toml` and `mods.toml`, `icon` in `fabric.mod.json`.
The same image is also `pack.png`, so it appears as the resource-pack icon rather than the
blank default. It is shown in the README too.

### Resource pack metadata

Added `pack.mcmeta`. A mod jar containing `assets/` is loaded as a resource pack, and Forge
validates it with vanilla's strict codec, which requires `description` and `pack_format`.
NeoForge and Fabric never complained because NeoForge substitutes a lenient
`OPTIONAL_FORMAT` codec that makes both fields optional — so the missing file only surfaced on
Forge, as a dismissible error screen.

A mod jar is read as **both** a resource pack (format 34 on 1.21.1) and a data pack (format
48), which one `pack_format` cannot satisfy, so the file declares
`supported_formats: {min_inclusive: 34, max_inclusive: 48}` to cover both. Formats taken from
Minecraft's own `DetectedVersion`, not from memory.


### NexusCore now owns the vanilla commands it supersedes

`/ban` `/kick` `/banlist` `/pardon` `/list` `/tp` `/gamemode` are NexusCore's. Previously
they stayed vanilla and NexusCore's versions were only reachable as `/nban`, `/nkick` and so
on — an operator typing the command they had always typed silently got vanilla behaviour with
no duration, no audit entry, no styled screen and no appeal line.

- `/tp` and `/gamemode` are **rebuilt from vanilla's own argument types**, so `@a`,
  `@e[type=…]`, absolute/relative/local coordinates (`~ ~5 ~`, `^ ^ ^3`), rotation and
  `facing <location|entity [anchor]>` all still work. A structural test asserts every branch
  of that grammar survives, because losing one would not fail a compile.
- Staff teleports deliberately skip the safe-destination search: typed coordinates mean those
  coordinates. `/home`, `/warp`, `/spawn`, `/back` and `/tpa` still run the full safety check.
- Set `overrideVanillaCommands=false` to leave vanilla in charge; NexusCore's versions then
  stay on the `/n`-prefixed names.
- Takeover uses reflection on Brigadier's private child maps because it has no removal API.
  If that ever fails, NexusCore logs why and falls back to `/n`-prefixed names rather than
  half-replacing a command.

### Styled death messages

Vanilla's death broadcast is replaced with NexusCore's styled one on all three loaders. The
wording still comes from vanilla's combat tracker, so causes, mob names and item names stay
correct and translated. This turns off the `showDeathMessages` game rule to avoid sending
each death twice, which is announced at startup with the exact command to undo it. Controlled
by `styleDeathMessages`.

### Message overhaul

Every player-facing message was restyled into a consistent visual language:

- Feature-prefixed chat lines: a bold coloured tag, an arrow, then the body —
  `BAN » Banned Deathcember for 2h.: "Toxic behavior"`. Gray body text, white names,
  orange times, yellow dates.
- Full-screen ban pages: `⚠ YOU ARE TEMPORARILY BANNED ⚠`, who was banned and when
  (`17/04/2026 17:58`), the quoted reason, the unban date with a live countdown
  (`in 1h. 59min. 59sec.`), and the configurable appeal line.
- Long time forms everywhere a player reads them (`1h. 28min. 1sec.`), while commands still
  accept the compact input form (`1d12h30m`). `TimeText` renders output; `DurationParser`
  parses input; the two are deliberately separate.
- The admin panel's ban tooltips match: `Ban · Active`, the quoted reason, then
  `→ Staff / → Date / → Duration / ⌛ Expires in` detail lines.
- The ban screen is now built in exactly one place (`PunishmentMessages`) for all commands
  and all three loaders' login enforcement, so the loaders cannot drift apart.
- Operators can still reword everything via `nexuscore/messages.json` overrides.


### Added
- **Fabric build** — `NexusCore-fabric-1.0.0-1.21.1.jar`. Requires Fabric Loader 0.19.3+ and
  Fabric API.
- **Forge build** — `NexusCore-forge-1.0.0-1.21.1.jar`. Requires MinecraftForge 52.1.16+.
- ADR-0008 recording the multi-loader architecture: why a compiled `common` subproject is
  impossible (proven — Fabric jars are remapped to intermediary, so the same source yields
  different bytecode), and why Forge must stay on Gradle 8 while the others use 9.2.1.
- Regression tests per loader: `FabricEntrypointTest`, `ForgeMetadataTest`.
- Shared `MayflyFlightController`, used by both Fabric and Forge.
- A dedicated test server per loader.

### Fixed
- **Fabric did nothing in singleplayer or on a LAN world.** The mod declared a `server`
  entrypoint and implemented `DedicatedServerModInitializer`, which fires only on a
  *dedicated* server. It loaded, listed itself in the mod list, and was completely silent:
  no commands, no config directory, not one log line. Now uses `ModInitializer` with a
  `main` entrypoint, verified on a real client loading a singleplayer world.
- **Fabric `/back` did not return a player to where they died**, while NeoForge did.
  Registered `ServerLivingEntityEvents.AFTER_DEATH`.
- **Forge rejected the jar outright** with `Missing required field mandatory in dependency`:
  the dependency blocks used NeoForge's `type = "required"` spelling. Forge requires
  `mandatory = true`.

### Changed
- Repository restructured: the git root moved up to `NexusCore/`, with `neoforge/`, `fabric/`
  and `forge/` as sibling builds. History preserved via rename detection.
- Artifact naming is now `NexusCore-<loader>-<version>-<mcVersion>.jar`.
- Server logs are no longer tracked by git.

### Known loader difference
- `/fly` uses NeoForge's additive `creative_flight` attribute, but a single `mayfly` flag on
  Fabric and Forge, neither of which has an equivalent attribute. On those loaders the last
  mod to write the flag wins. The active mechanism is logged at startup.

### Earlier in v1.0 — feature completion on NeoForge

#### Added — v1.0 polish

- `/nexus teleport tp <player> [destination]` and `/tphere` — staff teleports, no warmup or
  cooldown. The permission node existed and was granted to moderators, but no command
  reached it.
- `/seen <player>` — last-seen and first-joined, from NexusCore's own identity records.
- `/list` — online players, vanish-aware.
- `/near [radius]` — nearby players in the same world, vanish-aware.
- `/nexus permission set <player> <node> allow|deny` and `/nexus permission unset` — the
  service supported direct nodes; nothing exposed them.
- `/nexus audit tail [count]` — recent audit activity without reading the file.
- **`/back` now returns you to where you died.** A death is exactly the case where no
  teleport happened, so there would otherwise be no return point.
- `DurationParser.describeElapsed` for "how long ago" rendering.
- Admin actions that change another player's access or position are now surfaced to other
  operators, rather than succeeding silently.

### Fixed — v1.0

- **`/tpa` requests never expired on a quiet server.** The expiry sweep sat behind an early
  return that only ran when a teleport warmup was pending, so with no pending teleports a
  request stayed acceptable indefinitely.
- `/seen` computed elapsed time through an obscure arithmetic trick; replaced with a tested
  helper.

### Added — the v1.0 feature set

**Configuration and messages (M1)**

- JSON settings at `<server>/nexuscore/config.json` with `schemaVersion`, range validation,
  and a validation report naming file, key, invalid value, expected form, and fallback.
- Transactional `/nexus reload`: a bad file leaves the running configuration untouched.
- `MessageService` resolving keys **server-side** so unmodified vanilla clients see real text
  rather than raw translation keys (ADR-0003). 94 keys, operator-overridable via
  `messages.json`, with `&` colour codes.

**Storage, identity, audit (M2)**

- `JsonStore`: atomic replace (`.tmp` → fsync → `ATOMIC_MOVE`), `.bak` retention, and
  quarantine-on-corruption — unreadable data is preserved and reported, never discarded.
- `PathSafety`: real-path containment that catches symlink escapes, not just `..` strings.
- `IdentityService`: UUID-first, with offline name lookup and name history.
- `AuditService`: append-only, SHA-256 hash-chained, with **write-time** redaction of IPs,
  passwords, and tokens. `/nexus audit verify` names the first broken link.

**Permissions (M3)**

- The full ADR-0004 engine: specificity scoring, direct-beats-group, deny-beats-allow, group
  weight, and a lexicographic tie-break that makes the order total and evaluation
  reproducible.
- Groups with multiple inheritance and cycle rejection. Bounded LRU decision cache.
- Mid-pattern wildcards rejected at construction.
- `/nexus permission check` **explains** a decision — matched pattern, source, group chain.
- Operator bootstrap: switchable, warned at startup, named in explain output, and overridden
  by an explicit deny.

**Command framework (M4)**

- The §12.2 nine-step pipeline as the single registration path.
- One `DurationParser` for the whole project.
- Per-player token-bucket rate limiting, bounded against UUID flooding.
- Single-use confirmation tokens bound to actor, action, target, and parameter hash.
- Aliases that fail softly, and register a collision-free `n`-prefixed form when a name is
  already owned.

**Teleport, players, moderation (M5)**

- §19.1 safe-destination search: border, build height, bounded single-chunk load, collision
  shape, fluids, harmful blocks, solid support. Refuses with a specific reason rather than
  dropping a player somewhere "close enough".
- Warmup with movement cancellation, safety re-checked at commit, cooldown.
- Homes, warps, spawn, `/back`, `/tpa`.
- heal, feed, fly, god, speed, vanish, playerinfo.
- kick, ban (confirmation required), tempban, unban, mute, unmute, warn, warnings, banlist.
  Punishments are never deleted — lifting stamps who and when.

**Admin GUI — ahead of schedule**

- A **vanilla chest-menu** admin panel that works on unmodified clients, rather than waiting
  for the M9 client mod. Dashboard, paginated player list, per-player actions, moderation and
  permission overviews, server diagnostics.
- Permission re-checked on every click. Read-only container: no code path moves an item.

**Quality**

- 178 tests, 0 failures (172 shared + 3 Fabric + 3 Forge). Checkstyle clean. Stub-marker gate clean.
- `MessageCatalogueTest` mechanically enforces §2.3 part 6 — a player-facing string without a
  catalogue key fails the build.
- ADR-0006 records the JSON-configuration decision.

### Fixed

- Services were built on `ServerAboutToStartEvent`, but `RegisterCommandsEvent` fires earlier,
  on a worker thread — the command tree would have been built against services that did not
  exist. Found on a real server. Services now build in the mod constructor.
- `/ban`, `/kick`, and `/banlist` silently resolved to vanilla's versions. NexusCore now
  registers `/nban`, `/nkick`, `/nbanlist` and says so at startup.
- `adminGuiEnabled` and `teleportCooldownSeconds` were config keys nothing read.

### Known limitations

- **No GameTests.** M5's exit condition is not met.
- **No real player has ever joined.** Every runtime check was console-driven. The GUI has
  never been rendered to a client; vanish, chat muting, and ban-at-login are wired but
  unobserved.
- No `ModuleManager`; command documentation is hand-maintained and may drift.
- No write-ahead journal for multi-file transactions.
- No economy, chat, scheduler, backups, or benchmark harness (M6–M8).
- Every performance figure in the specification remains a **target, not a measurement**.

### Added — foundation

- NeoForge MDK project for Minecraft 1.21.1, Java 21 toolchain, UTF-8 compilation.
- Pinned platform versions: NeoForge `21.1.235`, supported range `[21.1.235,)`, NeoGradle
  userdev `7.1.38`, Gradle wrapper `9.2.1`. Verified against the NeoForged Maven metadata on
  2026-07-25 and recorded in ADR-0001.
- `@Mod` entry point `com.mwtstudios.nexuscore.NexusCore`, bootstrap only.
- Exactly one command: `/nexus version`.
- `META-INF/neoforge.mods.toml` as the only metadata file, with all versions expanded from
  `gradle.properties` so metadata cannot drift from the compiled versions.
- Stub-marker gate (`stubMarkerCheck`) failing the build on `TODO`, `FIXME`, "coming soon",
  or `UnsupportedOperationException` in `src/main/java`.
- Checkstyle 10.21.1 as the combined formatting and static-analysis gate, zero tolerance.
- GitHub Actions CI running the full `build`.
- Reproducible archives: stable entry order, no embedded timestamps.
- Sources and Javadoc JARs.
- ADR-0001 (pinned versions), ADR-0002 (zero mixins), ADR-0003 (server-side message
  resolution), ADR-0004 (permission precedence), ADR-0005 (quality-gate tooling).
- `IMPLEMENTATION_STATUS.md` and `RELEASE_CHECKLIST.md`.

### Notes

- No product logic exists yet. `IMPLEMENTATION_STATUS.md` is the authoritative record.
- Performance figures in the specification are **targets, not measurements**, until the
  benchmark harness lands at M8.
