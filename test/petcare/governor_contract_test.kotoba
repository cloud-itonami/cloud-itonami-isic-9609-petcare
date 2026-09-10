(ns petcare.governor-contract-test
  "Every HARD check must actually fire, and each must fire for its own
  reason. A gate that cannot be shown to refuse is theatre."
  (:require [clojure.test :refer [deftest is testing]]
            [petcare.governor :as governor]
            [petcare.registry :as registry]
            [petcare.facts :as facts]
            [petcare.store :as store]))

(def ctx {:actor-id "op-1" :actor-role :groomer :phase 3})

(defn- db [] (store/seed-db))

(defn- clean-proposal [op subject]
  {:op op :summary "ok" :rationale "ok"
   :cites ["動物の愛護及び管理に関する法律 第10条（第一種動物取扱業の登録）"]
   :effect :noop :value {:ticket-id subject} :confidence 0.9})

(defn- rules-of [verdict] (set (map :rule (:violations verdict))))

;; ----------------------------- what is absent, not gated -----------------------------

(deftest no-veterinary-act-exists-in-the-vocabulary
  (doseq [absent [:actuation/sedate-animal :actuation/administer-treatment
                  :actuation/diagnose :actuation/euthanize]]
    (is (not (contains? governor/allowed-ops absent))
        "these are absent from the vocabulary, not merely gated")))

(deftest an-op-outside-the-allowlist-is-hard-held
  (let [v (governor/check {:op :actuation/sedate-animal :subject "ticket-1"}
                          ctx (clean-proposal :actuation/sedate-animal "ticket-1") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :op-not-allowed))))

