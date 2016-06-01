(ns eponai.web.ui.utils
  (:require [eponai.client.ui :refer-macros [opts component-implements] :refer [map-all update-query-params!]]
            [eponai.web.ui.datepicker :refer [->Datepicker]]
            [sablono.core :refer-macros [html]]
            [om.next :as om]
            [om.dom :as dom]
            [cljsjs.react.dom]
            [goog.object]
            [taoensso.timbre :refer-macros [debug warn]]
            [eponai.common.format :as format]
            [eponai.common.datascript :as common.datascript]
            [datascript.core :as d]
            [eponai.web.routes :as routes]
            [clojure.string :as string]
            [clojure.data :as diff]))

(def ^:dynamic *playground?* false)

(defonce reconciler-atom (atom nil))

;;;;;;; Helpers for remote communcation

;; TODO: Move this function somewhere else?
(defn read-basis-t-remote-middleware
  "Given a remote-fn (that describes what, where and how to send a request to a server),
  add basis-t for each key to the request. basis-t represents at which basis-t we last
  read a key from the remote db."
  [remote-fn conn]
  (fn [query]
    (let [ret (remote-fn query)
          db (d/db conn)]
      (assoc-in ret [:opts :transit-params :eponai.common.parser/read-basis-t]
                (some->> (d/q '{:find [?e .] :where [[?e :db/ident :eponai.common.parser/read-basis-t]]}
                              db)
                         (d/entity db)
                         (d/touch)
                         (into {}))))))

;;;;;;; Om dynamic query helpers

(defprotocol IDynamicQuery
  (dynamic-query-fragment [this] "Fragment of the query needed to return next query.")
  (next-query [this next-props] "Next query to be set for this component using props returned by parsing dynamic-query-fragment"))

(defprotocol IDynamicQueryParams
  ;; -> {:child Class or Component}
  (dynamic-params [this next-props] "Return map of param key to class or component (via ref). Values will be replaced with queries."))

(defn query-with-component-meta [x query]
  (with-meta query
             {:component (cond
                           (om/component? x) (type x)
                           (goog/isFunction x) x)}))

(defn update-dynamic-query! [parser state x]
  {:pre  [(or (om/component? x) (goog/isFunction x))]
   :post [(map? %)]}
  (if-let [c (component-implements IDynamicQuery x)]
    (let [query (dynamic-query-fragment c)
          next-props (parser {:state state} query)
          next-query (next-query c next-props)
          ;; Checking om/mounted? because statics get elided in advanced mode
          ;; (and we create components in (component-implements ...))
          _ (when (om/mounted? c)
              (when (= next-query (om/get-query c))
                (warn "Setting the same query. Optimize this by not setting the query?"))
              (om/set-query! c next-query) [])
          next-params (when-let [c (component-implements IDynamicQueryParams x)]
                        (let [params (dynamic-params c next-props)
                              bound-params (reduce-kv (fn [p k x]
                                                        (let [param-query (update-dynamic-query! parser state x)]
                                                          (assert (nil? (:params param-query))
                                                                  (str "Params in child: " (pr-str x)
                                                                       " was not nil. Was: " (:params param-query)
                                                                       ". We currently do not support children with params"
                                                                       " because we'd have to bind the query to the params,"
                                                                       " and we don't need it right now. We could copy om.next's"
                                                                       " implementation or something, but that's for future us "
                                                                       " to figure out ;) (HI FUTURE US)."))
                                                          (assoc p k (query-with-component-meta x (:query param-query)))))
                                                      {} params)]
                          bound-params))
          _ (when (seq next-params)
              (when (om/mounted? c)
                (om/set-query! c {:params next-params} [])))]
      (merge next-query next-params))
    ;; else:
    (if (om/iquery? x)
      {:query (om/get-query x)}
      (throw (ex-info "Unknown parameter in update-dynamic-query!" {:param x :to-str (pr-str x)})))))

(defn update-dynamic-queries! [reconciler]
  {:pre [(om/reconciler? reconciler)]}
  (let [parser (-> reconciler :config :parser)
        state (om/app-state reconciler)]
    (update-dynamic-query! parser state (om/app-root reconciler))))
;; Is the problem that we're setting a query for om/app-root? hmm..

(defn component->ref [c]
  (keyword (pr-str c)))

(defn component->query-key [c]
  (let [k (component->ref c)]
    (keyword "proxy" (str (namespace k) "." (name k)))))

