(ns petcare.phase
  "Phase 0->3 staged rollout -- the pet-care analog of
  `cloud-itonami-isic-9601`'s `laundry.phase`.

    Phase 0  read-only         -- no writes, still governor-gated.
    Phase 1  assisted-intake   -- grooming-ticket intake allowed, every write
                                  needs human approval.
    Phase 2  assisted-verify   -- adds care-plan verification +
                                  rabies-vaccination screening writes,
                                  still approval.
    Phase 3  supervised auto   -- governor-clean, high-confidence
                                  `:ticket/intake` (no capital risk yet)
                                  may auto-commit.
                                  `:actuation/apply-grooming-process` and
                                  `:actuation/return-animal` NEVER
                                  auto-commit, at any phase.

  Those two actuations are deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- **a permanent structural fact, not
  a rollout milestone still to come**.

  An animal left for grooming is a bailment, but it is a bailment of a
  **living being**: the owner keeps ownership while the operator holds
  possession, and unlike a garment or a car, the thing held can be hurt
  or killed by the process applied to it. Applying a real grooming
  process to a real animal and handing a real animal back are therefore
  irreversible in a stronger sense than anywhere else in this cluster,
  and both are always a human call. `petcare.governor`'s high-stakes set
  asserts the same invariant independently -- two layers, not one, agree.

  `:vaccination/screen` is likewise never auto-eligible, matching the
  posture every sibling's screening op has (9601 solvent handling, 9522
  refrigerant handling, 9523 brand authenticity)."
  (:require [clojure.set :as set]))

(def read-ops #{})

(def write-ops #{:ticket/intake :careplan/verify :vaccination/screen
                 :actuation/apply-grooming-process :actuation/return-animal})

;; NOTE the invariant: the two `:actuation/*` ops are members of
;; `write-ops` (governor-gated like any write) but are NEVER members of
;; any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                :auto #{}}
   1 {:label "assisted-intake" :writes #{:ticket/intake}   :auto #{}}
   2 {:label "assisted-verify"
      :writes #{:ticket/intake :careplan/verify :vaccination/screen}
      :auto   #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:ticket/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - the two actuations are never auto-eligible at any phase, so they
    always escalate once the governor clears them (or hold if it does not)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Pet Care Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))

(defn auto-eligible-ops
  "Every op that any phase may auto-commit. Used by the tests to assert
  the permanent invariant in one place."
  []
  (reduce set/union #{} (map :auto (vals phases))))