(deftest prose-describing-a-veterinary-act-is-hard-held
  ;; the op is legitimate; the prose is not
  (let [p (assoc (clean-proposal :ticket/intake "ticket-1")
                 :rationale "animal will be sedated before the bath")
        v (governor/check {:op :ticket/intake :subject "ticket-1"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :scope-excluded))))

;; ----------------------------- spec basis -----------------------------

(deftest a-proposal-with-no-cites-is-hard-held
  (let [p (assoc (clean-proposal :careplan/verify "ticket-2") :cites [])
        v (governor/check {:op :careplan/verify :subject "ticket-2"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :no-spec-basis))))

(deftest an-unseeded-jurisdiction-can-never-satisfy-its-evidence
  (is (nil? (facts/required-evidence-satisfied? "ATL" ["anything"]))
      "a missing spec-basis is not 'no requirements'"))

(deftest every-seeded-jurisdiction-requires-a-rabies-certificate
  ;; the public-health duty is not optional in any seeded jurisdiction
  (doseq [iso3 (keys facts/spec-basis-table)]
    (is (some #(re-find #"(?i)rabies|狂犬病|Tollwut" %) (facts/required-evidence iso3))
        (str iso3 " must require a vaccination certificate"))))

;; ----------------------------- the fatal combination -----------------------------

(deftest a-heated-dryer-on-a-brachycephalic-dog-is-recomputed-and-held
  ;; ticket-3. The proposal is clean and well-cited; the hold comes only
  ;; from the ticket's own two ground-truth fields.
  (let [v (governor/check {:op :actuation/apply-grooming-process :subject "ticket-3"}
                          ctx (clean-proposal :actuation/apply-grooming-process "ticket-3") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :grooming-process-forbidden-by-condition)))
  (testing "and it is set membership, so there is no threshold to lower"
    (is (registry/grooming-process-forbidden-by-condition?
         {:condition :brachycephalic :proposed-grooming-process :heated-cage-dryer}))
    (is (not (registry/grooming-process-forbidden-by-condition?
              {:condition :healthy-adult :proposed-grooming-process :heated-cage-dryer})))))

(deftest a-restraint-table-on-a-senior-dog-is-held
  (let [v (governor/check {:op :actuation/apply-grooming-process :subject "ticket-5"}
                          ctx (clean-proposal :actuation/apply-grooming-process "ticket-5") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :grooming-process-forbidden-by-condition))))

(deftest an-unrecorded-condition-forbids-nothing-and-that-is-known
  ;; Stated rather than hidden: the table is keyed by recorded condition,
  ;; so an animal whose condition was never recorded passes this check.
  ;; docs/adr/0001 records this as a deliberate, revisitable gap.
  (is (not (registry/grooming-process-forbidden-by-condition?
            {:condition nil :proposed-grooming-process :heated-cage-dryer}))))

;; ----------------------------- vaccination currency -----------------------------

(deftest a-lapsed-vaccination-holds-from-the-screening-ops-own-finding
  (let [p (assoc (clean-proposal :vaccination/screen "ticket-4")
                 :value {:ticket-id "ticket-4" :rabies-vaccination-not-current? true})
        v (governor/check {:op :vaccination/screen :subject "ticket-4"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :rabies-vaccination-not-current))))

(deftest a-lapsed-vaccination-on-file-holds-an-actuation
  (let [st (db)]
    (store/commit-record! st {:effect :vaccination-screening/set :path ["ticket-1"]
                              :payload {:ticket-id "ticket-1"
                                        :rabies-vaccination-not-current? true}})
    (let [v (governor/check {:op :actuation/apply-grooming-process :subject "ticket-1"}
                            ctx (clean-proposal :actuation/apply-grooming-process "ticket-1") st)]
      (is (:hard? v))
      (is (contains? (rules-of v) :rabies-vaccination-not-current)))))

(deftest no-confidence-level-clears-a-lapsed-vaccination
  (let [st (db)
        p (assoc (clean-proposal :vaccination/screen "ticket-4")
                 :value {:rabies-vaccination-not-current? true}
                 :confidence 1.0)
        v (governor/check {:op :vaccination/screen :subject "ticket-4"} ctx p st)]
    (is (:hard? v) "a public-health duty is not a confidence question")))

;; ----------------------------- evidence -----------------------------

(deftest an-actuation-without-a-verified-careplan-is-hard-held
  (let [v (governor/check {:op :actuation/return-animal :subject "ticket-1"}
                          ctx (clean-proposal :actuation/return-animal "ticket-1") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :evidence-incomplete))))

(deftest a-complete-careplan-clears-the-evidence-check
  (let [st (db)]
    (store/commit-record! st {:effect :careplan/set :path ["ticket-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (let [v (governor/check {:op :actuation/return-animal :subject "ticket-1"}
                            ctx (clean-proposal :actuation/return-animal "ticket-1") st)]
      (is (not (contains? (rules-of v) :evidence-incomplete))))))

;; ----------------------------- double actuation -----------------------------

(deftest the-same-animal-cannot-be-groomed-or-returned-twice
  (let [st (db)]
    (store/commit-record! st {:effect :careplan/set :path ["ticket-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (store/commit-record! st {:effect :ticket/mark-groomed :path ["ticket-1"]})
    (store/commit-record! st {:effect :ticket/mark-returned :path ["ticket-1"]})
    (is (contains? (rules-of (governor/check
                              {:op :actuation/apply-grooming-process :subject "ticket-1"}
                              ctx (clean-proposal :actuation/apply-grooming-process "ticket-1") st))
                   :already-groomed))
    (is (contains? (rules-of (governor/check
                              {:op :actuation/return-animal :subject "ticket-1"}
                              ctx (clean-proposal :actuation/return-animal "ticket-1") st))
                   :already-returned))))

;; ----------------------------- high stakes -----------------------------

(deftest high-stakes-is-decided-on-the-op-not-the-advisors-self-report
  (let [st (db)]
    (store/commit-record! st {:effect :careplan/set :path ["ticket-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (testing "an actuation escalates even when the advisor declares no stake"
      (let [p (dissoc (clean-proposal :actuation/return-animal "ticket-1") :stake)
            v (governor/check {:op :actuation/return-animal :subject "ticket-1"} ctx p st)]
        (is (:high-stakes? v))
        (is (:escalate? v))
        (is (not (:ok? v)))))
    (testing "and intake does not escalate on stakes"
      (let [v (governor/check {:op :ticket/intake :subject "ticket-1"} ctx
                              (clean-proposal :ticket/intake "ticket-1") st)]
        (is (not (:high-stakes? v)))
        (is (:ok? v))))))