;;;;;;; App initialization

(defonce conn-atom (atom nil))

(defn init-conn
  "Sets up the datascript state. Caches the state so we can keep our app state between
  figwheel reloads."
  []
  (if @conn-atom
    (do
      (debug "Reusing old conn. It currently has schema for attributes:" (-> @conn-atom deref :schema keys))
      @conn-atom)
    (let [ui-state [{:ui/singleton :ui.singleton/app}
                    {:ui/singleton :ui.singleton/auth}
                    {:ui/component                      :ui.component/project
                     :ui.component.project/selected-tab :dashboard}
                    {:ui/component :ui.component/widget}
                    {:ui/component :ui.component/root}
                    {:ui/component :ui.component/sidebar
                     :ui.component.sidebar/newsletter-subscribe-status
                                   :ui.component.sidebar.newsletter-subscribe-status/not-sent}]
          conn (d/create-conn (common.datascript/ui-schema))]
      (d/transact! conn ui-state)
      (reset! conn-atom conn))))


;;;;;;; UI component helpers

(defprotocol ISyncStateWithProps
  (props->init-state [this props] "Takes props and returns initial state."))

(defn sync-with-received-props [component new-props]
  {:pre [(and (om/component? component) (satisfies? ISyncStateWithProps component))]}
  (when (not= new-props (om/props component))
    (let [next-state (props->init-state component new-props)]
      (debug "Reseting initial state for component: " component
             " diff between old and new props:" (diff/diff (om/props component) new-props)
             "next-state: " next-state)
      (om/set-state! component next-state))))

(defn ref-dom-node [component ref-name]
  {:pre [(om/component? component) (string? ref-name)]}
  (when-let [ref (om/react-ref component ref-name)]
    (js/ReactDOM.findDOMNode ref)))

(defn focus-ref
  "Calls .focus on ref's dom node. Returns true if focus was called."
  [component ref-name]
  {:pre [(om/component? component) (string? ref-name)]}
  (when-let [node (ref-dom-node component ref-name)]
    (.focus node)
    true))

(defn left-padding
  "Returns the left padding required for string s"
  [width s]
  (let [spaces (- width (.-length (str s)))
        spaces (if (neg? spaces) 0 spaces)]
    (string/join  (repeat spaces " "))))

(defn loader []
  (html
    [:div.loader-circle-black
     (opts {:style {:top      "50%"
                    :left     "50%"
                    :position :absolute
                    :z-index  1050}})]))

(defn click-outside-target [on-click]
  (html
    [:div.click-outside-target
     (opts {:on-click #(when (= "click-outside-target" (.-className (.-target %)))
                        (on-click))})]))

(defn modal [{:keys [content on-close size]}]
  (let [click-outside-target-id (name :click-outside-target)]
    (html
      [:div.reveal-overlay
       (opts {:id       click-outside-target-id
              :style    {:z-index  2050
                         :display  "block"}
              :on-click #(when (= click-outside-target-id (.-id (.-target %)))
                          (on-close))})
       [:div
        (opts {:class (if size (str size " reveal") "reveal")
               :style (cond-> {:display  "block"
                               :position :relative})})
        [:a.close-button
         {:on-click on-close}
         "x"]
        content]])))

(defn upgrade-button [& [options]]
  (html
    [:a.upgrade-button
     (opts (merge {:href (routes/key->route :route/subscribe)}
                  options))
     [:strong "Upgrade"]]))

(defn tag [{tag-name :tag/name} {:keys [on-delete
                                        on-click]}]
  (dom/div #js {:className "label secondary tag"
                :style     #js {:display "inline-block"}
                :key tag-name}
    (dom/a #js {:className "button"
                :onClick on-click}
           (dom/small nil tag-name))
    (when on-delete
      (dom/a #js {:className "button"
                  :style #js {:padding "0 0.2em"}
                  :onClick on-delete}
             (dom/small nil (dom/strong nil "x"))))))

(defn add-tag [tags tag]
  (if-let [found-tag (some #(when (= (:tag/name %) (:tag/name tag))
                             %) tags)]
    (if (= (:tag/status found-tag) :deleted)
      (replace {found-tag (dissoc found-tag :tag/status)})
      tags)
    (conj (or tags []) (assoc tag :tag/status :added))))

