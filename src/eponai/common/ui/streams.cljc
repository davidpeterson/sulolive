(ns eponai.common.ui.streams
  (:require
    [eponai.common.ui.common :as common]
    [eponai.common.ui.dom :as my-dom]
    [om.next :as om :refer [defui]]
    [taoensso.timbre :refer [debug]]
    [eponai.common.ui.elements.css :as css]
    [eponai.common.ui.elements.grid :as grid]
    [eponai.common.ui.router :as router]
    [eponai.client.routes :as routes]
    [eponai.web.ui.photo :as photo]
    [eponai.common.ui.dom :as dom]
    [eponai.web.ui.button :as button]
    [eponai.web.ui.content-item :as ci]
    [eponai.common.format.date :as date]))

(defui Streams
  static om/IQuery
  (query [_]
    [
     {:query/streams (om/get-query ci/OnlineChannel)}
     {:query/stores (om/get-query ci/StoreItem)}
     {:query/online-stores (om/get-query ci/StoreItem)}])
  Object
  (render [this]
    (let [{:query/keys [streams stores online-stores]} (om/props this)
          streaming-stores (set (map #(get-in % [:stream/store :db/id]) streams))
          online-not-live (remove #(contains? streaming-stores (:db/id %)) online-stores)
          offline-stores (remove #(contains? (set (map :db/id online-not-live)) (:db/id %)) stores)
          online-right-now (filter #(let [online (-> % :store/owners :store.owner/user :user/online?)]
                                      (or (= true online)
                                          (> 60000 (- (date/current-millis) online))))
                                   online-not-live)]
      (debug "Live props: " (om/props this))
      (dom/div
        {:classes ["sulo-browse"]}
        (grid/row
          (css/add-class :section)
          (grid/column
            nil
            (my-dom/div
              (css/add-class :section-title)
              (my-dom/h2 nil "LIVE right now"))
            (if (not-empty streams)
              (my-dom/div {:classes ["sulo-items-container"]}
                          (grid/row
                            (grid/columns-in-row {:small 2 :medium 3 :large 4})
                            (map (fn [s]
                                   (grid/column
                                     nil
                                     (ci/->OnlineChannel s)))
                                 streams)))
              (my-dom/div
                {:classes ["sulo-items-container empty-container"]}
                (my-dom/span (css/add-class :shoutout) "No stores are LIVE right now :'(")))

            (when (not-empty online-right-now)
              (my-dom/div
                {:classes ["sulo-items-container section"]}
                (my-dom/div
                  (css/add-class :section-title)
                  (my-dom/h3 nil "Stores online")
                  )
                (grid/row
                  (grid/columns-in-row {:small 2 :medium 3 :large 4})
                  (map (fn [store]
                         (grid/column
                           nil
                           (ci/->StoreItem store)))
                       online-right-now))))

            (my-dom/div
              {:classes ["sulo-items-container section"]}
              (my-dom/div
                (css/add-class :section-title)
                (my-dom/h3 nil "All SULO shops")
                )
              (grid/row
                (grid/columns-in-row {:small 2 :medium 3 :large 4})
                (map (fn [store]
                       (grid/column
                         nil
                         (ci/->StoreItem store)))
                     stores)))))))))

(def ->Streams (om/factory Streams))

(router/register-component :live Streams)