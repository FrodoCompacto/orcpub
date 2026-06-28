(ns orcpub.fork.character-ai-export
  "Build a human/AI-readable JSON export of a resolved D&D 5e character.
   Not an import format — descriptive labels and display values only."
  (:require [clojure.string :as s]
            [orcpub.common :as common]
            [orcpub.entity :as entity]
            [orcpub.entity-spec :as es]
            [orcpub.template :as t]
            [orcpub.pdf-spec :as pdf]
            [orcpub.dice :as dice]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.dnd.e5.character.equipment :as char-equip5e]
            [orcpub.dnd.e5.display :as disp5e]
            [orcpub.dnd.e5.magic-items :as mi5e]
            [orcpub.dnd.e5.options :as opt5e]
            [orcpub.dnd.e5.skills :as skill5e]
            [orcpub.dnd.e5.weapons :as weapon5e]
            #?(:cljs [cljsjs.filesaverjs])
            #?(:cljs [orcpub.fork.branding :as branding])
            #?(:cljs [re-frame.core :refer [dispatch subscribe]])))

(defn- field [label value]
  (when (some? value)
    (let [v (if (string? value) (s/trim value) value)]
      (when (not (and (string? v) (s/blank? v)))
        {:label label :value v}))))

(defn- section [name fields & [{:keys [description]}]]
  (let [fields (vec (remove nil? fields))]
    (when (seq fields)
      (cond-> {:name name :fields fields}
        description (assoc :description description)))))

(defn- ability-label [k]
  (or (:name (opt5e/abilities-map k)) (s/upper-case (name k))))

(def ^:private ignore-paths-ending-with
  #{:class :levels :asi-or-feat :ability-score-improvement})

(defn- ancestor-names-string [built-template path]
  (let [ancestor-paths (map
                        (fn [p]
                          (if (ignore-paths-ending-with (last p))
                            []
                            p))
                        (reductions conj [] path))
        ancestors (map (fn [a-p]
                         (let [template-path (entity/get-template-selection-path built-template a-p [])]
                           (entity/get-in-lazy built-template template-path)))
                       (butlast ancestor-paths))
        ancestor-names (map ::t/name (remove nil? ancestors))]
    (s/join " - " ancestor-names)))

(defn- find-top-level-selection [built-template selection-key]
  (some (fn [sel] (when (= (::t/key sel) selection-key) sel))
        (::t/selections built-template)))

(defn- option-display-name [selection option-key]
  (when option-key
    (or (some (fn [{:keys [::t/key ::t/name]}]
                (when (= key option-key) name))
              (entity/selection-options selection))
        (common/kw-to-name option-key true))))

(defn- selected-values-text [selection entity-data]
  (cond
    (vector? entity-data)
    (s/join ", "
            (remove nil?
                    (map #(option-display-name selection (::entity/key %))
                         entity-data)))

    (map? entity-data)
    (or (some-> entity-data ::entity/key (option-display-name selection))
        (let [v (::entity/value entity-data)]
          (when (and v (not (map? v)) (not (coll? v)))
            (str v))))

    :else nil))

(defn- selection-label [built-template path selection]
  (let [ancestors (ancestor-names-string built-template path)
        sel-name (::t/name selection)]
    (if (s/blank? ancestors)
      sel-name
      (str ancestors " - " sel-name))))

(def ^:private manual-inventory-keys
  #{:weapons :armor :equipment :treasure :magic-weapons :magic-armor :other-magic-items})

(def ^:private choice-include-tags
  #{:ability-scores :race :subrace :background :class :starting-equipment :profs :feats
    :optional-content})

(defn- include-choice-selection? [{:keys [::t/key ::t/tags]}]
  (and (some tags choice-include-tags)
       (not (manual-inventory-keys key))
       (not (and (contains? tags :equipment) (not (contains? tags :starting-equipment))))
       (not (contains? tags :spells))))

(defn- nested-selection-name [parent-option sel-key]
  (or (some (fn [sel] (when (= (::t/key sel) sel-key) (::t/name sel)))
            (::t/selections parent-option))
      (common/kw-to-name sel-key true)))

(defn- race-option-selections-text [character built-template]
  (let [race-opt (get-in character [::entity/options :race])
        race-key (::entity/key race-opt)]
    (when race-key
      (let [race-selection (find-top-level-selection built-template :race)
            race-template-opt (some #(when (= (::t/key %) race-key) %)
                                    (entity/selection-options race-selection))
            nested (get race-opt ::entity/options)]
        (when (and race-template-opt (seq nested))
          (s/join "\n"
                  (remove nil?
                          (for [[sel-key selected] nested
                                :when (not= sel-key :subrace)
                                :let [nested-selection (some #(when (= (::t/key %) sel-key) %)
                                                              (::t/selections race-template-opt))
                                      value (selected-values-text nested-selection selected)]]
                            (when value
                              (str (nested-selection-name race-template-opt sel-key) ": " value))))))))))

(defn- identity-section [built-char character built-template]
  (let [race (char5e/race built-char)
        subrace (char5e/subrace built-char)
        levels (char5e/levels built-char)
        classes (char5e/classes built-char)
        ability-scores-sel (find-top-level-selection built-template :ability-scores)
        ability-opt (get-in character [::entity/options :ability-scores])
        ability-method (when ability-scores-sel
                         (option-display-name ability-scores-sel (::entity/key ability-opt)))
        custom-race (get-in character [::entity/options :race ::entity/value])
        custom-subrace (get-in character [::entity/options
                                          :race
                                          ::entity/options
                                          :subrace
                                          ::entity/value])
        race-options (race-option-selections-text character built-template)]
    (section "Identity"
             [(field "Character Name" (char5e/character-name built-char))
              (field "Player Name" (char5e/player-name built-char))
              (field "Sex" (char5e/sex built-char))
              (field "Race" (str race (when subrace (str " / " subrace))))
              (field "Custom Race Name" custom-race)
              (field "Custom Subrace Name" custom-subrace)
              (field "Race Options" race-options)
              (field "Ability Score Method" ability-method)
              (field "Class and Level" (pdf/class-string classes levels))
              (field "Background" (char5e/background built-char))
              (field "Alignment" (char5e/alignment built-char))
              (field "Experience Points" (char5e/xps built-char))
              (field "Faction" (char5e/faction-name built-char))]
             {:description "Name, race, class, background, and alignment"})))

(defn- builder-choices-section [character built-char built-template]
  (when (and character built-template)
    (let [selections (entity/available-selections character built-char built-template)
          choice-fields
          (for [{:keys [::t/path] :as selection} selections
                :when (include-choice-selection? selection)
                :let [label (selection-label built-template path selection)
                      entity-data (entity/get-option built-template character path)
                      value (selected-values-text selection entity-data)]
                :when value]
            (field label value))]
      (section "Builder Choices"
               choice-fields
               {:description "Selections made in the character builder, for inferring build choices"}))))

(defn- appearance-section [built-char]
  (section "Appearance"
           [(field "Age" (char5e/age built-char))
            (field "Height" (char5e/height built-char))
            (field "Weight" (char5e/weight built-char))
            (field "Eyes" (char5e/eyes built-char))
            (field "Skin" (char5e/skin built-char))
            (field "Hair" (char5e/hair built-char))]
           {:description "Physical description"}))

(defn- ability-scores-section [built-char]
  (let [scores (char5e/ability-values built-char)
        bonuses (char5e/ability-bonuses built-char)]
    (section "Ability Scores"
             (for [k char5e/ability-keys]
               (field (ability-label k)
                      (str (scores k) " (modifier " (common/bonus-str (bonuses k)) ")")))
             {:description "Core ability scores and modifiers"})))

(defn- saving-throws-section [built-char]
  (let [save-bonuses (char5e/save-bonuses built-char)
        saving-throws (set (char5e/saving-throws built-char))]
    (section "Saving Throws"
             (for [k char5e/ability-keys]
               (field (ability-label k)
                      (str (common/bonus-str (save-bonuses k))
                           (when (k saving-throws) " (proficient)"))))
             {:description "Saving throw bonuses and proficiency"})))

(defn- skills-section [built-char]
  (let [skill-bonuses (char5e/skill-bonuses built-char)
        skill-profs (char5e/skill-proficiencies built-char)
        expertise (char5e/skill-expertise built-char)]
    (section "Skills"
             (for [{:keys [name key]} skill5e/skills]
               (field name
                      (str (common/bonus-str (skill-bonuses key))
                           (cond
                             (key expertise) " (expertise)"
                             (key skill-profs) " (proficient)"
                             :else ""))))
             {:description "Skill bonuses and proficiency"})))

(defn- hit-dice-string [built-char]
  (let [levels (char5e/levels built-char)
        con-mod (es/entity-val built-char :con-mod)]
    (->> levels
         vals
         (reduce (fn [levels-per-die level]
                   (update levels-per-die (:hit-die level)
                            (fnil + 0) (:class-level level)))
                 {})
         (sort-by key)
         (map #(str (val %) "x(1d" (key %) "+" con-mod ")"))
         (s/join ", "))))

(defn- combat-section [built-char plugin-data]
  (let [{:keys [current-armor-class]} plugin-data]
    (section "Combat"
             [(field "Armor Class" current-armor-class)
              (field "Initiative" (common/bonus-str (char5e/initiative built-char)))
              (field "Speed" (pdf/speed built-char))
              (field "Hit Points (current)" (char5e/current-hit-points built-char))
              (field "Hit Points (maximum)" (char5e/max-hit-points built-char))
              (field "Hit Dice" (hit-dice-string built-char))
              (field "Passive Perception" (char5e/passive-perception built-char))
              (field "Proficiency Bonus" (common/bonus-str (char5e/proficiency-bonus built-char)))
              (field "Number of Attacks" (char5e/number-of-attacks built-char))]
             {:description "Combat statistics"})))

(defn- damage-str [die die-count mod damage-type]
  (str (dice/dice-string die-count die mod)
       (when damage-type
         (str " " (if (keyword? damage-type) (name damage-type) (str damage-type))))))

(defn- weapon-attack-lines [built-char all-weapons-map]
  (let [all-weapons (mi5e/equipped-items-details
                     (char5e/all-weapons-inventory built-char)
                     all-weapons-map)]
    (mapcat
     (fn [{:keys [name ::weapon5e/damage-die ::weapon5e/damage-die-count ::weapon5e/damage-type]
           :as weapon}]
       (let [versatile (:versatile weapon)
             normal-damage-modifier (char5e/best-weapon-damage-modifier built-char weapon false)
             normal {:name (:name weapon)
                     :attack-bonus (char5e/best-weapon-attack-modifier built-char weapon)
                     :damage (damage-str damage-die damage-die-count normal-damage-modifier damage-type)}]
         (remove
          nil?
          [normal
           (when versatile
             {:name (str (:name weapon) " (two-handed)")
              :attack-bonus (char5e/weapon-attack-modifier built-char weapon false)
              :damage (damage-str (:damage-die versatile)
                                  (:damage-die-count versatile)
                                  normal-damage-modifier
                                  damage-type)})])))
     (remove #(= (::weapon5e/type %) :ammunition) all-weapons))))

(defn- format-all-attacks [built-char all-weapons-map]
  (let [weapon-lines (weapon-attack-lines built-char all-weapons-map)
        custom-attacks (map pdf/attack-string (es/entity-val built-char :attacks))
        weapon-text (map (fn [{:keys [name attack-bonus damage]}]
                           (str name ". " (common/bonus-str attack-bonus) ", " damage))
                         weapon-lines)
        number-of-attacks (char5e/number-of-attacks built-char)]
    (str "Number of Attacks: " number-of-attacks "\n"
         (s/join "\n" (concat custom-attacks weapon-text)))))

(defn- attacks-section [built-char {:keys [all-weapons-map]}]
  (when all-weapons-map
    (let [attacks-text (format-all-attacks built-char all-weapons-map)]
      (section "Attacks"
               [(field "Attacks and Weapons" attacks-text)]
               {:description "Weapon attacks and custom attacks"}))))

(defn- proficiencies-section [built-char {:keys [language-map]}]
  (let [profs (pdf/other-profs-field built-char language-map)]
    (section "Proficiencies"
             [(field "Proficiencies and Languages" profs)]
             {:description "Weapon, armor, tool, and language proficiencies"})))

(defn- personality-section [built-char]
  (section "Personality"
           [(field "Personality Traits"
                   (s/join "\n\n"
                           (remove nil? [(char5e/personality-trait-1 built-char)
                                         (char5e/personality-trait-2 built-char)])))
            (field "Ideals" (char5e/ideals built-char))
            (field "Bonds" (char5e/bonds built-char))
            (field "Flaws" (char5e/flaws built-char))]
           {:description "Roleplay personality traits"}))

(defn- backstory-section [built-char]
  (section "Backstory and Notes"
           [(field "Backstory" (char5e/description built-char))
            (field "Notes" (char5e/notes built-char))]
           {:description "Character backstory and player notes"}))

(defn- features-section [built-char]
  (let [traits-data (pdf/traits-fields built-char)
        features-text (:features-and-traits-2 traits-data)]
    (section "Features and Traits"
             [(field "Features, Actions, and Traits" features-text)]
             {:description "Racial traits, class features, actions, bonus actions, and reactions"})))

(defn- coin-label [kw]
  (case kw
    :cp "Copper Pieces (CP)"
    :sp "Silver Pieces (SP)"
    :ep "Electrum Pieces (EP)"
    :gp "Gold Pieces (GP)"
    :pp "Platinum Pieces (PP)"
    (name kw)))

(defn- inventory-line [equipment-map kw cfg]
  (let [qty (::char-equip5e/quantity cfg 1)
        equipped? (::char-equip5e/equipped? cfg true)
        name (disp5e/equipment-name equipment-map kw)]
    (str name
         (when (> qty 1) (str " x" qty))
         " (equipped: " (if equipped? "yes" "no") ")")))

(defn- custom-item-line [{:keys [::char-equip5e/name ::char-equip5e/quantity ::char-equip5e/equipped?]}]
  (str name
       (when (> quantity 1) (str " x" quantity))
       " (equipped: " (if equipped? "yes" "no") ")"))

(defn- format-inventory-group [label equipment-map inventory-map]
  (when (seq inventory-map)
    (field label
           (s/join "\n"
                   (map (fn [[kw cfg]] (inventory-line equipment-map kw cfg))
                        (sort inventory-map))))))

(defn- equipment-section [built-char {:keys [all-magic-items-map all-weapons-map]}]
  (when (or all-magic-items-map all-weapons-map)
    (let [equipment-map (merge mi5e/all-equipment-map all-magic-items-map all-weapons-map)
          weapons (es/entity-val built-char :weapons)
          magic-weapons (es/entity-val built-char :magic-weapons)
          armor (es/entity-val built-char :armor)
          magic-armor (es/entity-val built-char :magic-armor)
          equipment (es/entity-val built-char :equipment)
          magic-items (es/entity-val built-char :magic-items)
          treasure (es/entity-val built-char :treasure)
          treasure-map (into {} (map (fn [[kw {qty ::char-equip5e/quantity}]] [kw qty]) treasure))
          coin-fields (for [k pdf/coin-keys
                            :let [v (k treasure-map)]
                            :when (and v (pos? (long v)))]
                        (field (coin-label k) v))
          custom-equipment (when (seq (char5e/custom-equipment built-char))
                             (field "Custom Equipment"
                                    (s/join "\n" (map custom-item-line (char5e/custom-equipment built-char)))))
          custom-treasure (when (seq (char5e/custom-treasure built-char))
                            (field "Custom Treasure"
                                   (s/join "\n" (map custom-item-line (char5e/custom-treasure built-char)))))]
      (section "Equipment"
               (into coin-fields
                     (remove nil?
                             [(format-inventory-group "Weapons" equipment-map weapons)
                              (format-inventory-group "Magic Weapons" equipment-map magic-weapons)
                              (format-inventory-group "Armor" equipment-map armor)
                              (format-inventory-group "Magic Armor" equipment-map magic-armor)
                              (format-inventory-group "Equipment" equipment-map equipment)
                              (format-inventory-group "Magic Items" equipment-map magic-items)
                              custom-equipment
                              custom-treasure]))
               {:description "Equipped gear, inventory, and currency"}))))

(defn- spell-level-label [lvl]
  (case lvl
    0 "Cantrip"
    1 "1st Level"
    2 "2nd Level"
    3 "3rd Level"
    (str lvl "th Level")))

(defn- spell-entry-value [spell-cfg spells-map plugin-spells-map built-char]
  (let [{:keys [key qualifier class always-prepared?]} spell-cfg
        spell-data (or (spells-map key) (plugin-spells-map key))
        prepares-spells (char5e/prepares-spells built-char)
        prepared-spells-by-class (char5e/prepared-spells-by-class built-char)
        lvl (:level spell-data 0)
        prepared? (char5e/spell-prepared? {:hide-unprepared? false
                                           :always-prepared? always-prepared?
                                           :lvl lvl
                                           :key key
                                           :class class
                                           :prepares-spells prepares-spells
                                           :prepared-spells-by-class prepared-spells-by-class})
        parts (remove nil?
                      [(str "Level: " (spell-level-label lvl))
                       (when (:school spell-data)
                         (str "School: " (name (:school spell-data))))
                       (when class (str "Class: " class))
                       (when qualifier (str "Qualifier: " qualifier))
                       (when (get prepares-spells class)
                         (str "Prepared: " (if prepared? "yes" "no")))
                       (when-let [ct (:casting-time spell-data)]
                         (str "Casting Time: " ct))
                       (when-let [rng (:range spell-data)]
                         (str "Range: " rng))
                       (when-let [dur (:duration spell-data)]
                         (str "Duration: " dur))
                       (when-let [comp (:components spell-data)]
                         (str "Components: " comp))
                       (when-let [desc (:description spell-data)]
                         (str "Description: " desc))])]
    (s/join "\n" parts)))

(defn- spell-fields [built-char {:keys [spells-map plugin-spells-map]}]
  (when (seq (char5e/spells-known built-char))
    (let [spells-known (char5e/spells-known built-char)
          flat-spells (char5e/flat-spells spells-known)
          spell-save-dc-fn (char5e/spell-save-dc-fn built-char)
          spell-attack-mod-fn (char5e/spell-attack-modifier-fn built-char)
          spell-slots (char5e/spell-slots built-char)
          classes-with-spells (into #{} (map :class flat-spells))
          class-meta-fields
          (mapcat
           (fn [cls]
             (let [ability-spells (filter #(= cls (:class %)) flat-spells)
                   ability (some :ability ability-spells)]
               (remove nil?
                       [(when ability
                          (field (str cls " — Spellcasting Ability")
                                 (:name (opt5e/abilities-map ability))))
                        (when ability
                          (field (str cls " — Spell Save DC")
                                 (spell-save-dc-fn ability)))
                        (when ability
                          (field (str cls " — Spell Attack Bonus")
                                 (common/bonus-str (spell-attack-mod-fn ability))))])))
           classes-with-spells)
          slot-fields
          (for [lvl (range 10)
                :let [slots (spell-slots lvl)]
                :when (and slots (pos? slots))]
            (field (str "Spell Slots — " (spell-level-label lvl)) slots))
          spell-list-fields
          (for [spell-cfg (sort-by (fn [{:keys [key]}]
                                     [(get-in spells-map [key :level] 0)
                                      (get-in spells-map [key :name] (name key))])
                                   flat-spells)
                :let [{:keys [key]} spell-cfg
                      spell-data (or (spells-map key) (plugin-spells-map key))
                      spell-name (or (:name spell-data)
                                     (when key (name key))
                                     "(Unknown Spell)")
                      qualifier (:qualifier spell-cfg)
                      label (str spell-name (when qualifier (str " (" qualifier ")")))]]
            (field label (spell-entry-value spell-cfg spells-map plugin-spells-map built-char)))]
      (section "Spellcasting"
               (into class-meta-fields (concat slot-fields spell-list-fields))
               {:description "Known spells, spell slots, and spellcasting stats"}))))

(defn section-field-value
  "Test helper: find a field value by section and label in an export map."
  [export section-name field-label]
  (some (fn [{:keys [name fields]}]
          (when (= name section-name)
            (some #(when (= (:label %) field-label) (:value %)) fields)))
        (:sections export)))

(defn make-export
  "Build the export map for JSON download.
   plugin-data: same keys as PDF export (:spells-map, :plugin-spells-map,
   :language-map, :all-weapons-map, :all-magic-items-map, :current-armor-class).
   character + built-template: raw entity and resolved template for builder choices.
   opts: {:app-name str :exported-at str :character-id str}"
  [built-char plugin-data character built-template & [{:keys [app-name exported-at character-id]}]]
  (let [character-name (or (char5e/character-name built-char) "Character")
        sections (remove nil?
                        [(identity-section built-char character built-template)
                         (builder-choices-section character built-char built-template)
                         (appearance-section built-char)
                         (ability-scores-section built-char)
                         (saving-throws-section built-char)
                         (skills-section built-char)
                         (combat-section built-char plugin-data)
                         (attacks-section built-char plugin-data)
                         (proficiencies-section built-char plugin-data)
                         (personality-section built-char)
                         (backstory-section built-char)
                         (features-section built-char)
                         (equipment-section built-char plugin-data)
                         (spell-fields built-char plugin-data)])]
    {:about (str "Character export from "
                 (or app-name "Character Builder")
                 ". Each section groups related D&D 5e information with human-readable labels.")
     :exportedAt exported-at
     :characterId character-id
     :characterName character-name
     :sections sections}))

#?(:cljs
   (do
     (defn- clj->json [data]
       (.stringify js/JSON (clj->js data) nil 2))

     (defn- sanitize-filename [name]
       (-> (or name "character")
           (s/replace #"[^\w\s\-]" "")
           (s/replace #"\s+" "-")
           (s/trim)))

     (defn download!
       "Download AI-readable JSON for the given built character."
       [id built-char plugin-data]
       (let [character @(subscribe [::char5e/character id])
             built-template @(subscribe [::char5e/built-template id])
             exported-at (.toISOString (js/Date.))
             data (make-export built-char plugin-data character built-template
                               {:app-name branding/app-name
                                :exported-at exported-at
                                :character-id (str id)})
             filename (str (sanitize-filename (:characterName data)) ".json")
             blob (js/Blob.
                   (clj->js [(clj->json data)])
                   (clj->js {:type "application/json;charset=utf-8"}))]
         (js/saveAs blob filename)
         (dispatch [:orcpub.dnd.e5.character/hide-options])))))
