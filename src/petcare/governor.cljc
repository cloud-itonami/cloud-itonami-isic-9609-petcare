(ns petcare.governor
  "The **Pet Care Governor** -- an independent censor, a different system
  than the advisor it censors. It never asks the advisor whether a
  proposal is safe; it recomputes what it can from the ticket the
  operator already recorded, and holds when it cannot.

  ## What is absent from the vocabulary, and why that matters more here

  `allowed-ops` is the whole vocabulary -- five ops. **No op sedates an
  animal, diagnoses it, treats it, or ends its life.** Those are not
  gated; they are absent. A grooming/boarding operator is not a
  veterinarian, and the difference between 'gated' and 'absent' is the
  difference between a permission an operator can be argued into and a
  capability that does not exist.

  This is the strongest form of the cluster's bailment pattern: 9601
  holds a garment, 9522 an appliance, 9523 a shoe, 4520-carwash a car --
  **this one holds a living being that can be hurt or killed by the
  process applied to it.**

  ## The checks

    1. Spec basis missing        -- a jurisdiction with no animal-handling
                                    basis on file cannot be operated in. HARD.
    2. Evidence incomplete       -- for either actuation, the jurisdiction's
                                    required records (owner consent, rabies
                                    certificate, health record, process
                                    record) must actually be present.
    3. Rabies vaccination lapsed -- reported by this proposal OR already on
                                    file. HARD, un-overridable. This is a
                                    public-health duty, not a house rule.
    4. Process forbidden by      -- recomputed from the animal's own recorded
       condition                    condition and its own proposed process
                                    (`registry/grooming-process-forbidden-by-
                                    condition?`). **Set membership -- no
                                    threshold to lower.** Heat stress in a
                                    cage dryer is a documented cause of
                                    grooming-salon deaths in brachycephalic
                                    dogs; this table is why that path holds.
    5/6. Already groomed /       -- double-actuation guards off dedicated
       already returned             booleans, never a `:status` value.
    7. Scope excluded            -- the advisor's own prose reaching for
                                    sedation, diagnosis, treatment or
                                    euthanasia.

  ## high-stakes is a set of OPS, not of advisor-reported stakes

  Following `cloud-itonami-isic-9601` and `-4520-carwash` rather than
  9522/9523: a permanent invariant must not depend on the censored
  party's own `:stake` report. (superproject ADR-2800004000 records that
  this fleet carries both vocabularies.)"
  (:require [clojure.string :as str]
            [petcare.facts :as facts]
            [petcare.registry :as registry]
            [petcare.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction."
  #{:ticket/intake :careplan/verify :vaccination/screen
    :actuation/apply-grooming-process :actuation/return-animal})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean."
  #{:actuation/apply-grooming-process :actuation/return-animal})

(def scope-excluded-terms
  "Case-insensitive substrings marking a proposal as reaching for a
  permanently out-of-scope decision. Every one of these is a
  veterinary act."
  ["sedate" "sedation" "sedated" "鎮静" "麻酔"
   "diagnos" "診断" "prescrib" "処方"
   "euthan" "安楽死" "surgical" "外科"
   "treatment administered" "投薬"])

;; ----------------------------- checks -----------------------------

(defn- op-not-allowed-violations
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこの actor の語彙に存在しない")}]))

(defn- spec-basis-violations
  [{:keys [op]} proposal]
  (when (contains? #{:careplan/verify
                     :actuation/apply-grooming-process
                     :actuation/return-animal} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basis(動物取扱業/狂犬病予防)の引用が無い提案は運営基準として扱えない"}]))))

(defn- evidence-incomplete-violations
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/apply-grooming-process :actuation/return-animal} op)
    (let [t (store/ticket st subject)
          plan (store/careplan-of st subject)]
      (when-not (and plan
                     (facts/required-evidence-satisfied?
                      (:jurisdiction t) (:checklist plan)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(飼主同意記録/狂犬病予防注射証明書/健康状態記録/施術内容記録)が充足していない"}]))))

(defn- vaccination-not-current-violations
  "A lapsed rabies vaccination -- reported by THIS proposal (e.g. a
  `:vaccination/screen` that just found it lapsed) or already on file --
  is a HARD, un-overridable hold. Evaluated UNCONDITIONALLY so the
  screening op can hold on its own finding.

  **This is a public-health duty, not a house rule.** No confidence
  level and no phase relaxes it."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (true? (get-in proposal [:value :rabies-vaccination-not-current?]))
        ticket-id (when (contains? #{:vaccination/screen
                                     :actuation/apply-grooming-process
                                     :actuation/return-animal} op)
                    subject)
        hit-on-file? (and ticket-id
                          (true? (:rabies-vaccination-not-current?
                                  (store/vaccination-screening-of st ticket-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :rabies-vaccination-not-current
        :detail "狂犬病予防注射が最新でない状態で動物を預かる提案は進められない"}])))

(defn- process-forbidden-by-condition-violations
  "For `:actuation/apply-grooming-process`, INDEPENDENTLY recompute
  whether the animal's own proposed process is forbidden by its own
  recorded condition. Inputs are permanent ground-truth fields already
  on the ticket -- no proposal inspection at all."
  [{:keys [op subject]} st]
  (when (= op :actuation/apply-grooming-process)
    (let [t (store/ticket st subject)]
      (when (registry/grooming-process-forbidden-by-condition? t)
        [{:rule :grooming-process-forbidden-by-condition
          :detail (str subject " の提案施術(" (:proposed-grooming-process t)
                       ")が記録された状態" (:condition t) "の禁止施術に含まれている")}]))))

(defn- already-groomed-violations
  [{:keys [op subject]} st]
  (when (= op :actuation/apply-grooming-process)
    (when (store/animal-already-groomed? st subject)
      [{:rule :already-groomed :detail (str subject " は既に施術済み")}])))

(defn- already-returned-violations
  [{:keys [op subject]} st]
  (when (= op :actuation/return-animal)
    (when (store/animal-already-returned? st subject)
      [{:rule :already-returned :detail (str subject " は既に返却済み")}])))

(defn- scope-exclusion-violations
  [proposal]
  (let [text (str (:summary proposal) " " (:rationale proposal))
        lower (str/lower-case text)]
    (when-let [hit (first (filter #(str/includes? lower (str/lower-case (str %)))
                                  scope-excluded-terms))]
      [{:rule :scope-excluded
        :detail (str "恒久的にスコープ外の獣医行為に触れる文言を含む: " hit)}])))

(defn check
  "Censors a PetCareAdvisor proposal against the governor rules."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (vaccination-not-current-violations request proposal st)
                           (process-forbidden-by-condition-violations request st)
                           (already-groomed-violations request st)
                           (already-returned-violations request st)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:op request)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  [request context verdict]
  {:t           :governor-hold
   :op          (:op request)
   :actor       (:actor-id context)
   :subject     (:subject request)
   :disposition :hold
   :basis       (mapv :rule (:violations verdict))
   :violations  (:violations verdict)
   :confidence  (:confidence verdict)})
