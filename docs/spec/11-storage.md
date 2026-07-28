# §11 — Storage and persistence

**Status:** re-derived. See [README](README.md) for what that means and why the authority is new
while the numbering is inherited.

**Derived from:** `JsonStore`, `PathSafety`, `JournalService`, `Transaction`, their tests, and
[ADR-0006](../architecture/ADR-0006.md) and [ADR-0013](../architecture/ADR-0013.md).

---

## §11.1 — The storage provider

NexusCore persists operator-owned state: permissions, punishments, homes, warps, configuration,
and the audit log. All of it is data an operator would be entitled to be angry about losing.

### Format and location

**§11.1-R1** — The default storage provider MUST be structured JSON documents under a single data
root (`<gameDir>/nexuscore/`). No embedded database, no binary format.

**§11.1-R2** — Every document MUST carry an integer `schemaVersion`. A change to the meaning of a
field MUST bump it and MUST ship a forward migration (§11.4).

**§11.1-R3** — All persistent state MUST live under the data root, so that a backup is one
directory and a support bundle is one archive.

> ADR-0006 decided R1 against NeoForge's `ModConfigSpec`. The reasoning was that eleven of the
> twelve configuration files named by the original specification were already JSON, so making the
> twelfth TOML would have been the duplicate framework the rule against duplication warns about.

### Single-document durability

**§11.1-R4** — Replacing one document MUST be atomic with respect to process death: a reader after
a crash MUST see either the complete previous document or the complete new one, never a partial
write.

**§11.1-R5** — The write protocol MUST be: serialise to a temporary file beside the target, force
that file's contents to disk, then move it over the target atomically.

**§11.1-R6** — An existing target MUST be copied aside before being replaced, so that a
serialisation defect cannot destroy the only copy.

**§11.1-R7** — No temporary file may be left behind by a successful write.

### Corrupt data

**§11.1-R8** — A document that exists but cannot be parsed MUST NOT be silently replaced with
defaults. It MUST be preserved, and the failure MUST name where the preserved copy is.

**§11.1-R9** — An empty document MUST be treated as corrupt, not as an empty object. A zero-byte
file is the most common shape of a failed write, and reading it as "no permissions configured" is
how a permission file becomes an empty one.

> R8 is the rule the write-ahead journal's own refusal path inherits, and the reason it refuses to
> apply staged bytes that fail verification rather than guessing.

### Path containment

**§11.1-R10** — Every path derived from configuration, a command, or a payload MUST be proven to
resolve inside an approved root before it is opened.

**§11.1-R11** — Containment MUST NOT be implemented as a string check for `..`. The candidate MUST
be resolved to a real path, following symbolic links where the path exists, and proven to be under
the root. Absolute paths MUST be refused.

### Multi-file transactions

An operation that must change two or more documents together cannot be made safe by two atomic
single-document writes: the window between them is real, and a crash inside it leaves documents
that are individually well-formed and collectively wrong. Nothing downstream can detect that
state, because the only record of the operation connecting them was in the memory of a process
that no longer exists.

**§11.1-R12** — Any operation that must change two or more documents together MUST be applied
through a write-ahead journal, such that after any crash the set is either wholly applied or
wholly unapplied.

**§11.1-R13** — There MUST be exactly one transaction mechanism. A second boundary in the same
path is how exactly-once becomes at-least-once.

**§11.1-R14** — The journal MUST record intent before applying it, and that record becoming
durable is the commit point. Before it, nothing is owed; after it, the transaction MUST be
completed even across restarts.

**§11.1-R15** — Recovery MUST run before any component reads persisted data, so that no reader can
observe a half-applied transaction.

**§11.1-R16** — Recovery MUST be idempotent. Crashing during recovery MUST leave a state that the
next recovery completes, with no entry applied twice.

**§11.1-R17** — Recovery MUST verify staged content against what the commit record committed to
before applying it, and MUST refuse rather than apply content that fails verification.

**§11.1-R18** — A refusal MUST be stable. If recovery refuses to complete a transaction, every
subsequent start MUST reach the same refusal until an operator intervenes. A refusal that holds
once and then lets the process continue is worse than no refusal, because the first start reports
danger and the second silently discards it.

