# ADR-0001: A pet-care actor as a satellite of ISIC 9609

**Status**: accepted
**Date**: 2026-08-08
**Superproject record**: `com-junkawasaki/root` ADR-2800004000 §3

## Context

ISIC 9609's `includes` list names "pet care services (grooming, boarding,
training)" — read from this workspace's spec mirror
`cloud-itonami/org-un-isic` (`data/classes/9609.json`), not from memory.

Two things already existed and neither covers it:

1. **`cloud-itonami-isic-9609`** implements the *generic personal-service
   referral* half of the class. Its five-op vocabulary contains no animal
   and no procedure. When it was connected to the 営み OS on 2026-08-08,
   a test was written specifically to stop the misreading that connecting
   it delivered pet care (`personal-service-is-not-the-pet-grooming-vertical`).
2. **`cloud-itonami-isco-5164`** ("Pet Groomers and Animal Care Workers")
   is an **occupation** blueprint with a grooming-support-robot premise,
   and it is **not standard-form** — measured 2026-08-08: no `phase.cljc`,
   no `operation.cljc`, no `store/seed-db`. It cannot be connected to the
   OS as-is, and it models the worker rather than the business.

## Decision

### 1. A satellite of 9609, in standard form

`cloud-itonami-isic-9609-petcare`, following `-facade`/8129 and
`-cryptoexchange`/6611. The parent keeps generic referral; this repo takes
pet care. Standard-form from the first commit (single namespace,
`phase/{read,write}-ops`, `operation/build` on a langgraph StateGraph,
`store/seed-db`, `governor`, all `.cljc`), so connecting it later is a
shim plus a declaration.

### 2. The bailment is of a living being, and the design says so

This cluster's proven shape is intake → verify → screen → actuate →
return, with the two actuations never auto-committing. **Here the held
thing can be hurt or killed by the process applied to it**, which is a
difference in kind rather than degree from a garment, an appliance, a shoe
or a car. Both `phase` and `governor/high-stakes` assert the invariant,
and the ns docstrings state the reason rather than leaving it implicit.

### 3. Veterinary acts are absent, not gated

No op sedates, diagnoses, treats or euthanizes. Additionally
`scope-excluded-terms` scans the advisor's own prose, so a legitimate op
cannot carry a veterinary act through in its rationale. The test suite
asserts both the absence and the prose scan.

Why absence rather than a gate: a gate is a permission an operator can be
argued into. For an act that requires a licensed veterinarian, the right
answer is that the capability does not exist in this actor's vocabulary.

### 4. Two regulatory regimes, carried separately

`facts/spec-basis-table` holds **both** the trade registration
(`:legal-basis`) and the rabies vaccination duty
(`:public-health-basis`). They are different instruments binding different
parties — the operator and the owner respectively — and collapsing them
into one field would make it impossible to say which one a hold rests on.
A test asserts that every seeded jurisdiction requires a vaccination
certificate in its evidence list.

### 5. The condition/process table is set membership

`registry/condition-forbidden-processes` is recomputed from the animal's
own two ground-truth fields. Heat stress in a cage dryer is a documented
cause of grooming-salon deaths in brachycephalic dogs; a senior or
arthritic animal is injured on a full restraint table; a medicated dip on
broken skin is a chemical burn. **There is no threshold to lower, only a
set to belong to** — the same reason 9601's care-label check and
`4520-carwash`'s finish check are strong.

### 6. `high-stakes` is a set of OPS; the request key is `:subject`

Following 9601 and `4520-carwash` rather than 9522/9523: a permanent
invariant must not depend on the censored party's self-reported `:stake`.
A test asserts an actuation still escalates with `:stake` removed.

And the request key is `:subject`, so this repo does not add to the
fleet debt recorded in ADR-2800004000 (5229 `:target-id`, 4759
`:store-id`, 8121/8129 `:site-id` all need a translation line in their
OS adapters).

## Consequences

### What this buys

- The pet-care half of ISIC 9609 has an implementation, and the claim is
  checkable: 5 commits and 6 distinct governor holds, each naming its own
  rule.
- The misreading the parent's test was written to stop now has a real
  answer instead of only a denial.

### What it costs, stated rather than hidden

- **`DatomicStore` does not exist here.** Only `MemStore`.
- **An unrecorded condition forbids nothing.** The table is keyed by the
  recorded condition, so an animal whose condition was never entered
  passes that check. A test asserts this explicitly so it is visible
  rather than latent. **Open question for a future revision: should an
  unknown condition hold instead of pass?** Holding by default is safer
  for the animal but would block every intake before a health record
  exists, so the answer is not obviously "yes" — it needs an operator's
  workflow to decide, not a unilateral edit.
- **Not on the shared surface.** Being forkable and being declared in
  `os.edn` are different claims; this repo makes only the first.
- **The condition vocabulary is small** (five values). Extending it is a
  data change.
