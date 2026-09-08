(ns petcare.facts
  "Jurisdictional spec-basis table for pet grooming and boarding.

  A jurisdiction NOT in this table has **no spec-basis, full stop** --
  the advisor must report that honestly (empty `:cites`) and the Pet
  Care Governor turns it into a HARD hold. Never invent a
  jurisdiction's animal-handling requirement.

  Two regimes matter here and they are different things:

    - **the trade registration** (who may hold other people's animals
      for reward) -- JPN 動物愛護管理法 第一種動物取扱業登録
    - **the rabies vaccination duty** (a public-health rule that binds
      the owner, and which a boarding/grooming operator must verify
      before accepting the animal) -- JPN 狂犬病予防法

  The second is why `:required-evidence` asks for a vaccination
  certificate and not merely a consent form."
  (:require [kotoba.lang.text :as str]))

(def spec-basis-table
  "iso3 -> requirement map. Adding a jurisdiction is a data addition,
  never a code change."
  {"JPN" {:name "Japan"
          :legal-basis "動物の愛護及び管理に関する法律 第10条（第一種動物取扱業の登録）"
          :public-health-basis "狂犬病予防法 第5条（犬の予防注射）"
          :provenance "e-Gov 法令検索 昭和48年法律第105号 / 昭和25年法律第247号"
          :required-evidence ["飼主同意記録 (owner-consent-record)"
                              "狂犬病予防注射証明書 (rabies-vaccination-certificate)"
                              "健康状態記録 (animal-health-record)"
                              "施術内容記録 (grooming-process-record)"]}
   "USA" {:name "United States"
          :legal-basis "Animal Welfare Act (7 U.S.C. 2131 et seq.)"
          :public-health-basis "State rabies vaccination statutes (varies by state)"
          :provenance "9 CFR Parts 1-3"
          :required-evidence ["Owner consent record"
                              "Rabies vaccination certificate"
                              "Animal health record"
                              "Grooming process record"]}
   "DEU" {:name "Germany"
          :legal-basis "Tierschutzgesetz (TierSchG) SS 11 (Erlaubnispflicht)"
          :public-health-basis "Tollwut-Verordnung"
          :provenance "Bundesgesetzblatt TierSchG"
          :required-evidence ["Halter-Einwilligungsprotokoll (owner-consent-record)"
                              "Tollwut-Impfnachweis (rabies-vaccination-certificate)"
                              "Gesundheitsprotokoll (animal-health-record)"
                              "Pflegeprotokoll (grooming-process-record)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO
  spec-basis. Never read nil as 'no requirements'."
  [iso3]
  (get spec-basis-table (some-> iso3 str/upper)))

(defn covered? [iso3] (some? (spec-basis iso3)))

(defn coverage-summary []
  (str (count spec-basis-table)
       " jurisdictions seeded with an official spec-basis. "
       "A jurisdiction outside this set has NO basis on file and every "
       "proposal touching it is held."))

(defn required-evidence [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn required-evidence-satisfied?
  "Does `submitted` cover every evidence item listed for `iso3`?
  A missing spec-basis can NEVER be satisfied -- returns nil (falsey),
  so the governor holds."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))