**§11.1-R19** — Nothing belonging to a committed transaction may be deleted as garbage while any
part of that transaction is unreadable or unapplied.

**§11.1-R20** — Where the journal refuses and names a remediation, that remediation MUST NOT
itself produce a corrupt state if followed literally. An instruction whose obvious reading forges
the mechanism's own progress marker is a defect in the specification of the message, not operator
error.

> R18, R19 and R20 are here because the first implementation violated all three and the tests did
> not notice. R13 is the constraint escrow and settlement inherit: item custody and balances move
> through the same journal, not through a second mechanism beside it.

### Durability boundaries

**§11.1-R21** — Where a durability primitive is unavailable or degraded on a platform, the
degradation MUST be recorded where the guarantee is claimed. A durability claim that is false on
one supported platform is worse than a qualified claim on all of them.

**§11.1-R22** — Where an atomic move is unsupported by the filesystem, the fallback MUST be
reported rather than silently substituted.

---

## Conformance

As of `1.1.1`. Per the README, agreement between this document and the code proves nothing about
correctness — both were derived from the same implementation. This table exists to record the
**disagreements**, which are the only entries here that carry information.

| Requirement | Met | Evidence, or why not |
|---|---|---|
| R1–R3 | yes | `JsonStore`, ADR-0006. `StorageTest.roundTrip`. |
| R4–R7 | yes | `StorageTest.noTemporaryFileLeftBehind`, `overwriteKeepsBackup`. **Subject to R22 below.** |
| R8, R9 | yes | `StorageTest.corruptFileIsQuarantinedNotDiscarded`, `emptyFileIsCorrupt`. |
| R10, R11 | yes | `StorageTest.traversalRefused` (4 cases), `absolutePathRefused`, `symlinkEscapeRefused`, `storeRefusesEscape`. |
| R12, R14 | yes | `JournalService`, ADR-0013. `JournalTest.crashMidTransactionIsRepairedByReplay`, `crashAtEveryPointIsRecoverable` (4 cases). |
| R13 | yes | One mechanism. `Transaction.put` takes any document, so item-custody records and balances commit through the same boundary. **No caller yet** — the requirement is met by construction, not by exercise. |
| R15 | yes | `replayPending()` is called in the `storage` module's start, before it publishes its services; module ordering puts every reader after it. |
| R16 | yes | `JournalTest.replayIsIdempotent`, `crashDuringReplayIsRecoverable`. |
| R17 | yes | `JournalTest.tamperedStagingFileIsRefused`. |
| R18 | yes | `JournalTest.unreadableRecordRefusesEveryTime` — three consecutive starts. Violated in the first implementation: records were read through the quarantining reader, so the second start saw no pending work at all. |
| R19 | yes | `JournalTest.quarantinedRecordIsStillPending`, `abandonedStagingFilesAreSwept`, and the in-flight guard on the sweep. |
| R20 | yes | `JournalTest.repairGuidanceDoesNotForgeTheMarker`. |
| R21 | **partial** | Recorded in `JournalService`'s class javadoc and here — **not fixed.** `JsonStore.forceDirectory` logs at DEBUG and continues when a directory fsync fails, and opening a directory as a channel fails unconditionally on **Windows**, so every directory fsync is a silent no-op there. R21's recording duty is met; the underlying weakness is not. |
| R22 | **partial** | `JsonStore.move` falls back to a non-atomic replace with a logged warning where `ATOMIC_MOVE` is refused. The fallback is reported, so R22 is met — but on such a filesystem **R4 does not hold**, and no test covers it. |

### The evidence ceiling on R12–R20

Every crash above is simulated **in-process**, by throwing at an injected point. That establishes
the order of operations and that recovery repairs what a stopped process leaves. It does **not**
establish durability: deleting any `force()` or `forceDirectory()` call would leave the entire
suite green, because nothing in it loses power. `tools/mutate-journal.sh` proves the suite
constrains ordering; nothing in this repository currently proves it constrains durability.

Stated here rather than left implicit, because "verified by a simulated crash mid-transaction" is
the exit condition this work was written against, and a reader is entitled to know that "crash"
there means an exception and not a power cut.
