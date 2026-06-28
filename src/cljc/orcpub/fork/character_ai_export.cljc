(ns orcpub.fork.character-ai-export
  "Build a human/AI-readable JSON export of a resolved D&D 5e character.
   Not an import format — descriptive labels and display values only."
  (:require [clojure.string :as s]
            [orcpub.common :as common]
            [orcpub.entity-spec :as es]
            [orcpub.pdf-spec :as pdf]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.dnd.e5.options :as opt5e]
            [orcpub.dnd.e5.skills :as skill5e]
            #?(:cljs [cljsjs.filesaverjs])
            #?(:cljs [orcpub.fork.branding :as branding])
            #?(:cljs [re-frame.core :refer [dispatch]])))

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

(defn- identity-section [built-char]
  (let [race (char5e/race built-char)
        subrace (char5e/subrace built-char)
        levels (char5e/levels built-char)
        classes (char5e/classes built-char)]
    (section "Identity"
             [(field "Character Name" (char5e/character-name built-char))
              (field "Player Name" (char5e/player-name built-char))
              (field "Race" (str race (when subrace (str " / " subrace))))
              (field "Class and Level" (pdf/class-string classes levels))
              (field "Background" (char5e/background built-char))
              (field "Alignment" (char5e/alignment built-char))
              (field "Experience Points" (char5e/xps built-char))
              (field "Faction" (char5e/faction-name built-char))]
             {:description "Name, race, class, background, and alignment"})))

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

(defn- attacks-section [built-char {:keys [all-weapons-map]}]
  (when all-weapons-map
    (let [attack-data (pdf/attacks-and-spellcasting-fields built-char all-weapons-map)
          attacks-text (:attacks-and-spellcasting attack-data)]
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

(defn- equipment-section [built-char {:keys [all-magic-items-map]}]
  (when all-magic-items-map
    (let [equip-data (pdf/equipment-fields built-char all-magic-items-map)
          coin-fields (for [k pdf/coin-keys
                            :let [v (k equip-data)]
                            :when (and v (pos? (long v)))]
                        (field (coin-label k) v))]
      (section "Equipment"
               (into coin-fields
                     [(field "Equipped Items" (:features-and-traits equip-data))
                      (field "Other Equipment and Treasure" (:treasure equip-data))])
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

(defn make-export
  "Build the export map for JSON download.
   plugin-data: same keys as PDF export (:spells-map, :plugin-spells-map,
   :language-map, :all-weapons-map, :all-magic-items-map, :current-armor-class).
   opts: {:app-name str :exported-at str :character-id str}"
  [built-char plugin-data & [{:keys [app-name exported-at character-id]}]]
  (let [character-name (or (char5e/character-name built-char) "Character")
        sections (remove nil?
                        [(identity-section built-char)
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
       (let [exported-at (.toISOString (js/Date.))
             data (make-export built-char plugin-data
                               {:app-name branding/app-name
                                :exported-at exported-at
                                :character-id (str id)})
             filename (str (sanitize-filename (:characterName data)) ".json")
             blob (js/Blob.
                   (clj->js [(clj->json data)])
                   (clj->js {:type "application/json;charset=utf-8"}))]
         (js/saveAs blob filename)
         (dispatch [:orcpub.dnd.e5.character/hide-options])))))
