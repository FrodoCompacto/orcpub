(ns orcpub.fork.character-choices-export
  "Compact JSON export of character builder selections only.
   Excludes ::entity/values (flavor, play state, custom-equipment in values).
   Round-trips via compact->strict + char5e/from-strict when plugins match."
  (:require [clojure.string :as s]
            [orcpub.entity :as entity]
            [orcpub.entity.strict :as strict]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.dnd.e5.character.equipment :as equip]
            #?(:cljs [cljsjs.filesaverjs])
            #?(:cljs [orcpub.dnd.e5.character :as char5e])
            #?(:cljs [re-frame.core :refer [dispatch subscribe]])))

(def ^:private format-version 1)

(def ^:private equip-key->short
  {::equip/quantity :q
   ::equip/equipped? :e
   ::equip/class-starting-equipment? :c
   ::equip/background-starting-equipment? :b})

(def ^:private short->equip-key
  (into {} (map (fn [[k v]] [v k]) equip-key->short)))

(def ^:private ability-keys
  #{::char5e/str ::char5e/dex ::char5e/con ::char5e/int ::char5e/wis ::char5e/cha})

(defn- kw->str [k]
  (name k))

(defn- str->kw [s]
  (keyword s))

(defn- homebrew-key-str [k homebrew?]
  (let [nm (kw->str k)]
    (if homebrew? (str "^" nm) nm)))

(defn- parse-key-str [s]
  (if (s/starts-with? s "^")
    [(str->kw (subs s 1)) true]
    [(str->kw s) false]))

(defn- equip-map? [m]
  (some short->equip-key (keys m)))

(defn- compact-map-value [m]
  (into {}
        (map (fn [[k v]]
               (if (equip-key->short k)
                 [(equip-key->short k) v]
                 [(keyword (name k)) v]))
             m)))

(defn- expand-map-value [m]
  (if (equip-map? m)
    (into {}
          (map (fn [[k v]] [(get short->equip-key k k) v]) m))
    (into {}
          (map (fn [[k v]] [(keyword "orcpub.dnd.e5.character" (name k)) v]) m))))

(declare encode-selection)

(defn- encode-single-option
  [{:keys [::strict/key ::strict/int-value ::strict/string-value
           ::strict/map-value ::strict/selections]}]
  (let [k (kw->str key)]
    (cond
      (seq selections)
      [k (mapv encode-selection selections)]

      map-value
      [k (compact-map-value map-value)]

      string-value
      [k string-value]

      (some? int-value)
      [k int-value]

      :else
      [k []])))

(defn- leaf-multi-entry? [[k v]]
  (and (string? k)
       (or (empty? v) (number? v) (string? v) (map? v))))

(defn- compress-multi-options [encoded]
  (if (and (seq encoded) (every? leaf-multi-entry? encoded))
    (mapv first encoded)
    encoded))

(defn- encode-selection-payload [option options]
  (cond
    (seq options)
    (compress-multi-options (mapv encode-single-option options))

    option
    (encode-single-option option)

    :else
    []))

(defn encode-selection
  [{:keys [::strict/key ::strict/option ::strict/options ::strict/homebrew?]}]
  [(homebrew-key-str key homebrew?)
   (encode-selection-payload option options)])

(declare decode-selection-entry)

(defn- decode-single-option
  [[k-str payload]]
  (let [[key] (parse-key-str k-str)]
    (cond
      (and (vector? payload) (seq payload) (vector? (first payload)))
      {::strict/key key
       ::strict/selections (mapv decode-selection-entry payload)}

      (map? payload)
      {::strict/key key ::strict/map-value (expand-map-value payload)}

      (string? payload)
      {::strict/key key ::strict/string-value payload}

      (number? payload)
      {::strict/key key ::strict/int-value payload}

      :else
      {::strict/key key})))

(defn decode-selection-entry
  [[sel-key-str payload]]
  (let [[sel-key homebrew?] (parse-key-str sel-key-str)]
    (cond-> (cond
              (every? string? payload)
              {::strict/key sel-key
               ::strict/options (mapv (fn [k] {::strict/key (first (parse-key-str k))}) payload)}

              (and (vector? payload) (seq payload) (vector? (first payload)))
              {::strict/key sel-key
               ::strict/options (mapv decode-single-option payload)}

              :else
              {::strict/key sel-key
               ::strict/option (decode-single-option [sel-key-str payload])})
      homebrew? (assoc ::strict/homebrew? true))))

(defn compact->strict
  "Rebuild a strict entity (selections only) from compact export data."
  [{:keys [s]}]
  {::strict/selections (mapv decode-selection-entry s)})

(defn make-choices-export
  "Build compact choices map from raw character entity."
  [character]
  (let [strict (-> character
                   char5e/to-strict
                   entity/remove-ids
                   (dissoc :db/id ::strict/owner ::strict/values))]
    {:v format-version
     :s (mapv encode-selection (::strict/selections strict))}))

(defn options-round-trip
  "Test helper: entity options after export/import (values stripped)."
  [character]
  (-> character
      make-choices-export
      compact->strict
      char5e/from-strict
      ::entity/options))

#?(:cljs
   (do
     (defn- clj->json [data]
       (.stringify js/JSON (clj->js data)))

     (defn- sanitize-filename [name]
       (-> (or name "character")
           (s/replace #"[^\w\s\-]" "")
           (s/replace #"\s+" "-")
           (s/trim)))

     (defn download!
       "Download compact choices JSON for the given character id."
       [id]
       (let [character (if id
                         @(subscribe [::char5e/character id])
                         @(subscribe [:character]))
             data (make-choices-export character)
             name (or (get-in character [::entity/values ::char5e/character-name])
                      "character")
             filename (str (sanitize-filename name) "-choices.json")
             blob (js/Blob.
                   (clj->js [(clj->json data)])
                   (clj->js {:type "application/json;charset=utf-8"}))]
         (js/saveAs blob filename)
         (dispatch [:orcpub.dnd.e5.character/hide-options])))))
