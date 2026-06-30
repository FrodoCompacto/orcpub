(ns orcpub.fork.character-choices-export-test
  (:require [clojure.test :refer [deftest is testing]]
            [orcpub.entity :as entity]
            [orcpub.entity-test :as entity-test]
            [orcpub.entity.strict :as strict]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.fork.character-choices-export :as export]))

(def test-entity
  {:orcpub.entity/options
   {:ability-scores {:orcpub.entity/key :standard-roll
                     :orcpub.entity/value {::char5e/str 18
                                           ::char5e/dex 7
                                           ::char5e/con 13
                                           ::char5e/int 12
                                           ::char5e/wis 8
                                           ::char5e/cha 9}}
    :race {:orcpub.entity/key :human
           :orcpub.entity/options
           {:subrace {:orcpub.entity/key :damaran}
            :variant {:orcpub.entity/key :standard-human}}}
    :weapons [{:orcpub.entity/key :halberd
               :orcpub.entity/value {:orcpub.dnd.e5.character.equipment/quantity 1
                                     :orcpub.dnd.e5.character.equipment/equipped? true}}
              {:orcpub.entity/key :greataxe
               :orcpub.entity/value {:orcpub.dnd.e5.character.equipment/quantity 1
                                     :orcpub.dnd.e5.character.equipment/equipped? true}}]}})

(def homebrew-entity
  {::entity/options {:race
                     {:orcpub.entity/key :human
                      :orcpub.entity/options
                      {:subrace
                       {:orcpub.entity/key :custom
                        :orcpub.entity/value "Sancho"}}}}
   ::entity/homebrew-paths {[:race] true
                            [:race :human :subrace] true}})

(deftest compact-export-has-no-values
  (let [data (export/make-choices-export test-entity)]
    (is (= 1 (:v data)))
    (is (seq (:s data)))
    (is (not (contains? data :values)))
    (is (not (re-find #"orcpub\.entity\.strict" (pr-str data))))))

(deftest options-round-trip-human-variant-weapons
  (is (= (::entity/options test-entity)
         (export/options-round-trip test-entity))))

(deftest options-round-trip-entity-test-character
  (is (= (::entity/options entity-test/character)
         (export/options-round-trip entity-test/character))))

(deftest homebrew-flag-preserved
  (let [rebuilt (export/compact->strict (export/make-choices-export homebrew-entity))]
    (is (some ::strict/homebrew? (::strict/selections rebuilt)))))

(deftest strict-round-trip-via-compact
  (let [strict (-> test-entity char5e/to-strict entity/remove-ids (dissoc ::strict/values))
        rebuilt (export/compact->strict (export/make-choices-export test-entity))]
    (is (= (::strict/selections strict)
           (::strict/selections rebuilt)))))
