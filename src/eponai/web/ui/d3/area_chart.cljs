(ns eponai.web.ui.d3.area-chart
  (:require
    [cljsjs.d3]
    [eponai.client.ui :refer-macros [opts]]
    [eponai.web.ui.d3 :as d3]
    [om.next :as om :refer-macros [defui]]
    [sablono.core :refer-macros [html]]
    [taoensso.timbre :refer-macros [debug]]
    [cljs-time.coerce :as c]
    [cljs-time.core :as t]))

(defui AreaChart
  Object
  (make-axis [_ width height domain]
    (let [x-scale (.. js/d3 -time scale
                      (range #js [0 width])
                      (nice (.. js/d3 -time -year))
                      (domain (.. js/d3
                                  (extent domain (fn [d] (.-name d))))))

          y-scale (.. js/d3 -scale linear
                      (range #js [height 0])
                      (nice)
                      (domain #js [0 (.. js/d3
                                         (max domain (fn [d] (.-value d))))]))]
      {:x-axis (.. js/d3 -svg axis
                   (scale x-scale)
                   (orient "bottom")
                   (ticks (max (/ width 150) 2))
                   (tickFormat #((.. js/d3
                                     -time
                                     (format "%b %Y"))
                                 (js/Date. %))))

       :y-axis (.. js/d3 -svg axis
                   (scale y-scale)
                   (orient "left")
                   (ticks (max (/ height 50) 2))
                   (tickFormat (.. js/d3
                                   (format ",.2f"))))
       :x-scale x-scale
       :y-scale y-scale}))

  (create [this]
    (let [{:keys [id width height data]} (om/props this)
          svg (d3/build-svg (str "#area-chart-" id) width height)

          js-domain (clj->js (flatten (map :values data)))
          js-data (clj->js data)

          {:keys [margin]} (om/get-state this)
          {inner-width :width
           inner-height :height} (d3/svg-dimensions svg {:margin margin})

          {:keys [x-axis y-axis x-scale y-scale]} (.make-axis this inner-width inner-height js-domain)
          color-scale (.. js/d3 -scale category20)

          area (.. js/d3 -svg area
                   (x (fn [d] (x-scale (.-name d))))
                   (y0 inner-height)
                   (y1 (fn [d] (y-scale (.-value d)))))
          line (.. js/d3 -svg line
                   (x (fn [d] (x-scale (.-name d))))
                   (y (fn [d] (y-scale (.-value d)))))

          graph (.. svg
                    (append "g")
                    (attr "transform" (str "translate(" (:left margin) "," (:top margin) ")")))]

      (.. graph
          (append "g")
          (attr "class" "x axis grid")
          (attr "transform" (str "translate(0," inner-height ")"))
          (call x-axis))

      (.. graph
          (append "g")
          (attr "class" "y axis grid")
          (attr "transform" (str "translate(0,0)"))
          (call y-axis))

      (d3/update-on-resize this id)
      (om/update-state! this assoc :svg svg :js-domain js-domain :js-data js-data :x-scale x-scale :y-scale y-scale :area area :x-axis x-axis :y-axis y-axis :graph graph :color-scale color-scale :line line)))

  (update [this]
    (let [{:keys [svg graph x-scale y-scale x-axis y-axis area js-domain margin]} (om/get-state this)
          {inner-width :width
           inner-height :height} (d3/svg-dimensions svg {:margin margin})]

      ; When resize is in progress and sidebar pops in, inner size can be fucked up here.
      ; So just don't do anything in that case, an update will be triggered when sidebar transition is finished anyway.
      (when-not (or (js/isNaN inner-height) (js/isNaN inner-width))
        (if (empty? js-domain)
          (do
            (d3/no-data-insert svg)
            (.. x-scale
                (range #js [0 inner-width] 0.1)
                (domain #js [(t/minus (t/today) (t/days 30)) (c/to-long (t/now))]))
            (.. y-scale
                (range #js [inner-height 0])
                (domain #js [0 1])))
          (do
            (d3/no-data-remove svg)
            (.. x-scale
                (range #js [0 inner-width])
                (domain (.. js/d3
                            (extent js-domain (fn [d] (.-name d))))))
            (.. y-scale
                (range #js [inner-height 0])
                (domain #js [0 (.. js/d3
                                   (max js-domain (fn [d] (.-value d))))]))))
        (.. area
            (y0 inner-height))

        (.update-areas this)

        (.. y-axis
            (ticks (max (/ inner-height 50) 2))
            (tickSize (* -1 inner-width) 0 0))
        (.. x-axis
            (ticks (max (/ inner-width 100) 2))
            (tickSize (* -1 inner-height) 0 0))
        (.. graph
            (selectAll ".x.axis")
            (attr "transform" (str "translate(0, " inner-height ")"))
            transition
            (duration 250)
            (call x-axis))

        (.. graph
            (selectAll ".y.axis")
            transition
            (duration 250)
            (call y-axis)))))

  (update-areas [this]
    (let [{:keys [graph js-data color-scale area line]} (om/get-state this)
          graph-area (.. graph
                         (selectAll ".area")
                         (data js-data))]
      (.. graph-area
          enter
          (append "path")
          (attr "class" "area")
          (style "fill" (fn [d i]
                          (let [data-id (.-id d)]
                            (if (= data-id "mean")
                              "none"
                              (color-scale i)))))
          (style "stroke" (fn [d i]
                            (let [data-id (.-id d)]
                              (if (= data-id "mean")
                                "black"
                                (color-scale i))))))

      (.. graph-area
          transition
          (duration 250)
          (attr "d" (fn [d]
                      (let [data-id (.-id d)]
                        (debug "Found area chart id: " data-id)
                        (if (= data-id "mean")
                          (line (.-values d))
                          (area (.-values d)))))))

      (.. graph-area
          exit
          remove)))

  (initLocalState [_]
    {:margin {:top 10 :bottom 30 :left 40 :right 10}})
  (componentDidMount [this]
    (d3/create-chart this))

  (componentDidUpdate [this _ _]
    (d3/update-chart this))

  (componentWillReceiveProps [this next-props]
    (d3/update-chart-data this next-props))

  (render [this]
    (let [{:keys [id]} (om/props this)]
      (html
        [:div
         (opts {:id (str "area-chart-" id)
                :style {:height "100%"
                        :width "100%"}})]))))

(def ->AreaChart (om/factory AreaChart))