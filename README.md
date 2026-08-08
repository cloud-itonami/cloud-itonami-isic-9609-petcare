# cloud-itonami-isic-9609-petcare

Open Business Blueprint for **pet care services (grooming, boarding)** —
a role-suffix satellite of
[`cloud-itonami-isic-9609`](https://github.com/cloud-itonami/cloud-itonami-isic-9609)
(ISIC 9609: other personal service activities n.e.c.), following the same
satellite pattern this fleet uses for `-facade` under 8129 and
`-cryptoexchange` under 6611.

## Why this repo exists, measured rather than assumed

**ISIC 9609 explicitly includes "pet care services (grooming, boarding,
training)."** That is the `includes` list of class 9609 in this
workspace's own spec mirror (`cloud-itonami/org-un-isic`,
`data/classes/9609.json`), read on 2026-08-08 — not an inference.

The parent actor `cloud-itonami-isic-9609` implements the **generic
personal-service referral** half of that class (client intake → service-plan
verification → background check → referral finalization). Its vocabulary
contains no animal and no procedure; `os_test.cljc` in
`network-awai/cloud-itonami` asserts that absence directly
(`personal-service-is-not-the-pet-grooming-vertical`). So the pet-care
half of 9609 had no implementation. This repo is that half.

### Relationship to `cloud-itonami-isco-5164`

`cloud-itonami-isco-5164` ("Pet Groomers and Animal Care Workers") is an
**occupation** blueprint with a grooming-support-robot premise, and it is
**not standard-form** — no `phase.cljc`, no `operation.cljc`, no
`store/seed-db` (measured 2026-08-08). It models the worker; this repo
models the **business**. Neither re-promotes the other, and this repo
does not reuse its vocabulary.

## The strongest form of this cluster's bailment pattern

9601 holds a garment. 9522 holds an appliance. 9523 holds a shoe.
`4520-carwash` holds a car. **This one holds a living being that can be
hurt or killed by the process applied to it.**

So the two real-world acts — applying a grooming process to a real
animal, handing a real animal back — are irreversible in a stronger
sense than anywhere else in the cluster, and neither auto-commits at any
phase. Two independent layers say so: `petcare.phase`'s `:auto` sets and
`petcare.governor/high-stakes`.

## What is absent from the vocabulary, not merely gated

`petcare.governor/allowed-ops` is the whole vocabulary — five ops.
**No op sedates an animal, diagnoses it, treats it, or ends its life.**

| Not here | Whose it is |
|---|---|
| sedation | a veterinarian |
| diagnosis / prescription / treatment | a veterinarian |
| euthanasia | a veterinarian, with the owner |

The difference between "gated" and "absent" is the difference between a
permission an operator can be argued into and a capability that does not
exist. The governor additionally scans the advisor's own prose, so a
legitimate op cannot smuggle a veterinary act through in its rationale.

## Two regimes, and they are different things

`petcare.facts` carries both, because an operator needs both:

- **the trade registration** — who may hold other people's animals for
  reward (JPN 動物愛護管理法 第10条 第一種動物取扱業登録 / USA Animal
  Welfare Act / DEU TierSchG §11)
- **the rabies vaccination duty** — a public-health rule binding the
  owner, which the operator must verify before accepting the animal
  (JPN 狂犬病予防法 第5条)

A jurisdiction outside that table has **no basis on file**; the advisor
says so honestly with empty `:cites` and the governor holds. Adding a
jurisdiction is a data addition, never a code change.

## The check that exists because animals die of it

`registry/condition-forbidden-processes` is a table of **physical
incompatibility**, recomputed from the animal's own recorded condition
and its own proposed process — no proposal inspection, no threshold, only
set membership:

| Recorded condition | Forbidden |
|---|---|
| `:brachycephalic` (短頭種) | `:heated-cage-dryer`, `:forced-air-high-heat` |
| `:senior` | `:full-restraint-table`, `:heated-cage-dryer` |
| `:skin-lesion` | `:medicated-dip`, `:stripping-knife` |
| `:arthritic` | `:full-restraint-table` |

Heat stress in a cage dryer is a documented cause of grooming-salon
deaths in brachycephalic dogs, whose airways cannot shed heat. That is
why this table is set membership and not a confidence score.

## Run it

```bash
clojure -M:dev:run     # 5 commits and 6 distinct governor holds
clojure -M:dev:test    # 33 tests / 93 assertions
```

The demo ledger ends like this — every hold names its own rule:

```
:committed     :ticket/intake                     ticket-1
:committed     :careplan/verify                   ticket-1
:committed     :vaccination/screen                ticket-1
:committed     :actuation/apply-grooming-process  ticket-1
:committed     :actuation/return-animal           ticket-1
:governor-hold :careplan/verify                   ticket-2 [:no-spec-basis]
:governor-hold :actuation/apply-grooming-process   ticket-3 [:evidence-incomplete :grooming-process-forbidden-by-condition]
:governor-hold :vaccination/screen                ticket-4 [:rabies-vaccination-not-current]
:governor-hold :actuation/apply-grooming-process   ticket-5 [:evidence-incomplete :grooming-process-forbidden-by-condition]
:governor-hold :actuation/sedate-animal            ticket-1 [:op-not-allowed :scope-excluded]
:governor-hold :actuation/apply-grooming-process   ticket-1 [:already-groomed]
```

## Honest state

- **`DatomicStore` is not implemented.** Only `MemStore`. The contract
  test is written so adding one is a drop-in.
- **An unrecorded condition forbids nothing.** The table is keyed by the
  recorded condition, so an animal whose condition was never entered
  passes that check. `governor_contract_test.clj` asserts this explicitly
  rather than hiding it; `docs/adr/0001-architecture.md` records it as a
  revisitable gap (should an unknown condition hold instead?).
- **Not connected to the 営み OS yet.** Standard-form, so the adapter
  will be a three-line shim — but a declaration in `os.edn` is a
  separate, deliberate step. Being forkable and being on the shared
  surface are two different claims.
- **No robotics.** `blueprint.edn` says `:robotics false`. Physical
  handling is the groomer's; this actor coordinates records around it.

## License

AGPL-3.0-or-later. See `LICENSE`.
