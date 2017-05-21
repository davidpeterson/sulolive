(ns eponai.common.photos
  (:require
    [clojure.string :as string]
    [taoensso.timbre :refer [debug]]))

(def transformations
  {:transformation/micro           "micro"
   :transformation/thumbnail-tiny  "thumbnail-tiny"
   :transformation/thumbnail       "thumbnail"
   :transformation/thumbnail-large "thumbnail-large"
   :transformation/preview         "preview"})

(def storage-host "https://res.cloudinary.com/sulolive")

(def transformation-path)
(defn transformation-param
  "Get transformation parameter for URL given the key"
  [k]
  (when k
    (str "t_" (get transformations k))))

(defn transform [public-id & [transformation file-ext]]
  (let [ext (or file-ext "jpg")
        t (when-not (= transformation :transformation/full)
            (transformation-param transformation))
        url (string/join "/" (into [] (remove nil? [storage-host "image/upload" t (str public-id "." ext)])))]
    url))