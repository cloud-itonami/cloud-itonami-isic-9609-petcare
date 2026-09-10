(ns petcare.store
  "SSoT for the pet-care actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore` -- atom of EDN. The deterministic default for
                    dev/tests/demo (no deps).

  **`DatomicStore` is not implemented here yet.** The protocol and the
  contract test are here so adding one is a drop-in; claiming a Datomic
  backend before writing it would be exactly the kind of unverified
  assertion this fleet's ADRs forbid.

  Two actuation events (grooming application, animal return) act on the
  SAME entity (a care ticket), each with its OWN history collection,
  sequence counter and dedicated double-actuation guard boolean
  (`:grooming-applied?` / `:animal-returned?`, **never a `:status`
  value** -- a status is a view, a boolean is a fact).

  The ledger stays append-only: 'which animal was screened for a lapsed
  rabies vaccination, which grooming was applied, which animal was
  handed back, on what jurisdictional basis, approved by whom' is always
  a query over an immutable log. For a business holding other people's
  animals, that log is the evidence when something goes wrong."
  (:require [petcare.registry :as registry]))

(defprotocol Store
  (ticket [s id])
  (all-tickets [s])
  (vaccination-screening-of [s ticket-id] "committed rabies-vaccination screening verdict, or nil")
  (careplan-of [s ticket-id] "committed care-plan verification, or nil")
  (ledger [s])
  (grooming-history [s])
  (return-history [s])
  (next-grooming-sequence [s jurisdiction])
  (next-return-sequence [s jurisdiction])
  (animal-already-groomed? [s ticket-id])
  (animal-already-returned? [s ticket-id])
  (commit-record! [s record])
  (append-ledger! [s fact])
  (with-tickets [s tickets]))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A self-contained care-ticket set. Each ticket exists to make exactly
  one governor rule reachable:

    ticket-1  the clean path (healthy adult, vaccination current)
    ticket-2  jurisdiction \"ATL\" -- no spec-basis on file
    ticket-3  brachycephalic + heated cage dryer -- the fatal combination
    ticket-4  rabies vaccination lapsed
    ticket-5  senior + full restraint table -- injury risk"
  []
  {:tickets
   {"ticket-1" {:id "ticket-1" :owner "Sakura Tanaka" :animal "Shiba Inu (full groom)"
                :species :dog :condition :healthy-adult
                :proposed-grooming-process :hand-dry-low-heat
                :rabies-vaccination-not-current? false
                :grooming-applied? false :animal-returned? false
                :jurisdiction "JPN" :status :intake}
    "ticket-2" {:id "ticket-2" :owner "Atlantis Doe" :animal "Poodle (trim)"
                :species :dog :condition :healthy-adult
                :proposed-grooming-process :hand-dry-low-heat
                :rabies-vaccination-not-current? false
                :grooming-applied? false :animal-returned? false
                :jurisdiction "ATL" :status :intake}
    "ticket-3" {:id "ticket-3" :owner "鈴木一郎" :animal "French Bulldog (bath and dry)"
                :species :dog :condition :brachycephalic
                :proposed-grooming-process :heated-cage-dryer
                :rabies-vaccination-not-current? false
                :grooming-applied? false :animal-returned? false
                :jurisdiction "JPN" :status :intake}
    "ticket-4" {:id "ticket-4" :owner "田中花子" :animal "Corgi (nail and bath)"
                :species :dog :condition :healthy-adult
                :proposed-grooming-process :hand-dry-low-heat
                :rabies-vaccination-not-current? true
                :grooming-applied? false :animal-returned? false
                :jurisdiction "JPN" :status :intake}
    "ticket-5" {:id "ticket-5" :owner "佐藤次郎" :animal "Senior Golden Retriever (deshed)"
                :species :dog :condition :senior
                :proposed-grooming-process :full-restraint-table
                :rabies-vaccination-not-current? false
                :grooming-applied? false :animal-returned? false
                :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- apply-grooming-process! [s ticket-id]
  (let [t (ticket s ticket-id)
        seq-n (next-grooming-sequence s (:jurisdiction t))
        result (registry/register-grooming-application ticket-id (:jurisdiction t) seq-n)]
    {:result result
     :ticket-patch {:grooming-applied? true
                    :grooming-number (get result "grooming_number")}}))

(defn- return-animal! [s ticket-id]
  (let [t (ticket s ticket-id)
        seq-n (next-return-sequence s (:jurisdiction t))
        result (registry/register-animal-return ticket-id (:jurisdiction t) seq-n)]
    {:result result
     :ticket-patch {:animal-returned? true
                    :return-number (get result "return_number")}}))

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (ticket [_ id] (get-in @a [:tickets id]))
  (all-tickets [_] (sort-by :id (vals (:tickets @a))))
  (vaccination-screening-of [_ id] (get-in @a [:vaccination-screenings id]))
  (careplan-of [_ id] (get-in @a [:careplans id]))
  (ledger [_] (:ledger @a))
  (grooming-history [_] (:groomings @a))
  (return-history [_] (:returns @a))
  (next-grooming-sequence [_ j] (get-in @a [:grooming-sequences j] 0))
  (next-return-sequence [_ j] (get-in @a [:return-sequences j] 0))
  (animal-already-groomed? [_ id] (boolean (get-in @a [:tickets id :grooming-applied?])))
  (animal-already-returned? [_ id] (boolean (get-in @a [:tickets id :animal-returned?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :ticket/upsert
      (swap! a update-in [:tickets (:id value)] merge value)

      :careplan/set
      (swap! a assoc-in [:careplans (first path)] payload)

      :vaccination-screening/set
      (swap! a assoc-in [:vaccination-screenings (first path)] payload)

      :ticket/mark-groomed
      (let [ticket-id (first path)
            {:keys [result ticket-patch]} (apply-grooming-process! s ticket-id)
            j (:jurisdiction (ticket s ticket-id))]
        (swap! a (fn [st]
                   (-> st
                       (update-in [:grooming-sequences j] (fnil inc 0))
                       (update-in [:tickets ticket-id] merge ticket-patch)
                       (update :groomings registry/append result))))
        result)

      :ticket/mark-returned
      (let [ticket-id (first path)
            {:keys [result ticket-patch]} (return-animal! s ticket-id)
            j (:jurisdiction (ticket s ticket-id))]
        (swap! a (fn [st]
                   (-> st
                       (update-in [:return-sequences j] (fnil inc 0))
                       (update-in [:tickets ticket-id] merge ticket-patch)
                       (update :returns registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-tickets [s tickets] (when (seq tickets) (swap! a assoc :tickets tickets)) s))

(defn seed-db
  "A MemStore seeded with the demo care-ticket set. The deterministic
  default -- the same shape every sibling actor exposes, which is what
  lets the 営み OS adapter be a three-line shim."
  []
  (->MemStore (atom (assoc (demo-data)
                           :careplans {} :vaccination-screenings {}
                           :ledger [] :grooming-sequences {} :groomings []
                           :return-sequences {} :returns []))))
