(ns petcare.registry
  "Pure, backend-agnostic record drafting + the ground-truth
  recomputation the Pet Care Governor relies on.

  Nothing here reads a proposal. Everything takes facts already
  recorded on the animal's ticket, which is exactly why the governor can
  use it to check the advisor instead of believing it."
  (:require [kotoba.lang.text :as str]))

;; ----------------------------- condition / process compatibility -----------------------------

(def condition-forbidden-processes
  "Recorded animal condition -> grooming processes that must never be
  applied to it.

  **This table exists because animals die of these.** Heat stress in a
  cage dryer is the documented cause of grooming-salon deaths in
  brachycephalic (短頭種) dogs, whose airways cannot shed heat; a full
  restraint table is how a senior or arthritic animal gets injured; a
  medicated dip on broken skin is a chemical burn.

  It is a table of physical incompatibility, not a policy knob. A caller
  cannot loosen it by lowering a threshold, because there is no
  threshold -- only set membership."
  {:brachycephalic #{:heated-cage-dryer :forced-air-high-heat}
   :senior         #{:full-restraint-table :heated-cage-dryer}
   :skin-lesion    #{:medicated-dip :stripping-knife}
   :arthritic      #{:full-restraint-table}
   :healthy-adult  #{}})

(defn grooming-process-forbidden-by-condition?
  "Independently recompute, from the two permanent ground-truth fields
  already on the ticket, whether the animal's own proposed process is
  forbidden by its own recorded condition. Needs no proposal at all."
  [{:keys [proposed-grooming-process condition]}]
  (boolean (and proposed-grooming-process condition
                (contains? (get condition-forbidden-processes condition #{})
                           proposed-grooming-process))))

;; ----------------------------- record drafting -----------------------------

(defn- seq->number [prefix jurisdiction seq-n]
  (str prefix "-" (str/upper (or jurisdiction "XXX")) "-"
       (str/join (repeat (max 0 (- 4 (count (str (inc seq-n))))) "0"))
       (inc seq-n)))

(defn register-grooming-application
  "Draft the grooming-application record. Pure: same inputs -> same record."
  [ticket-id jurisdiction seq-n]
  {"grooming_number" (seq->number "GRM" jurisdiction seq-n)
   "ticket_id" ticket-id
   "jurisdiction" jurisdiction})

(defn register-animal-return
  "Draft the animal-return record. Pure: same inputs -> same record."
  [ticket-id jurisdiction seq-n]
  {"return_number" (seq->number "RET" jurisdiction seq-n)
   "ticket_id" ticket-id
   "jurisdiction" jurisdiction})

(defn append [coll record] (conj (vec coll) record))
