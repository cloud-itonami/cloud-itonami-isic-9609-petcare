(ns petcare.advisor
  "The **contained intelligence**: a PetCareAdvisor that drafts proposals
  and nothing else. Every proposal it produces is censored by
  `petcare.governor` and gated by `petcare.phase` before anything
  reaches the SSoT.

  **The advisor reports missing basis honestly.** When a jurisdiction has
  no spec-basis on file it emits empty `:cites` and does NOT raise its
  confidence -- fabricating an animal-handling requirement would make the
  governor's spec-basis gate unreachable, which is worse than a hold.

  **The advisor never proposes a veterinary act**, because there is no op
  for one. If a real LLM advisor tries to describe one in prose, the
  governor's scope-exclusion check catches it on the way through."
  (:require [petcare.facts :as facts]
            [petcare.store :as store]))

(defprotocol Advisor
  (-advise [a store request] "Draft a proposal for this request."))

(defn trace
  [request proposal]
  {:t          :advisor-proposed
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})

;; ----------------------------- proposal generators -----------------------------

(defn- propose-intake
  "`:patch` arrives at the TOP level of the request (the OS shim merges
  the envelope's payload into the request map)."
  [_st {:keys [subject patch confidence]}]
  {:op :ticket/intake
   :summary (str subject " の預り受付票を起票")
   :rationale "受付の記録行為。動物に触れる行為も獣医行為も含まない。"
   :cites (vec (keys patch))
   :effect :ticket/upsert
   :value (merge {:id subject} patch)
   :confidence (or confidence 0.9)})

(defn- propose-careplan [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        iso3 (:jurisdiction t)
        sb (facts/spec-basis iso3)]
    (if-not sb
      {:op :careplan/verify
       :summary (str iso3 " の公式spec-basis(動物取扱業/狂犬病予防)が見つかりません")
       :rationale (str iso3 " は台帳に無い法域。動物取扱の要件を推測で作らない。")
       :cites []
       :effect :careplan/set
       :value {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :confidence 0.2}
      {:op :careplan/verify
       :summary (str subject " の預りケア計画を " iso3 " 基準で検証")
       :rationale (str (:legal-basis sb) " および " (:public-health-basis sb)
                       " に基づく必要書類の充足確認。")
       :cites [(:legal-basis sb) (:public-health-basis sb) (:provenance sb)]
       :effect :careplan/set
       :value {:jurisdiction iso3
               :checklist (facts/required-evidence iso3)
               :spec-basis (:provenance sb)}
       :confidence 0.88})))

(defn- propose-vaccination-screen [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        lapsed? (true? (:rabies-vaccination-not-current? t))]
    {:op :vaccination/screen
     :summary (str subject " の狂犬病予防注射の現況を確認"
                   (if lapsed? " -- 未接種/失効を検出" " -- 有効"))
     :rationale "接種の現況は台帳の事実から読む。提案者の自己申告では判定しない。"
     :cites [:rabies-vaccination-check]
     :effect :vaccination-screening/set
     :value {:ticket-id subject :rabies-vaccination-not-current? lapsed?}
     :confidence 0.9}))

(defn- propose-grooming [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        sb (facts/spec-basis (:jurisdiction t))]
    {:op :actuation/apply-grooming-process
     :summary (str subject " に施術(" (:proposed-grooming-process t) ")を適用")
     :rationale "受付・ケア計画・接種確認を経た施術の適用。実施は人の承認を要する。"
     :cites (if sb [(:legal-basis sb) subject] [])
     :effect :ticket/mark-groomed
     :value {:ticket-id subject :spec-basis (:provenance sb)}
     :confidence 0.85}))

(defn- propose-return [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        sb (facts/spec-basis (:jurisdiction t))]
    {:op :actuation/return-animal
     :summary (str subject " の動物を飼主へ返却")
     :rationale "預り動物の返却。所有権は動かず占有だけが戻る行為で、人の承認を要する。"
     :cites (if sb [(:legal-basis sb) subject] [])
     :effect :ticket/mark-returned
     :value {:ticket-id subject :spec-basis (:provenance sb)}
     :confidence 0.85}))

(defn infer
  [st {:keys [op] :as request}]
  (case op
    :ticket/intake                     (propose-intake st request)
    :careplan/verify                   (propose-careplan st request)
    :vaccination/screen                (propose-vaccination-screen st request)
    :actuation/apply-grooming-process  (propose-grooming st request)
    :actuation/return-animal           (propose-return st request)
    {:op op :summary "未対応の操作" :rationale (str op)
     :cites [] :effect :noop :value {} :confidence 0.0}))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ st request] (infer st request))))
