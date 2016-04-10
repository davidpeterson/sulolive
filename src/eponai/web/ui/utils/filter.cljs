(ns eponai.web.ui.utils.filter
  (:require
    [cljs-time.core :as time]
    [cljs-time.coerce :as coerce]
    [eponai.client.ui :refer-macros [opts]]
    [eponai.common.format :as format]
    [eponai.web.ui.datepicker :refer [->Datepicker]]
    [eponai.web.ui.utils :as utils]
    [om.next :as om :refer-macros [defui]]
    [sablono.core :refer-macros [html]]
    [taoensso.timbre :refer-macros [debug]]
    ))

(defui DateFilter
  Object
  (initLocalState [this]
    (let [{:keys [filter]} (om/props this)]
      {:filter filter}))

  (set-this-month-filter [this]
    (let [today (time/today)
          year (time/year today)
          month (time/month today)
          start-date (coerce/to-date (time/date-time year month 1))]
      (om/update-state! this
                        #(-> %
                             (update :filter dissoc :filter/end-date :filter/last-x-days)
                             (assoc-in [:filter :filter/start-date] start-date)))))

  (set-last-x-days-filter [this span]
    (om/update-state! this
                      #(-> %
                           (update :filter dissoc :filter/end-date :filter/start-date)
                           (assoc-in [:filter :filter/last-x-days] span))))

  (reset-date-filters [this]
    (om/update-state! this
                      #(update % :filter dissoc :filter/start-date :filter/end-date :filter/last-x-days)))

  (update-date-filter [this value]
    (let [time-type (cljs.reader/read-string value)]
      (cond
        (= time-type :all-time)
        (.reset-date-filters this)
        (= time-type :last-7-days)
        (.set-last-x-days-filter this 7)
        (= time-type :last-30-days)
        (.set-last-x-days-filter this 30)
        (= time-type :this-month)
        (.set-this-month-filter this)
        true
        (om/update-state! this #(update % :filter dissoc :filter/last-x-days)))
      (om/update-state! this assoc :type time-type)))

  (componentDidUpdate [this _ _]
    (let [{:keys [on-change]} (om/get-computed this)
          input-filter (:filter (om/get-state this))]

      (when on-change
        (on-change (cond-> input-filter

                           (some? (:filter/start-date input-filter))
                           (update :filter/start-date format/date->ymd-string)

                           (some? (:filter/end-date input-filter))
                           (update :filter/end-date format/date->ymd-string))))))

  (render [this]
    (let [{:keys [filter type]} (om/get-state this)]
      (html
        [:div.filters
         [:div.row.small-up-1.medium-up-3

          [:div.column
           [:select
            (opts {:on-change #(.update-date-filter this (.-value (.-target %)))})
            [:option
             {:value :all-time}
             "all time"]
            [:option
             {:value :this-month}
             "this month"]
            [:option
             {:value :last-7-days}
             "last 7 days"]
            [:option
             {:value :last-30-days}
             "last 30 days"]
            [:option
             {:value :date-range}
             "custom..."]]]
          (when (= :date-range type)
            [:div.column
             (->Datepicker
               (opts {:key         ["From date..."]
                      :placeholder "From date..."
                      :value       (:filter/start-date filter)
                      :on-change   #(om/update-state! this assoc-in [:filter :filter/start-date] %)}))])
          (when (= :date-range type)
            [:div.column
             (->Datepicker
               (opts {:key         ["To date..."]
                      :placeholder "To date..."
                      :value       (:filter/end-date filter)
                      :on-change   #(om/update-state! this assoc-in [:filter :filter/end-date] %)
                      :min-date    (:filter/start-date filter)}))])]]))))

(def ->DateFilter (om/factory DateFilter))

(defn add-tag [tags tag]
  (if-not (some #(= (:tag/name %) (:tag/name tag)) tags)
    (if (empty? tags)
      [tag]
      (conj tags tag))
    tags))

(defn delete-tag [tags tag]
  (into [] (remove #(= (:tag/name %) (:tag/name tag))) tags))

(defui TagFilter
  Object
  (add-tag [this tag]
    (let [{:keys [on-change]} (om/get-computed this)]
      (om/update-state! this
                        (fn [st] (-> st
                                     (assoc :input-tag "")
                                     (update :tags add-tag tag))))
      (when on-change
        (on-change (:tags (om/get-state this))))))
  (delete-tag [this tag]
    (let [{:keys [on-change]} (om/get-computed this)]
      (om/update-state! this update :tags delete-tag tag)
      (when on-change
        (on-change (:tags (om/get-state this))))))

  (initLocalState [this]
    (let [{:keys [tags]} (om/props this)]
      {:tags tags}))
  (componentWillReceiveProps [this new-props]
    (let [{:keys [tags]} new-props]
      (om/update-state! this assoc :tags tags)))

  (render [this]
    (let [{:keys [input-tag tags]} (om/get-state this)]
      (debug "Rendering tags: " tags)
      (html
        [:div
         (opts {:style {:display        :flex
                        :flex-direction :column}})

         (utils/tag-input {:input-tag     input-tag
                           :selected-tags tags
                           :on-change     #(om/update-state! this assoc :input-tag %)
                           :on-add-tag    #(.add-tag this %)
                           :on-delete-tag #(.delete-tag this %)})]))))

(def ->TagFilter (om/factory TagFilter))