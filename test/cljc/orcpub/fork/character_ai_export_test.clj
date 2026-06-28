(ns orcpub.fork.character-ai-export-test
  (:require [clojure.test :refer [deftest is testing]]
            [orcpub.entity :as entity]
            [orcpub.template :as t]
            [orcpub.dnd.e5.template :as t5e]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.dnd.e5.magic-items :as mi5e]
            [orcpub.dnd.e5.modifiers :as mod5e]
            [orcpub.dnd.e5.options :as opt5e]
            [orcpub.dnd.e5.spell-lists :as sl5e]
            [orcpub.dnd.e5.spells :as spells5e]
            [orcpub.dnd.e5.weapons :as weapons5e]
            [orcpub.fork.character-ai-export :as export]
            [orcpub.common :as common]))

(def test-languages
  [{:name "Common" :key :common}
   {:name "Dwarvish" :key :dwarvish}])

(def language-map (common/map-by-key test-languages))

(def human-race-cfg
  {:name "Human"
   :key :human
   :size :medium
   :speed 30
   :languages ["Common"]
   :subraces [{:name "Damaran"}]
   :selections [(t/selection-cfg
                  {:name "Variant"
                   :tags #{:subrace}
                   :options [(t/option-cfg
                              {:name "Standard Human"
                               :key :standard-human
                               :modifiers [(mod5e/race-ability ::char5e/str 1)
                                           (mod5e/race-ability ::char5e/con 1)
                                           (mod5e/race-ability ::char5e/dex 1)
                                           (mod5e/race-ability ::char5e/int 1)
                                           (mod5e/race-ability ::char5e/wis 1)
                                           (mod5e/race-ability ::char5e/cha 1)]})
                             (t/option-cfg
                              {:name "Variant Human"
                               :key :variant-human})]})]})

(def test-template
  (t5e/template
   (t5e/template-selections
    nil nil nil
    weapons5e/weapons-map
    weapons5e/weapons
    sl5e/spell-lists
    spells5e/spell-map
    []
    [(opt5e/race-option sl5e/spell-lists spells5e/spell-map language-map weapons5e/weapons-map
                        human-race-cfg)]
    []
    language-map)))

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

(def plugin-data
  {:all-weapons-map weapons5e/weapons-map
   :all-magic-items-map mi5e/all-magic-items-map
   :language-map language-map
   :spells-map spells5e/spell-map
   :plugin-spells-map {}})

(defn- export-for [entity]
  (let [built-template (entity/build-template entity test-template)
        built-char (entity/build entity built-template)]
    (export/make-export built-char plugin-data entity built-template
                        {:app-name "Test"
                         :exported-at "2026-01-01T00:00:00.000Z"
                         :character-id "1"})))

(deftest export-includes-weapons-in-equipment-and-attacks
  (let [data (export-for test-entity)
        weapons-field (export/section-field-value data "Equipment" "Weapons")
        attacks-field (export/section-field-value data "Attacks" "Attacks and Weapons")]
    (testing "inventory weapons appear in Equipment"
      (is (re-find #"Halberd" weapons-field))
      (is (re-find #"Greataxe" weapons-field))
      (is (re-find #"equipped: yes" weapons-field)))
    (testing "equipped weapons appear in Attacks"
      (is (re-find #"Halberd" attacks-field))
      (is (re-find #"Greataxe" attacks-field)))))

(deftest export-includes-human-variant-and-ability-method
  (let [data (export-for test-entity)
        race-options (export/section-field-value data "Identity" "Race Options")
        ability-method (export/section-field-value data "Identity" "Ability Score Method")
        choices-section (some #(when (= "Builder Choices" (:name %)) %) (:sections data))
        choice-values (map :value (:fields choices-section))]
    (testing "human variant selection is exported"
      (is (re-find #"Standard Human" race-options))
      (is (some #(re-find #"Standard Human" (str %)) choice-values)))
    (testing "ability score method is exported"
      (is (= "Standard Roll" ability-method)))))
