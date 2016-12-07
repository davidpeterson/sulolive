(ns eponai.client.run
  (:require
    [eponai.client.utils :as utils]
    [eponai.common.ui.navbar :as nav]
    [eponai.common.parser :as parser]
    [eponai.client.parser.read]
    [goog.dom :as gdom]
    [om.next :as om]
    [eponai.common.ui.store :refer [Store]]
    [taoensso.timbre :refer [debug]]))

(defn navbar []
  (debug "Run navbar")
  (let [conn (utils/init-conn)
        reconciler (om/reconciler {:state   conn
                                   :parser  (parser/client-parser)
                                   :remotes []
                                   :migrate nil})]
    (reset! utils/reconciler-atom reconciler)
    (om/add-root! reconciler nav/Navbar (gdom/getElement "sulo-navbar"))))

(defn store []
  (debug "Run store stream")
  (let [conn (utils/init-conn)
        reconciler (om/reconciler {:state   conn
                                   :parser  (parser/client-parser)
                                   :remotes []
                                   :migrate nil})]
    (reset! utils/reconciler-atom reconciler)
    (om/add-root! reconciler Store (gdom/getElement "sulo-store-container"))))