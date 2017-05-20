(ns eponai.web.ui.user.dashboard
  (:require
    [eponai.common.ui.dom :as dom]
    [eponai.common.ui.elements.css :as css]
    [eponai.common.ui.router :as router]
    [om.next :as om :refer [defui]]
    [taoensso.timbre :refer [debug]]
    [eponai.common.ui.common :as common]
    [eponai.common.ui.navbar :as nav]
    [eponai.common.ui.elements.grid :as grid]
    [eponai.common.ui.elements.menu :as menu]
    [eponai.common.ui.elements.callout :as callout]
    [eponai.web.ui.photo :as photo]
    [eponai.client.routes :as routes]
    #?(:cljs
       [eponai.client.ui.photo-uploader :as pu])))

(defn edit-profile-modal [component]
  (let [{:query/keys [auth]} (om/props component)
        {:keys [photo-upload queue-photo]} (om/get-state component)
        user-profile (:user/profile auth)]
    (common/modal
      {:on-close #(om/update-state! component dissoc :modal)
       :size     "full"}
      (dom/h2 nil "Edit profile")
      (grid/row
        (css/align :center)
        (grid/column
          (grid/column-size {:small 6 :medium 4 :large 3})

          (if (some? queue-photo)
            (dom/div
              {:classes ["upload-photo circle loading user-profile-photo"]}
              (photo/circle {:src (:src queue-photo)}
                            (photo/overlay nil (dom/i {:classes ["fa fa-spinner fa-spin"]}))))
            (dom/label {:htmlFor "file-profile"
                        :classes ["upload-photo circle"]}
                       (photo/user-photo auth {:transformation :transformation/thumbnail})
                       #?(:cljs
                          (pu/->PhotoUploader
                            (om/computed
                              {}
                              {:hide-label?     true
                               :id              "profile"
                               :on-photo-queue  (fn [img-result]
                                                  (om/update-state! component assoc :queue-photo {:src img-result}))
                               :on-photo-upload (fn [photo]
                                                  ;(msg/om-transact! this [(list 'photo/upload {:photo photo})
                                                  ;                        :query/user])
                                                  )}))))))
        (grid/column
          (grid/column-size {:small 12 :medium 8 :large 9})
          (dom/div
            nil
            (grid/row
              nil
              (grid/column
                (->> (grid/column-size {:small 12 :medium 3 :large 2})
                     (css/text-align :right))
                (dom/label nil "Name"))
              (grid/column
                nil
                (dom/input {:type         "text"
                            :defaultValue (:user.profile/name user-profile)})))

            ;(my-dom/div
            ;  (css/grid-row)
            ;  (my-dom/div
            ;    (->> (css/grid-column)
            ;         (css/grid-column-size {:small 3 :medium 3 :large 2})
            ;         (css/text-align :right))
            ;    (dom/label nil "Username"))
            ;  (my-dom/div
            ;    (css/grid-column)
            ;    (dom/input #js {:type "text"})))

            )
          (dom/div
            (css/text-align :right)
            (dom/a (css/button {:onClick #(.save-info component)}) (dom/span nil "Save"))))))))

(def payment-logos
  {"Visa"             "icon-cc-visa"
   "American Express" "icon-cc-amex"
   "MasterCard"       "icon-cc-mastercard"
   "Discover"         "icon-cc-discover"
   "JCB"              "icon-cc-jcb"
   "Diners Club"      "icon-cc-diners"
   "Unknown"          "icon-cc-unknown"})

(defn payment-info-modal [component]
  (let [cards []
        on-close #(om/update-state! component dissoc :modal)]
    (common/modal
      {:on-close on-close
       :size     :full}
      (grid/row-column
        (css/text-align :center)
        (dom/h2 nil "Credit cards")
        (if (empty? cards)
          (dom/p nil
                 (dom/span nil "You don't have any saved credit cards.")
                 (dom/br nil)
                 (dom/small nil "Save your cards at checkout."))
          (dom/div
            nil
            (menu/vertical
              (css/add-class :section-list)
              (map-indexed (fn [i c]
                             (menu/item
                               nil
                               (grid/row
                                 (css/add-class :collapse)
                                 (grid/column
                                   (css/add-class :shrink)
                                   (dom/div {:classes ["icon" (get payment-logos (:brand c))]}))
                                 (grid/column
                                   nil
                                   (grid/row
                                     nil
                                     (grid/column
                                       nil
                                       (dom/div
                                         (css/add-class :payment-card)
                                         (dom/p nil (dom/span (css/add-class :payment-brand) (:brand c)))
                                         (dom/p nil (dom/small (css/add-class :payment-past4) (str "ending in " (:last4 c))))))
                                     (grid/column
                                       (grid/column-size {:small 12 :medium 6})
                                       (dom/a (->> (css/button-hollow)
                                                   (css/add-class :secondary)
                                                   (css/add-class :small))
                                              (dom/span nil "Set default"))
                                       (dom/a (->> (css/button-hollow)
                                                   (css/add-class :secondary)
                                                   (css/add-class :small))
                                              (dom/span nil "Remove"))))))))
                           cards))
            (dom/p nil (dom/small nil "Save your cards at checkout."))))
        (dom/a
          (->> {:onClick on-close}
               (css/button-hollow)
               (css/add-class :secondary)
               (css/add-class :small)) (dom/span nil "Close"))))))
(defui UserDashboard
  static om/IQuery
  (query [_]
    [{:proxy/navbar (om/get-query nav/Navbar)}
     {:query/auth [:user/email
                   {:user/profile [:user.profile/name
                                   {:user.profile/photo [:photo/id]}]}
                   :user/stripe]}
     :query/current-route])
  Object
  ;(initLocalState [_]
  ;  {:modal :modal/payment-info})
  (render [this]
    (let [{:proxy/keys [navbar]
           :query/keys [auth current-route]} (om/props this)
          {:keys [modal photo-upload queue-photo]} (om/get-state this)
          {user-profile :user/profile} auth
          {:keys [route-params]} current-route]
      (debug "Auth: " auth)
      (common/page-container
        {:navbar navbar :id "sulo-user-dashboard"}
        (grid/row-column
          nil
          (dom/h1 nil "Settings")
          (dom/div
            (css/add-class :section-title)
            (dom/span nil "Account"))
          (menu/vertical
            (css/add-class :section-list)
            (menu/item
              nil
              (grid/row
                (->> (css/align :middle)
                     (css/add-class :collapse))
                (grid/column
                  nil
                  (dom/label nil "Email")
                  (dom/span nil (:user/email auth)))
                (grid/column
                  (grid/column-size {:small 12 :medium 6})
                  ;(dom/a
                  ;  (->> (css/button-hollow)
                  ;       (css/add-class :secondary)
                  ;       (css/add-class :small))
                  ;  (dom/span nil "Edit email"))
                  )
                ))
            (cond (= modal :modal/edit-profile)
                  (edit-profile-modal this)
                  (= modal :modal/payment-info)
                  (payment-info-modal this))
            (menu/item
              nil
              (grid/row
                (->> (css/align :middle)
                     (css/add-class :collapse))
                (grid/column
                  nil
                  (dom/label nil "Public profile")
                  (dom/p nil (dom/small nil "This is how other users on SULO will see you when you interact in common spaces (such as store chat rooms)."))
                  )
                (grid/column
                  (grid/column-size {:small 12 :medium 6})
                  (dom/div
                    (css/add-class :user-profile)
                    (dom/span nil (:user.profile/name user-profile))
                    (photo/user-photo auth {:transformation :transformation/thumbnail}))
                  (dom/a
                    (->> {:href (routes/url :user/profile route-params)}
                         (css/button-hollow)
                         (css/add-class :secondary)
                         (css/add-class :small))
                    (dom/span nil "Edit profile"))))))
          (dom/div
            (css/add-class :section-title)
            (dom/span nil "Shopping details"))
          (menu/vertical
            (css/add-class :section-list)

            (menu/item
              nil
              (grid/row
                (->> (css/align :middle)
                     (css/add-class :collapse))
                (grid/column
                  nil
                  (dom/label nil "Payment info")
                  (dom/p nil (dom/small nil "Manage your saved credit cards. Change your default card or remove old ones.")))
                (grid/column
                  (grid/column-size {:small 12 :medium 6})
                  (dom/a
                    (->> {:onClick #(om/update-state! this assoc :modal :modal/payment-info)}
                         (css/button-hollow)
                         (css/add-class :secondary)
                         (css/add-class :small))
                    (dom/span nil "Manage payment info")))))
            (menu/item
              nil
              (grid/row
                (->> (css/align :middle)
                     (css/add-class :collapse))
                (grid/column
                  nil
                  (dom/label nil "Shipping")
                  (dom/p nil (dom/small nil "Your saved shipping addresses for easier checkout.")))
                (grid/column
                  (grid/column-size {:small 12 :medium 6})
                  (dom/a
                    (->> (css/button-hollow)
                         (css/add-class :secondary)
                         (css/add-class :small))
                    (dom/span nil "Manage shipping info"))))))

          (dom/div
            (css/add-class :section-title)
            (dom/span nil "Connections"))
          (menu/vertical
            (css/add-class :section-list)
            (menu/item
              nil
              (grid/row
                (->> (css/align :middle)
                     (css/add-class :collapse))
                (grid/column
                  nil
                  (dom/label nil "Facebook")
                  (dom/p nil (dom/small nil "Connect to Facebook to login with your account. We will never post to Facebook or message your friends without your permission")))
                (grid/column
                  (grid/column-size {:small 12 :medium 6})
                  (dom/a
                    (->> (css/button)
                         (css/add-class :facebook)
                         (css/add-class :small))
                    (dom/i {:classes ["fa fa-facebook fa-fw"]})
                    (dom/span nil "Connect to Facebook")))))

            (menu/item
              nil
              (grid/row
                (->> (css/align :middle)
                     (css/add-class :collapse))
                (grid/column
                  nil
                  (dom/label nil "Twitter")
                  (dom/p nil (dom/small nil "Connect to Twitter to login with your account. We will never post to Twitter or message your followers without your permission.")))
                (grid/column
                  (grid/column-size {:small 12 :medium 6})
                  (dom/a
                    (->> (css/button)
                         (css/add-class :twitter)
                         (css/add-class :small))
                    (dom/i {:classes ["fa fa-twitter fa-fw"]})
                    (dom/span nil "Connect to Twitter")))))))))))

;(def ->UserSettings (om/factory UserSettings))

(router/register-component :user-settings UserDashboard)