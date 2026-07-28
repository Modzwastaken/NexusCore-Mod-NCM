# NexusCore specification

Normative requirements for NexusCore. Where this directory and the code disagree, one of them
is a defect, and the conformance section of the relevant document says which.

## What these documents are, and what they are not

NexusCore was built against an external specification that this repository has never contained.
Every `§` reference across the codebase — `§11.1` in `JsonStore`, `§15` in `PathSafety`, `§9` in
the permission engine, `§20.4`'s status vocabulary — points into that document. It is not here,
and it is not recoverable.

That left the project in an awkward position it had been quietly living with: the numbered rules
it cites as authority could not be read by anyone reading the repository. A `§11.1` reference in
a javadoc looked like a citation and functioned as an assertion.

**The owner's ruling (2026-07-27) is to re-derive: the sections NexusCore actually depends on
become NexusCore's own published specification, with the provenance change stated rather than
hidden.** These documents are that re-derivation.

**So the numbering is inherited, and the authority is new.** A section here keeps the number the
codebase already cites, so existing references stay valid. What it says is recovered from the
implementation, its tests, and the architecture decisions that produced them — not transcribed
from the original. Where the original said something these documents do not, that has been lost;
where these documents say something the original did not, it is written down as new.

**This means a re-derived section cannot be used to prove the implementation is correct.** It was
derived *from* the implementation, so agreement between the two is circular by construction and
proves nothing. What it is good for is the opposite direction: fixing the requirement in place so
that future changes have something to be measured against, and so a reader can see what NexusCore
believes it owes without taking a javadoc's word for it.

Requirements use MUST, MUST NOT, SHOULD and MAY in the ordinary sense. Each is numbered so tests,
ADRs and status rows can cite it precisely.

## Documents

| Section | Covers |
|---|---|
| [§11 — Storage and persistence](11-storage.md) | The storage provider, atomic replacement, corrupt-data handling, path containment, and the write-ahead journal for multi-file transactions |

The section numbers are not contiguous, and that is not an oversight — a section is written when
the work it governs is built, and `IMPLEMENTATION_STATUS.md` is the only place that records what
is not yet written (§2.4). The absence of a section here is not a claim that the requirement does
not exist; it is a claim that this repository has not recovered it.

## Conformance

Every document ends with a conformance section stating, per requirement, whether the code meets
it and what proves it. **A requirement the code does not meet is recorded there rather than
softened here.** A specification edited to match whatever the code happens to do is not a
specification.
