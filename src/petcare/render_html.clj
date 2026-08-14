(ns petcare.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-9609-petcare`:
  this repo had no demo page and no generator. Every row this namespace
  emits is produced by ACTUALLY RUNNING the repo's own actor stack --
  `petcare.advisor` -> `petcare.governor` -> `petcare.phase` ->
  `petcare.store` -- driven through the compiled langgraph StateGraph
  (`petcare.operation/build` + `langgraph.graph/run*`) against this
  repo's own seed data (`petcare.store/demo-data`). Nothing on the page
  is transcribed by hand.

  ## Three things this generator refuses to do

  **1. It refuses to print a conclusion it did not measure.**
  `g/run*` returns `{:state :events :status :frontier}` and the `:audit`
  channel lives UNDER `:state` -- `(:audit result)` at the top level is
  `nil`. Reading the top level yields an empty audit, which renders as
  \"no approver\" and reads like a fact about the domain when it is
  actually a failed join. `step!` therefore throws when a run yields no
  audit facts at all, and `-main` throws when the whole scenario yields
  zero `:approval-granted` facts even though it performed approvals.

  **2. It refuses to let a governor rule go unexercised.**
  `declared-rules` extracts the rule identifiers the governor (and the
  approval node) are CAPABLE of raising by scanning their own source
  from the classpath, and `-main` throws naming any rule the scenario
  never provoked. A bare `>= 1 hold` check would keep this page green
  while the governor grew new rules nobody ever tested. The scan has an
  evidence floor: if it finds no sources, or implausibly few rules, it
  throws rather than reporting full coverage of an empty universe.

  **3. It refuses to describe behaviour it could derive.**
  The phase ladder, the phase gate matrix, the action gate, the
  condition/process incompatibility table and the jurisdiction table are
  all computed at render time by calling `petcare.phase`,
  `petcare.governor`, `petcare.registry` and `petcare.facts`. They
  cannot drift away from the code they document.

  Deterministic and offline: no timestamps, no network, no map-iteration
  order left to chance. Two runs are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
         (default `docs/samples/operator-console.html`)"
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [petcare.advisor :as advisor]
            [petcare.facts :as facts]
            [petcare.governor :as governor]
            [petcare.operation :as op]
            [petcare.phase :as phase]
            [petcare.registry :as registry]
            [petcare.sim :as sim]
            [petcare.store :as store]))

;; ============================ rule universe ============================

(def ^:private rule-bearing-sources
  "The namespaces that can put a `:rule` into a hold's `:basis`. The
  governor raises seven of its own; the approval node raises
  `:approver-rejected` when a human declines an escalation."
  ["petcare/governor.cljc" "petcare/operation.cljc"])

(def ^:private rule-re #":rule\s+:([A-Za-z0-9?!*<>=_-]+)")

(def ^:private rule-floor
  "Evidence floor. The governor documents seven checks and the approval
  node adds one; a scan that finds fewer has failed to read the source,
  and reporting 'full coverage' of a universe we could not measure is
  exactly the failure mode this generator exists to prevent."
  8)

(defn declared-rules
  "The set of rule identifiers this actor is CAPABLE of raising, read out
  of its own source on the classpath rather than restated here -- so a
  rule added to `petcare.governor` tomorrow immediately becomes a rule
  this build demands the scenario exercise.

  Throws rather than returning a small or empty set: an unmeasured
  universe would make `-main`'s coverage assertion silently vacuous."
  []
  (let [per-source (into (sorted-map)
                         (for [path rule-bearing-sources]
                           (let [res (io/resource path)]
                             (when-not res
                               (throw (ex-info "could not measure the rule universe: source not on classpath"
                                               {:source path})))
                             [path (into (sorted-set)
                                         (map second (re-seq rule-re (slurp res))))])))
        all (reduce set/union #{} (vals per-source))]
    (doseq [[path rules] per-source]
      (when (empty? rules)
        (throw (ex-info "could not measure the rule universe: source yielded no rules"
                        {:source path}))))
    (when (< (count all) rule-floor)
      (throw (ex-info "could not measure the rule universe: implausibly few rules found"
                      {:found (vec (sort all)) :floor rule-floor})))
    {:by-source per-source :all (into (sorted-set) all)}))

;; ============================ the scenario ============================

(def ^:private approver
  "The approving human is the operator this repo's own demo driver
  configures -- not a name invented for the page."
  (:actor-id sim/operator))

(defn- at-phase [n] (assoc sim/operator :phase n))

(defn- drift-probe-term
  "The probe below needs prose that reaches for a veterinary act. Rather
  than inventing one, take it from the governor's OWN excluded-term
  list, preferring a Japanese term to match the advisor's prose -- the
  probe therefore cannot drift away from the check it probes."
  []
  (let [terms governor/scope-excluded-terms]
    (or (first (filter #(some (fn [^Character c] (> (int c) 127)) (seq (str %))) terms))
        (first terms))))

(defn- drift-probe-advisor
  "An advisor that is the repo's own `petcare.advisor/infer` in every
  respect EXCEPT that its rationale reaches for a veterinary act -- the
  documented threat model of `petcare.governor`'s scope-exclusion check
  (\"the advisor's own prose reaching for sedation, diagnosis, treatment
  or euthanasia\"), injected through the `:advisor` seam
  `petcare.operation/build` exposes for exactly this.

  The ticket it reasons over is still seeded data; only the advisor's
  prose is probe input. `:scope-excluded` is ALSO reached without this
  probe (an out-of-vocabulary `:actuation/sedate-animal` request trips
  it alongside `:op-not-allowed`), so the coverage assertion never
  depends on the probe -- it is here to show the check firing ALONE, on
  an op that is otherwise entirely in-vocabulary and clean."
  []
  (let [term (drift-probe-term)]
    (reify advisor/Advisor
      (-advise [_ st request]
        (let [p (advisor/infer st request)]
          (update p :rationale str
                  " 飼主要望により " term " を伴う処置も併せて実施する。"))))))

(defn- step!
  "Runs ONE operation through the real compiled graph and records what
  the run actually observed.

  `:expect` is not decoration -- it is checked against the run's own
  status and audit facts, and a mismatch throws. A scenario that stops
  provoking what it claims to provoke fails the build instead of quietly
  rendering a thinner page."
  [{:keys [runs default-actor]}
   {:keys [thread label request context expect actor]}]
  (let [actor (or actor default-actor)
        ctx (or context sim/operator)
        r0 (g/run* actor {:request request :context ctx} {:thread-id thread})
        interrupted? (= :interrupted (:status r0))]
    (when (and (#{:approve :reject} expect) (not interrupted?))
      (throw (ex-info "scenario expected a human-approval interrupt; the run did not stop"
                      {:thread thread :expect expect :status (:status r0)})))
    (when (and (#{:commit :hold} expect) interrupted?)
      (throw (ex-info "scenario expected no human gate; the run stopped for approval"
                      {:thread thread :expect expect})))
    (let [r (case expect
              :approve (g/run* actor {:approval {:status :approved :by approver}}
                               {:thread-id thread :resume? true})
              :reject  (g/run* actor {:approval {:status :rejected :by approver}}
                               {:thread-id thread :resume? true})
              r0)
          ;; HAZARD (measured, 2026-08-15): `g/run*` returns
          ;; {:state :events :status :frontier}. The :audit channel is
          ;; UNDER :state -- `(:audit r)` is nil. Reading the top level
          ;; here would empty every approver cell on the page and make a
          ;; failed measurement look like a domain observation.
          audit (vec (get-in r [:state :audit]))]
      (when (empty? audit)
        (throw (ex-info "could not measure: the run produced no :audit facts"
                        {:thread thread :label label})))
      (let [kinds (set (map :t audit))
            want (case expect
                   :commit  #{:committed}
                   :approve #{:approval-granted :committed}
                   :reject  #{:approval-rejected}
                   :hold    #{:governor-hold})
            missing (set/difference want kinds)]
        (when (seq missing)
          (throw (ex-info "scenario expectation not met by the real run"
                          {:thread thread :expect expect
                           :missing (vec (sort missing)) :observed (vec (sort kinds))}))))
      (swap! runs conj {:thread thread :label label :expect expect :audit audit})
      r)))

(defn run-demo!
  "Drives a freshly seeded store through a scenario that reaches EVERY
  rule this actor can raise, plus the rollout-phase gate and the full
  clean lifecycle.

    ticket-1  the clean path: intake auto-commits at phase 3, then
              care-plan verification, rabies-vaccination screening,
              grooming application and animal return each escalate to a
              human and commit on approval -- after which a second
              grooming and a second return HARD-hold on the dedicated
              double-actuation guards.
    ticket-2  jurisdiction \"ATL\" has no spec-basis on file -> HARD.
    ticket-3  brachycephalic dog + heated cage dryer -> HARD, on the
              physical-incompatibility table alone (its care plan is
              verified first, so no evidence rule fires with it).
    ticket-4  rabies vaccination lapsed -> HARD; and a return attempted
              with no verified care plan -> HARD on evidence.
    ticket-5  senior dog + full restraint table -> HARD; its screening
              is escalated and then DECLINED by the human, which is the
              only path to `:approver-rejected`.

  Also: two phase-gated runs at phase 0 and phase 1 and one at phase 2
  (identical requests, earlier phase -> `:phase-disabled`), an
  out-of-vocabulary `:actuation/sedate-animal` request, and one
  adversarial advisor probe.

  Returns `{:db :runs}` -- `:runs` carries each run's own `:audit`
  channel, which is the ONLY place approver identity exists (see the
  attribution section below)."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        drift-actor (op/build db {:advisor (drift-probe-advisor)})
        runs (atom [])
        env {:runs runs :default-actor actor}
        go (partial step! env)
        t1 (store/ticket db "ticket-1")
        ;; the intake patch is read back off the seeded ticket, so even
        ;; the request payload traces to `store/demo-data`.
        intake-req {:op :ticket/intake :subject (:id t1)
                    :patch {:id (:id t1) :owner (:owner t1)}}]

    ;; --- the rollout phase gate, before anything else ------------------
    (go {:thread "p0-intake" :label "phase 0 -- read-only" :expect :hold
         :context (at-phase 0) :request intake-req})
    (go {:thread "p1-careplan" :label "phase 1 -- assisted-intake" :expect :hold
         :context (at-phase 1) :request {:op :careplan/verify :subject "ticket-1"}})

    ;; --- ticket-1: the whole clean lifecycle at phase 3 -----------------
    (go {:thread "t1-intake" :label "intake (auto-commit)" :expect :commit
         :request intake-req})
    (go {:thread "t1-careplan" :label "care-plan verification" :expect :approve
         :request {:op :careplan/verify :subject "ticket-1"}})
    (go {:thread "t1-vaccination" :label "rabies-vaccination screening" :expect :approve
         :request {:op :vaccination/screen :subject "ticket-1"}})
    (go {:thread "t1-groom" :label "grooming application" :expect :approve
         :request {:op :actuation/apply-grooming-process :subject "ticket-1"}})
    (go {:thread "t1-return" :label "animal return" :expect :approve
         :request {:op :actuation/return-animal :subject "ticket-1"}})

    ;; --- ticket-3: care plan verified, then the fatal combination ------
    (go {:thread "t3-careplan" :label "care-plan verification" :expect :approve
         :request {:op :careplan/verify :subject "ticket-3"}})
    (go {:thread "p2-return" :label "phase 2 -- assisted-verify" :expect :hold
         :context (at-phase 2) :request {:op :actuation/return-animal :subject "ticket-3"}})
    (go {:thread "t3-groom" :label "heated cage dryer on a brachycephalic dog" :expect :hold
         :request {:op :actuation/apply-grooming-process :subject "ticket-3"}})

    ;; --- ticket-5: an escalation a human DECLINES, then the hold -------
    (go {:thread "t5-careplan" :label "care-plan verification" :expect :approve
         :request {:op :careplan/verify :subject "ticket-5"}})
    (go {:thread "t5-vaccination" :label "screening declined by the approver" :expect :reject
         :request {:op :vaccination/screen :subject "ticket-5"}})
    (go {:thread "t5-groom" :label "full restraint table on a senior dog" :expect :hold
         :request {:op :actuation/apply-grooming-process :subject "ticket-5"}})

    ;; --- ticket-2 / ticket-4: basis and evidence -----------------------
    (go {:thread "t2-careplan" :label "jurisdiction with no spec-basis on file" :expect :hold
         :request {:op :careplan/verify :subject "ticket-2"}})
    (go {:thread "t4-vaccination" :label "lapsed rabies vaccination" :expect :hold
         :request {:op :vaccination/screen :subject "ticket-4"}})
    (go {:thread "t4-return" :label "return with no verified care plan" :expect :hold
         :request {:op :actuation/return-animal :subject "ticket-4"}})

    ;; --- ticket-1 again: the double-actuation guards -------------------
    (go {:thread "t1-regroom" :label "second grooming application" :expect :hold
         :request {:op :actuation/apply-grooming-process :subject "ticket-1"}})
    (go {:thread "t1-rereturn" :label "second animal return" :expect :hold
         :request {:op :actuation/return-animal :subject "ticket-1"}})

    ;; --- outside the closed vocabulary ---------------------------------
    (go {:thread "x-sedate" :label "op outside the closed vocabulary" :expect :hold
         :request {:op :actuation/sedate-animal :subject "ticket-1"}})

    ;; --- an advisor that drifts toward a veterinary act ----------------
    (go {:thread "x-drift" :label "advisor prose reaching for a veterinary act"
         :expect :hold :actor drift-actor
         :request {:op :careplan/verify :subject "ticket-1"}})

    {:db db :runs @runs}))

;; ============================ measurement ============================

(defn audit-facts
  "Every audit fact the scenario produced, in run order. Approver
  identity exists ONLY here -- see `approver-retention`."
  [runs]
  (vec (mapcat :audit runs)))

(defn holds
  "Every HARD hold and human rejection that reached the append-only
  ledger."
  [db]
  (vec (filter #(#{:governor-hold :approval-rejected} (:t %)) (store/ledger db))))

(defn exercised-rules
  "Rule identifiers the scenario actually provoked, read back off the
  ledger rather than declared."
  [db]
  (into (sorted-set) (map name (mapcat :basis (holds db)))))

(def ^:private approver-key-candidates
  "Keys under which an approver could survive into a stored record.
  Checked by membership, not assumed -- this repo's answer is measured
  at render time, not written into the page."
  [:approved-by :approver "approved_by" "approver"])

(defn- register-for
  "The store register a committed op writes into, and the record that
  actually landed there. Used to CHECK whether the approver survived the
  commit rather than to assert that it did."
  [db op subject]
  (case op
    :ticket/intake
    {:register "tickets" :record (store/ticket db subject)}

    :careplan/verify
    {:register "careplans" :record (store/careplan-of db subject)}

    :vaccination/screen
    {:register "vaccination-screenings" :record (store/vaccination-screening-of db subject)}

    :actuation/apply-grooming-process
    {:register "groomings + tickets"
     :record (merge (store/ticket db subject)
                    (first (filter #(= subject (get % "ticket_id"))
                                   (store/grooming-history db))))}

    :actuation/return-animal
    {:register "returns + tickets"
     :record (merge (store/ticket db subject)
                    (first (filter #(= subject (get % "ticket_id"))
                                   (store/return-history db))))}

    {:register nil :record nil}))

(defn approver-retention
  "For every approval this run granted, DERIVE -- by walking the store --
  whether the approver's identity is still present in the record the
  commit produced.

  Deliberately derived and not written down: a hardcoded note saying
  'this repo drops the approver' becomes a lie the moment the store is
  fixed, and silently omitting the approver would leave a reader unable
  to tell 'nobody approved' from 'the store did not keep it'."
  [db runs]
  (vec (for [{:keys [t op subject by]} (audit-facts runs)
             :when (= t :approval-granted)
             :let [{:keys [register record]} (register-for db op subject)
                   found (first (filter #(and (map? record) (contains? record %))
                                        approver-key-candidates))]]
         {:op op :subject subject :by by :register register
          :retained? (some? found) :retained-key found})))

;; ============================ rendering ============================

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw->s [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- code [v] (str "<code>" (esc (kw->s v)) "</code>"))

(defn- span [class v] (str "<span class=\"" class "\">" v "</span>"))

(defn- ok [v] (span "ok" (esc v)))
(defn- warn [v] (span "warn" (esc v)))
(defn- bad [v] (span "critical" (esc v)))
(defn- muted [v] (span "muted" (esc v)))

(defn- tr [cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title note & body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       (when note (str "    <p class=\"muted\">" note "</p>\n"))
       (str/join body)
       "  </section>\n"))

(defn- rules-cell [basis]
  (if (seq basis)
    (str/join "<br>" (map #(bad (kw->s %)) basis))
    (muted "—")))

;; ----------------------------- sections -----------------------------

(defn- tickets-section [db]
  (let [ledger (store/ledger db)
        last-fact (fn [id] (last (filter #(= id (:subject %)) ledger)))
        status (fn [id]
                 (let [f (last-fact id)]
                   (case (:t f)
                     :committed (ok "committed")
                     :governor-hold (if (seq (:basis f))
                                      (bad (str "HARD hold · " (kw->s (first (:basis f)))))
                                      (warn (str "phase hold · " (kw->s (:phase-reason f)))))
                     :approval-rejected (warn "declined by approver")
                     (muted "no activity"))))
        lifecycle (fn [{:keys [grooming-applied? animal-returned?]}]
                    (cond animal-returned? (ok "groomed & returned")
                          grooming-applied? (warn "groomed, still held")
                          :else (muted "in custody")))]
    (section
     "Care tickets (seeded animals in custody)"
     (str "Every row is read back out of <code>petcare.store</code> after the run — "
          "the grooming and return numbers are the ones "
          "<code>petcare.registry</code> actually minted this build.")
     (table ["Ticket" "Owner" "Animal" "Condition" "Proposed process" "Juris." "Grooming #" "Return #" "Custody" "Last decision"]
            (for [t (store/all-tickets db)]
              (tr [(code (:id t)) (esc (:owner t)) (esc (:animal t))
                   (code (:condition t)) (code (:proposed-grooming-process t))
                   (if (facts/covered? (:jurisdiction t))
                     (esc (:jurisdiction t))
                     (bad (str (:jurisdiction t) " (no basis)")))
                   (if-let [n (:grooming-number t)] (span "num" (esc n)) (muted "—"))
                   (if-let [n (:return-number t)] (span "num" (esc n)) (muted "—"))
                   (lifecycle t)
                   (status (:id t))]))))))

(defn- incompatibility-section [db]
  (section
   "Condition × process incompatibility (recomputed, not asked)"
   (str "Left: <code>petcare.registry/condition-forbidden-processes</code> as it stands in the code. "
        "Right: <code>registry/grooming-process-forbidden-by-condition?</code> called at render time on "
        "each seeded ticket — the same function the governor calls, given the same two ground-truth "
        "fields. <strong>Set membership, so there is no threshold a caller can lower.</strong>")
   (table ["Recorded condition" "Processes that must never be applied"]
          (for [[cond-kw forbidden] registry/condition-forbidden-processes]
            (tr [(code cond-kw)
                 (if (seq forbidden)
                   (str/join " " (map #(bad (kw->s %)) (sort forbidden)))
                   (muted "— (none)"))])))
   (table ["Ticket" "Animal" "Condition" "Proposed process" "Recomputed verdict"]
          (for [t (store/all-tickets db)]
            (tr [(code (:id t)) (esc (:animal t)) (code (:condition t))
                 (code (:proposed-grooming-process t))
                 (if (registry/grooming-process-forbidden-by-condition? t)
                   (bad "FORBIDDEN for this animal")
                   (ok "permitted"))])))))

(defn- clean-base
  "The disposition a CLEAN governor verdict yields for `op`, computed by
  the governor's own high-stakes set and the phase namespace's own
  mapping — so the matrix below cannot drift from either."
  [op]
  (let [high? (contains? governor/high-stakes op)]
    (phase/verdict->disposition {:hard? false :escalate? high? :high-stakes? high?})))

(defn- disposition-cell [{:keys [disposition reason]}]
  (str (case disposition
         :commit (ok "auto-commit")
         :escalate (warn "human approval")
         :hold (bad "hold"))
       (when reason (str " " (muted (str "· " (kw->s reason)))))))

(defn- phase-section []
  (let [ops (sort governor/allowed-ops)
        phs (sort (keys phase/phases))]
    (section
     "Rollout phase ladder"
     (str "Derived at render time from <code>petcare.phase/phases</code> and by calling "
          "<code>phase/gate</code> for every (phase, op) pair with a <em>clean</em> governor "
          "verdict. The matrix is what the code does, not a description of it.")
     (table ["Phase" "Label" "Ops allowed to write" "Ops allowed to auto-commit"]
            (for [ph phs]
              (let [{:keys [label writes auto]} (get phase/phases ph)]
                (tr [(span "num" (esc ph)) (esc label)
                     (if (seq writes) (str/join " " (map code (sort writes))) (muted "— (none)"))
                     (if (seq auto) (str/join " " (map code (sort auto))) (muted "— (none)"))]))))
     (table (into ["Op (governor-clean)"] (map #(str "phase " %) phs))
            (for [o ops]
              (tr (into [(code o)]
                        (for [ph phs]
                          (disposition-cell (phase/gate ph {:op o} (clean-base o))))))))
     (str "    <p>" (ok "Invariant, recomputed above: ")
          "every phase's auto-commit set is "
          (str/join " " (map code (sort (phase/auto-eligible-ops))))
          " — the two irreversible actuations "
          (str/join " " (map code (sort governor/high-stakes)))
          " are absent from <em>every</em> phase, including phase 3. An animal is a bailment of a "
          "living being: applying a real process to it and handing it back are always a human call.</p>\n"))))

(defn- action-gate-section [db]
  (let [ledger-holds (holds db)
        rules-seen (fn [o] (into (sorted-set)
                                 (map name (mapcat :basis (filter #(= o (:op %)) ledger-holds)))))]
    (section
     "Action gate (Pet Care Governor)"
     (str "One row per member of <code>petcare.governor/allowed-ops</code> — the closed vocabulary. "
          "Every column is computed by calling the real functions; the last column is what this "
          "build's run actually produced for that op.")
     (table ["Op" "High-stakes (always human)" "Gate at phase 3 when clean" "Rules it raised in this run"]
            (for [o (sort governor/allowed-ops)]
              (tr [(code o)
                   (if (contains? governor/high-stakes o) (bad "yes · never auto") (muted "no"))
                   (disposition-cell (phase/gate phase/default-phase {:op o} (clean-base o)))
                   (rules-cell (rules-seen o))]))))))

(defn- vocabulary-section []
  (section
   "What is absent, not merely gated"
   (str "A grooming and boarding operator is not a veterinarian. There is no op that sedates, "
        "diagnoses, treats or ends the life of an animal — the difference between a permission an "
        "operator can be argued into and a capability that does not exist. Both lists below are read "
        "out of <code>petcare.governor</code> at render time.")
   (table ["The entire proposal vocabulary" "Scope-excluded terms (checked in advisor prose)"]
          [(tr [(str/join "<br>" (map code (sort governor/allowed-ops)))
                (str/join " " (map #(str "<code>" (esc %) "</code>") governor/scope-excluded-terms))])])))

(defn- coverage-section [declared db]
  (let [exercised (exercised-rules db)
        all (:all declared)
        missing (set/difference all exercised)
        subject-for (fn [rule]
                      (str/join "<br>"
                                (for [h (holds db) :when (some #(= rule (name %)) (:basis h))]
                                  (str (esc (:subject h)) " " (muted (kw->s (:op h)))))))]
    (section
     "Governor rule coverage (build-time assertion)"
     (str "The rule universe is extracted from <code>petcare/governor.cljc</code> and "
          "<code>petcare/operation.cljc</code> on the classpath, not restated here. "
          "<strong>The build throws if any declared rule stayed silent</strong> — a bare "
          "&ldquo;at least one hold&rdquo; check would keep this page green while the governor grew "
          "new rules nobody ever exercised.")
     (table ["Rule the actor can raise" "Exercised in this run" "Where"]
            (for [r (sort all)]
              (tr [(code (str ":" r))
                   (if (contains? exercised r) (ok "yes") (bad "NEVER FIRED"))
                   (if (contains? exercised r) (subject-for r) (muted "—"))])))
     (str "    <p>" (if (empty? missing) (ok "Full coverage: ") (bad "INCOMPLETE: "))
          (esc (str (count exercised) " of " (count all) " declared rules exercised"))
          (when (seq missing)
            (esc (str " — missing " (str/join ", " (sort missing)))))
          ".</p>\n"))))

(defn- holds-section [db]
  (section
   "Every hold this run produced"
   (str "Read back off the append-only ledger. A HARD hold never reaches a human — it is not an "
        "escalation that happened to be declined. The <code>phase</code> rows are the same requests "
        "issued at an earlier rollout phase.")
   (table ["Op" "Ticket" "Rules" "Why (the governor's own words)" "Phase"]
          (for [h (holds db)]
            (tr [(code (:op h)) (code (:subject h))
                 (if (= :approval-rejected (:t h))
                   (str (warn "approver-rejected"))
                   (rules-cell (:basis h)))
                 (if-let [ds (seq (map :detail (:violations h)))]
                   (str/join "<br>" (map esc ds))
                   (if-let [pr (:phase-reason h)]
                     (warn (str "rollout phase gate · " (kw->s pr)))
                     (muted "—")))
                 (if-let [p (:phase h)] (span "num" (esc p)) (muted "—"))])))))

(defn- approvals-section [db runs]
  (let [rows (approver-retention db runs)
        retained (filter :retained? rows)
        dropped (remove :retained? rows)]
    (section
     "Human approvals and approver attribution"
     (str "Joined from each run's <code>:audit</code> channel — which lives under <code>:state</code> "
          "in <code>langgraph.graph/run*</code>, not at the top level. "
          "The right-hand column is <strong>derived by walking the store</strong> and looking for an "
          "approver key on the record the commit actually produced; it is not a note written into "
          "this page, so it stops being &ldquo;audit only&rdquo; by itself if the store starts "
          "keeping the approver.")
     (table ["Op" "Ticket" "Approved by" "Store register written" "Approver in the stored record?"]
            (for [{:keys [op subject by register retained? retained-key]} rows]
              (tr [(code op) (code subject) (esc by) (code register)
                   (if retained?
                     (ok (str "retained · " (kw->s retained-key)))
                     (warn "audit only — not retained in record"))])))
     (str "    <p>"
          (esc (str (count rows) " approvals granted; "
                    (count retained) " retained the approver in the store, "
                    (count dropped) " did not."))
          (when (seq dropped)
            (str " " (esc (str "The registers that dropped it: "
                               (str/join ", " (sort (distinct (map :register dropped))))
                               ". For those ops the approver's identity exists only in the run's audit "
                               "channel — the ledger's own commit fact does not carry it either. "
                               "Read this as measured behaviour of this build, not as a claim about "
                               "any other repo."))))
          "</p>\n"))))

(defn- registry-section [db]
  (section
   "Registry records minted this build"
   (str "Drafted by <code>petcare.registry</code> from the ticket's own jurisdiction and a per-"
        "jurisdiction sequence held by the store. Pure: same inputs, same record.")
   (table ["Kind" "Number" "Ticket" "Jurisdiction"]
          (concat
           (for [r (store/grooming-history db)]
             (tr [(esc "grooming application") (span "num" (esc (get r "grooming_number")))
                  (code (get r "ticket_id")) (esc (get r "jurisdiction"))]))
           (for [r (store/return-history db)]
             (tr [(esc "animal return") (span "num" (esc (get r "return_number")))
                  (code (get r "ticket_id")) (esc (get r "jurisdiction"))]))))))

(defn- jurisdiction-section [db]
  (let [seeded (distinct (map :jurisdiction (store/all-tickets db)))
        uncovered (sort (remove facts/covered? seeded))]
    (section
     "Jurisdictional spec-basis"
     (str (esc (facts/coverage-summary))
          " Two different regimes are tracked: the trade registration (who may hold other people's "
          "animals for reward) and the rabies-vaccination duty (a public-health rule binding the "
          "owner, which the operator must verify before accepting the animal).")
     (table ["ISO3" "Jurisdiction" "Trade registration" "Public-health basis" "Required evidence"]
            (for [[iso3 sb] facts/spec-basis-table]
              (tr [(code iso3) (esc (:name sb)) (esc (:legal-basis sb))
                   (esc (:public-health-basis sb))
                   (str/join "<br>" (map esc (:required-evidence sb)))])))
     (str "    <p>Jurisdictions appearing on seeded tickets: "
          (str/join " " (map code (sort seeded)))
          ". "
          (if (seq uncovered)
            (str (bad (str "No basis on file: " (str/join ", " uncovered)))
                 " — every proposal touching them is held. The advisor reports the gap honestly "
                 "(empty <code>:cites</code>, confidence 0.2) rather than inventing a requirement, "
                 "which is what keeps this gate reachable.")
            (ok "All seeded jurisdictions are covered."))
          "</p>\n"))))

(defn- runs-section [runs]
  (section
   "Actor runs in this build"
   (str "One row per <code>langgraph.graph/run*</code> invocation, with the fact chain that run's "
        "own <code>:audit</code> channel recorded. Every other table on this page is a projection "
        "of these runs.")
   (table ["Thread" "What was attempted" "Op" "Fact chain"]
          (for [{:keys [thread label audit]} runs]
            (tr [(code thread) (esc label)
                 (code (:op (first audit)))
                 (str/join (muted " → ")
                           (for [f audit]
                             (case (:t f)
                               :committed (ok "committed")
                               :approval-granted (ok "approval-granted")
                               :approval-rejected (warn "approval-rejected")
                               :approval-requested (warn "approval-requested")
                               :governor-hold (bad "governor-hold")
                               (muted (kw->s (:t f))))))])))))

(defn- ledger-section [db]
  (section
   "Append-only audit ledger"
   (str "The store's own log, in order. For a business holding other people's animals, this is the "
        "evidence when something goes wrong.")
   (table ["#" "Fact" "Op" "Ticket" "Basis"]
          (map-indexed
           (fn [i f]
             (tr [(span "num" (esc (inc i)))
                  (case (:t f)
                    :committed (ok "committed")
                    :governor-hold (bad "governor-hold")
                    :approval-rejected (warn "approval-rejected")
                    (muted (kw->s (:t f))))
                  (code (:op f)) (code (:subject f))
                  (cond
                    (seq (:basis f)) (esc (str/join " · " (map kw->s (:basis f))))
                    (:phase-reason f) (warn (str "phase · " (kw->s (:phase-reason f))))
                    :else (muted "—"))]))
           (store/ledger db)))))

(defn- console-css []
  (if-let [r (io/resource "petcare/console.css")]
    (slurp r)
    (throw (ex-info "vendored jp-go-dds CSS missing from the classpath"
                    {:resource "petcare/console.css"}))))

(defn render
  "Renders the whole document from a completed `run-demo!` result."
  [{:keys [db runs]} declared]
  (str
   "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
   "<title>cloud-itonami-isic-9609 · pet care — Operator Console</title>\n"
   "<style>\n" (console-css) "\n</style></head><body>\n"
   "<header class=\"bar\">\n"
   "  <h1>Pet grooming and boarding (ISIC 9609) — Operator Console</h1>\n"
   "  <span class=\"badge\">read-only sample · governor-gated · grooming &amp; return always human-approved</span>\n"
   "</header>\n"
   "<main>\n"
   "  <div class=\"banner\">\n"
   "    <p>Generated at build time by <code>petcare.render-html</code> "
   "(<code>clojure -M:dev:render-html</code>) by running this repo's own actor — "
   "<code>petcare.advisor</code> → <code>petcare.governor</code> → <code>petcare.phase</code> → "
   "<code>petcare.store</code>, through the compiled <code>langgraph</code> StateGraph — against "
   "<code>petcare.store/demo-data</code>. No row is hand-written. The build fails if the run "
   "produces no hold, if any rule the governor can raise stayed silent, or if the approver join "
   "measures nothing.</p>\n"
   "  </div>\n"
   (tickets-section db)
   (coverage-section declared db)
   (holds-section db)
   (approvals-section db runs)
   (incompatibility-section db)
   (phase-section)
   (action-gate-section db)
   (vocabulary-section)
   (jurisdiction-section db)
   (registry-section db)
   (runs-section runs)
   (ledger-section db)
   "</main>\n"
   "<footer>\n"
   "  <p>cloud-itonami-isic-9609-petcare · ISIC Rev.4 9609 (other personal service activities n.e.c. — "
   "pet grooming and boarding). Deterministic: this page contains no timestamps and is byte-identical "
   "across reruns against the same seed. Styling is vendored "
   "<a href=\"https://github.com/kotoba-lang/jp-go-digital-design-system\">jp-go-digital-design-system</a> "
   "CSS (デジタル庁デザインシステム, MIT) checked in at <code>resources/petcare/console.css</code>, so "
   "regenerating this page needs no network.</p>\n"
   "</footer>\n"
   "</body></html>\n"))

;; ============================ entry point ============================

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        declared (declared-rules)
        {:keys [db runs] :as result} (run-demo!)
        hold-facts (holds db)
        exercised (exercised-rules db)
        missing (set/difference (:all declared) exercised)
        approvals (approver-retention db runs)
        expected-approvals (count (filter #(= :approve (:expect %)) runs))]

    ;; 1. the page must show the governor actually refusing something.
    (when (zero? (count (filter #(= :governor-hold (:t %)) hold-facts)))
      (throw (ex-info "refusing to write a console: the run produced zero :governor-hold facts"
                      {:ledger-size (count (store/ledger db))})))

    ;; 2. every rule the actor can raise must have been raised. A rule
    ;;    that never fires is a rule nobody has ever seen work.
    (when (seq missing)
      (throw (ex-info "refusing to write a console: declared governor rules never fired"
                      {:missing (vec (sort missing))
                       :exercised (vec exercised)
                       :sources (:by-source declared)})))

    ;; 3. evidence floor on the approver join. This scenario performs
    ;;    approvals; if the rendered page would show none, the join
    ;;    failed and the page would print a measurement failure as a
    ;;    domain fact ("auto-committed, no approver").
    (when (and (pos? expected-approvals) (zero? (count approvals)))
      (throw (ex-info "refusing to write a console: approvals were granted but none could be measured"
                      {:expected expected-approvals
                       :hint ":audit lives under :state in langgraph.graph/run*"})))
    (when (not= expected-approvals (count approvals))
      (throw (ex-info "refusing to write a console: approval count does not match the scenario"
                      {:expected expected-approvals :measured (count approvals)})))

    ;; 4. a hold with neither a rule nor a phase reason is an
    ;;    unexplained refusal -- we could not measure why it held.
    (when-let [mute (seq (remove #(or (seq (:basis %)) (:phase-reason %)) hold-facts))]
      (throw (ex-info "refusing to write a console: a hold carries no rule and no phase reason"
                      {:holds (vec mute)})))

    (let [html (render result declared)]
      (io/make-parents out)
      (spit out html)
      (println (format "wrote %s (%d bytes)" out (count (.getBytes ^String html "UTF-8"))))
      (println (format "  sections=%d  table-rows=%d  runs=%d  ledger=%d"
                       (count (re-seq #"<section" html))
                       (count (re-seq #"<tr><td" html))
                       (count runs)
                       (count (store/ledger db))))
      (println (format "  holds=%d  rules=%d/%d exercised  approvals=%d (%d retained in store)"
                       (count hold-facts) (count exercised) (count (:all declared))
                       (count approvals) (count (filter :retained? approvals)))))))