(defn delete-tag [tags tag]
  (if-let [found-tag (some #(when (= (:tag/name %) (:tag/name tag))
                          %) tags)]
    (if (= (:tag/status found-tag) :added)
      (do
        (into [] (remove #(= (:tag/name %) (:tag/name found-tag)) tags)))
      (replace {found-tag (assoc found-tag :tag/status :deleted)} tags))
    tags))

(defn on-enter-down [e f]
  (when (and (= 13 (.-keyCode e))
             (seq (.. e -target -value)))
    (.preventDefault e)
    (f (.. e -target -value))))

(defn tag-input [{:keys [input-tag
                         selected-tags
                         ref
                         on-change
                         on-add-tag
                         on-delete-tag
                         on-key-down
                         placeholder
                         no-render-tags?
                         input-only?]}]
  (let [input-opts {:type        "text"
                    :ref         ref
                    :value       (or (:tag/name input-tag) "")
                    :on-change   #(on-change {:tag/name (.-value (.-target %))})
                    :on-key-down (fn [e]
                                   (when on-key-down (on-key-down e))
                                   (on-enter-down e #(on-add-tag {:tag/name (clojure.string/trim %)})))
                    :placeholder (or placeholder "Filter tags...")}]
    (html
     [:div

      (if input-only?
        [:input input-opts]

        [:div.input-group
         (opts {:style {:margin-bottom 0}})
         [:input.input-group-field input-opts]
         [:span.input-group-label
          [:i.fa.fa-tag]]])

      (when-not no-render-tags?
        [:div
         (map-all
           selected-tags
           (fn [t]
             (tag t
                  {:on-delete #(on-delete-tag t)})))])])))

(defn on-change-in
  "Function that updates state in component c with assoc-in for the specified keys ks.
  Calls f on the input value and updates state with that, (or identity if not provided).
  Function f takes one argument that's the value of the input."
  ([c ks]
    (on-change-in c ks identity))
  ([c ks f]
   {:pre [(om/component? c) (vector? ks)]}
   (fn [e]
     (om/update-state! c assoc-in ks (f (.-value (.-target e)))))))

(defn on-change [c k]
  {:pre [(keyword? k)]}
  (on-change-in c [k]))

;;############## Drag-drop transactions #############

(defn on-drag-transaction-start [_ tx-uuid event]
  (.. event -dataTransfer (setData "uuid-str" (str tx-uuid))))

(defn on-drag-transaction-over [component project-uuid event]
  (let [{:keys [drop-target]} (om/get-state component)]
    (.preventDefault event)
    (when-not (= drop-target project-uuid)
      (om/update-state! component assoc :drop-target project-uuid))))

(defn on-drag-transaction-leave [component _]
  (om/update-state! component dissoc :drop-target))

(defn on-drop-transaction [component project-uuid event]
  (.preventDefault event)
  (let [t-uuid (.. event -dataTransfer (getData "uuid-str"))]
    (om/transact! component `[(transaction/edit ~{:transaction/uuid   (format/str->uuid t-uuid)
                                                  :transaction/project {:project/uuid (str project-uuid)}
                                                  :mutation-uuid      (d/squuid)})])
    (om/update-state! component dissoc :drop-target)))

;;############# Debugging ############################

(defn shouldComponentUpdate [this next-props next-state]
  (let [next-children (. next-props -children)
        next-children (if (undefined? next-children) nil next-children)
        next-props (goog.object/get next-props "omcljs$value")
        next-props (cond-> next-props
                           (instance? om/OmProps next-props) om.next/unwrap)
        children (.. this -props -children)
        pe (not= (om.next/props this)
                 next-props)
        se (and (.. this -state)
                (not= (goog.object/get (. this -state) "omcljs$state")
                      (goog.object/get next-state "omcljs$state")))
        ce (not= children next-children)

        pdiff (diff/diff (om.next/props this) next-props)
        sdiff (diff/diff (when (.. this -state) (goog.object/get (. this -state) "omcljs$state"))
                         (goog.object/get next-state "omcljs$state"))
        cdiff (diff/diff children next-children)
        prn-diff (fn [label [in-first in-second :as diff]]
                   (when (or (some? in-first) (some? in-second))
                     (debug label " diff:" diff)))]
    (debug "props-not-eq?: " pe
           " state-not-eq?:" se
           " children-not-eq?:" ce)
    (prn-diff "props diff" pdiff)
    (prn-diff "state diff" sdiff)
    (prn-diff "children diff" cdiff)
    (or pe se ce)))