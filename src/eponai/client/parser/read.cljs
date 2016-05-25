(ns eponai.client.parser.read
  (:require [datascript.core :as d]
            [eponai.common.database.pull :as p]
            [eponai.common.prefixlist :as pl]
            [eponai.common.parser :refer [read]]
            [taoensso.timbre :refer-macros [debug error]]
            [eponai.common.format :as f]))

;; ################ Local reads  ####################
;; Generic, client only local reads goes here.

(defmethod read :query/tags
  [{:keys [db target]} _ _]
  (when-not target
    (let [tags (->> (p/pull-many db '[:db/id :tag/name] (p/all-with db {:where '[[?e :tag/name]]}))
                    (map (fn [tag] (assoc tag :tag/count (count (p/all-with db {:where [['?e :transaction/tags (:db/id tag)]]})))))
                    (sort-by :tag/name)
                    (pl/prefix-list-by :tag/name))]
      {:value tags})))

;; ################ Remote reads ####################
;; Remote reads goes here. We share these reads
;; with all client platforms (web, ios, android).
;; Local reads should be defined in:
;;     eponai.<platform>.parser.read

;; ----------

(defn read-entity-by-key
  "Gets an entity by it's ref id. Returns the full component unless a pull pattern is supplied.

  Examples:
  om/IQuery
  (query [{[:ui/component :ui.component/transactions] [:db/id]}])
  om/IQuery
  (query [[:ui/singleton :ui.singleton/project]]"
  [db query key]
  (let [e (d/entity db key)]
    {:value (cond
              (nil? e) e
              query (d/pull db '[*] (:db/id e))
              :else (d/touch e))}))

(defmethod read :ui/component
  [{:keys [db ast query target]} _ _]
  (when-not target
    (read-entity-by-key db query (:key ast))))

(defmethod read :ui/singleton
  [{:keys [db ast query target]} _ _]
  (when-not target
    (read-entity-by-key db query (:key ast))))

;; --------------- Remote readers ---------------

(defmethod read :datascript/schema
  [_ _ _]
  {:remote true})

(defmethod read :user/current
  [_ _ _]
  {:remote true})

(defn transactions-since [db last-db project-eid]
  {:pre [(number? project-eid)]
   :post [(set? %)]}
  (let [last-basis (:max-tx last-db)
        _ (debug "Last-basis: " last-basis)
        new-transaction-eids (into #{} (comp (mapcat #(d/datoms db :eavt (.-e %)))
                                             (filter #(> (.-tx %) last-basis))
                                             (map #(.-e %)))
                                   (d/datoms db :avet :transaction/project project-eid))]
    (debug "new-transaction-eids: " new-transaction-eids)
    new-transaction-eids))

(defn all-local-transactions-by-project [{:keys [parser db txs-by-project] :as env} project-eid]
  (let [{:keys [db-used txs]} (get @txs-by-project project-eid)
        {:keys [query/current-user]} (parser env '[{:query/current-user [:user/uuid]}])]
    (when (and project-eid current-user)
      (if (= db db-used)
        txs
        (let [user-uuid (:user/uuid current-user)
              new-txs (transactions-since db db-used project-eid)
              new-with-convs (p/transactions-with-conversions db user-uuid new-txs)
              ;; Group by uuid in an atom, so we can pick transactions by id destructively.
              new-by-uuid (atom (into {} (map #(vector (:transaction/uuid %) %)) new-with-convs))

              ;; Old txs may have gotten new conversions, get them.
              ;; TODO: Optimize to only do this if there are new conversions?
              old-convs (p/transaction-conversions db user-uuid txs)

              ;; Assoc :transaction/conversion in old tx if they need it.
              ;; Replace new transactions in the same position they had in the cached transactions.
              old-with-new-inserted (into [] (comp
                                               (map (fn [{:keys [db/id] :as tx}]
                                                      {:pre [(some? id)]}
                                                      (if-let [conv (get old-convs id)]
                                                        (assoc tx :transaction/conversion conv)
                                                        tx)))
                                               (map (fn [{:keys [transaction/uuid] :as old}]
                                                      (if-let [new (get @new-by-uuid uuid)]
                                                        (do (swap! new-by-uuid dissoc uuid)
                                                            new)
                                                        old))))
                                          txs)
              ;; Pour the old into remaining new. We want new to be before old because
              ;; of sorting (not sure that it is correct to do so).
              remaining-new (vals @new-by-uuid)
              new-and-old (cond->> old-with-new-inserted
                                   (seq remaining-new)
                                   (into (vec remaining-new)))]
          (swap! txs-by-project assoc project-eid {:db-used db :txs new-and-old})
          new-and-old)))))

(defn active-project-eid [db]
  (or (:ui.component.project/eid (d/entity db [:ui/component :ui.component/project]))
      ;; No project-uuid, grabbing the one with the smallest created-at
      (p/min-by db :project/created-at (p/project))))

(defmethod read :query/transactions
  [{:keys [db target ast] :as env} _ p]
  (let [project-eid (active-project-eid db)]
    (if (= target :remote)
      ;; Pass the active project uuid to remote reader
      {:remote (assoc-in ast [:params :project-eid] project-eid)}

      ;; Local read
      {:value (p/filter-transactions p (all-local-transactions-by-project env project-eid))})))

(defmethod read :query/dashboard
  [{:keys [db ast query target] :as env} _ _]
  (let [project-eid (active-project-eid db)]
    (if (= target :remote)
      ;; Pass the active project uuid to remote reader
      {:remote (assoc-in ast [:params :project-eid] project-eid)}

      ;; Local read
      (let [transactions (delay (all-local-transactions-by-project env project-eid))]

        {:value (when project-eid
                  (when-let [dashboard-id (p/one-with db {:where [['?e :dashboard/project project-eid]]})]
                    (update (p/pull db query dashboard-id)
                            :widget/_dashboard (fn [widgets]
                                                 (mapv #(p/widget-with-data db @transactions %) widgets)))))}))))

(defmethod read :query/all-projects
  [{:keys [db query target]} _ _]
  (if target
    {:remote true}
    {:value (sort-by :project/created-at (p/pull-many db query (p/all-with db (p/project))))}))

(defmethod read :query/all-currencies
  [{:keys [db query target]} _ _]
  (if target
    {:remote true}
    {:value  (p/pull-many db query (p/all-with db {:where '[[?e :currency/code]]}))}))

(defmethod read :query/current-user
  [{:keys [db query target]} _ _]
  (if target
    {:remote true}
    (let [{:keys [ui.singleton.auth/user]} (p/pull db [:ui.singleton.auth/user] [:ui/singleton :ui.singleton/auth])]
      {:value (when (:db/id user)
                (p/pull db query (:db/id user)))})))

(defmethod read :query/stripe
  [{:keys [db query parser target] :as env} _ _]
  (if target
    {:remote true}
    (let [{:keys [query/current-user]} (parser env `[{:query/current-user [:db/id]}])
          stripe (when (:db/id current-user)
                   (p/all-with db {:where [['?e :stripe/user (:db/id current-user)]]}))]
      {:value (when stripe
                (first (p/pull-many db query stripe)))})))

;; ############ Signup page reader ############

(defmethod read :query/user
  [{:keys [db query target]} k {:keys [uuid]}]
  (if target
    {:remote (not (= uuid '?uuid))}
    {:value (when (and (not (= uuid '?uuid))
                       (-> db :schema :verification/uuid))
              (try
                (p/pull db query [:user/uuid (f/str->uuid uuid)])
                (catch :default e
                  (error "Error for parser's read key:" k "error:" e)
                  {:error {:cause :invalid-verification}})))}))

(defmethod read :query/fb-user
  [{:keys [db query target]} _ _]
  (if target
    {:remote true}
    (let [eid (p/one-with db {:where '[[?e :fb-user/id]]})]
      {:value  (when eid
                 (p/pull db query eid))
       :remote true})))