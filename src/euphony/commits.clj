(ns euphony.commits
  (:require [clojure.core.match :refer [match]]
            [clojure.set :as Set]
            [euphony.protocols.conn :as pc]
            [euphony.queries :as q]
            [euphony.structs.label :as l]
            [euphony.utils.log :as log]))

                                        ; HELPERS

(defn- f->str
  "Convert a Clojure function to a string."
  [f] (str \[ (-> f class .getSimpleName) \]))

(defn- intersect?
  "Test if two sets intersect with each other."
  [old new] (not (empty? (Set/intersection old new))))

(defn- changelog
  "Create a log message to inform about a change."
  [source on id at from to & conflict]
  (log/log :debug (f->str source) on ":" id at ":" from "->" to
           (if conflict (str "with conflict: " conflict) "")))

                                        ; MEMORY COMMITS

(defn mem>token-fields
  [source token new-fields & [{:keys [on-conflict->new]}]]
  {:pre [(every? l/FIELDS new-fields) (set? new-fields)]
   :post [(not-empty (l/token-fields %))]}
  (let [m (partial changelog source "Mem.Token" (l/token-text token) "Fields")
        old (l/token-fields token), new new-fields, inter (Set/intersection old new)
        keep-old (fn [] token)
        keep-new (fn [] (l/token-set-fields token new))
        keep-inter (fn [] (l/token-set-fields token inter))]
    (match [(empty? old) (empty? new) (= old inter) (intersect? old new)]
      [_ true _ _] (do (keep-old))
      [_ _ true _] (do (keep-old))
      [true _ _ _] (do (m old new) (keep-new))
      [_ _ _ true] (do (m old inter) (keep-inter))
      [_ _ _ _]    (if on-conflict->new
                     (do (m old new new) (keep-new))
                     (do (m old old new) (keep-old))))))

                                        ; DATABASE COMMITS

(defn db>word-field
  [source conn word new-field & [{:keys [on-conflict->new]}]]
  {:pre [(l/FIELDS new-field)]}
  (let [m (partial changelog source "Db.Word" word "Field")
        old (-> (q/db>word (pc/db conn) word) :word/field)
        new new-field
        keep-old (fn [] conn)
        keep-new (fn [] (pc/transact conn [{:db/id [:word/word word] :word/field new}]))]
    (match [(nil? old) (nil? new) (= old new)]
      [_ true _] (do (keep-old))
      [_ _ true] (do (keep-old))
      [true _ _] (do (m old new) (keep-new))
      [_ _ _]    (if on-conflict->new
                   (do (m old new new) (keep-new))
                   (do (m old old new) (keep-old))))))

(defn db>word-fields
  [source conn word new-fields & [{:keys [on-conflict->new]}]]
  {:pre [(every? l/FIELDS new-fields) (set? new-fields)]
   :post [(not-empty (-> (q/db>word (pc/db %) word) :word/fields))]}
  (let [m (partial changelog source "Db.Word" word "Fields")
        old (-> (q/db>word (pc/db conn) word) :word/fields)
        new new-fields
        inter (Set/intersection old new)
        keep-old (fn [] conn)
        keep-new (fn [] (pc/transact conn [{:db/id [:word/word word] :word/fields new}]))
        keep-inter (fn [] (pc/transact conn (for [field (Set/difference old inter)]
                                              [:db/retract [:word/word word] :word/fields field])))]
    (match [(empty? old) (empty? new) (= old new) (intersect? old new)]
      [_ true _ _] (do (keep-old))
      [_ _ true _] (do (keep-old))
      [true _ _ _] (do (m old new) (keep-new))
      [_ _ _ true] (do (m old inter) (keep-inter))
      [_ _ _ _]    (if on-conflict->new
                     (do (m old new new) (keep-new))
                     (do (m old old new) (keep-old))))))

(defn db>label-words-pattern
  [source conn label new-pattern new-words & [{:keys [on-conflict->new]}]]
  {:pre [(string? new-pattern) (not-empty new-words)]}
  (let [m (partial changelog source "Db.Label" label "Words-Pattern")
        old (-> (q/db>label (pc/db conn) label) :label/words-pattern)
        new new-pattern
        keep-old (fn [] conn)
        keep-new (fn [] (pc/transact conn (concat (for [word new-words] {:db/id word :word/word word})
                                                  [{:db/id [:label/label label] :label/words new-words
                                                    :label/words-pattern new}])))]
    (match [(nil? old) (nil? new) (= old new)]
      [_ true _] (do (keep-old))
      [_ _ true] (do (keep-old))
      [true _ _] (do (m old new) (keep-new))
      [_ _ _]    (if on-conflict->new
                   (do (m old new new) (keep-new))
                   (do (m old old new) (keep-old))))))

(defn db>label-fields-pattern
  [source conn label new-pattern & [{:keys [on-conflict->new]}]]
  {:pre [(string? new-pattern)]}
  (let [m (partial changelog source "Db.Label" label "Fields-Pattern")
        old (-> (q/db>label (pc/db conn) label) :label/fields-pattern)
        new new-pattern
        keep-old (fn [] conn)
        keep-new (fn [] (pc/transact conn [{:db/id [:label/label label] :label/fields-pattern new-pattern}]))]
    (match [(nil? old) (nil? new) (= old new)]
      [_ true _] (do (keep-old))
      [_ _ true] (do (keep-old))
      [true _ _] (do (m old new) (keep-new))
      [_ _ _]    (if on-conflict->new
                   (do (m old new new) (keep-new))
                   (do (m old old new) (keep-old))))))

(defn db>label-vendor-attribute
  [source conn label attribute value & [{:keys [on-conflict->new]}]]
  {:pre [(#{:label/vendor-name :label/vendor-type :label/vendor-platform} attribute) (string? value)]}
  (let [m (partial changelog source "db.label" label (name attribute))
        old (-> (q/db>label (pc/db conn) label) attribute)
        new value
        keep-old (fn [] conn)
        keep-new (fn [] (pc/transact conn [{:db/id [:label/label label] attribute value}]))]
    (match [(nil? old) (nil? new) (= old new)]
      [_ true _] (do (keep-old))
      [_ _ true] (do (keep-old))
      [true _ _] (do (m old new) (keep-new))
      [_ _ _]    (if on-conflict->new
                   (do (m old new new) (keep-new))
                   (do (m old old new) (keep-old))))))

(defn db>result-cluster-attribute
  [source conn result attribute value & [{:keys [on-conflict->new]}]]
  {:pre [(#{:result/cluster-name :result/cluster-type :result/cluster-platform} attribute) (string? value)]}
  (let [m (partial changelog source "db.result" result (name attribute))
        old (-> (q/db>result (pc/db conn) result) attribute)
        new value
        keep-old (fn [] conn)
        keep-new (fn [] (pc/transact conn [{:db/id [:result/result result] attribute value}]))]
    (match [(nil? old) (nil? new) (= old new)]
      [_ true _] (do (keep-old))
      [_ _ true] (do (keep-old))
      [true _ _] (do (m old new) (keep-new))
      [_ _ _]    (if on-conflict->new
                   (do (m old new new) (keep-new))
                   (do (m old old new) (keep-old))))))

(defn db>vendors-from-tokens
  [source conn label tokens & [{:keys [name-strategy type-strategy plat-strategy]
                                :or {name-strategy first
                                     type-strategy (comp l/words-concat sort)
                                     plat-strategy (comp l/words-concat sort)}}]]
  (letfn [(select [field tokens]
            (->> tokens
                 (filter #(l/token-is-this-field? field %))
                 (map l/token-text) seq))
          (commit-attribute [co attribute value]
                            (if (nil? value) co
                                (db>label-vendor-attribute source co label attribute value)))]
    (-> conn
        (commit-attribute :label/vendor-name (some-> (select :N tokens) name-strategy))
        (commit-attribute :label/vendor-type (some-> (select :T tokens) type-strategy))
        (commit-attribute :label/vendor-platform (some-> (select :P tokens) plat-strategy)))))

(defn db>cluster-attribute-from-cluster-mapping
  [source conn mapping vendor-field cluster-field & [options]]
  (let [reverse-index (-> (q/db>result->av_label vendor-field (pc/db conn)) q/reverse-index)]
    (reduce (fn [out-co [avlabel cluster]]
              (reduce (fn [in-co res]
                        (db>result-cluster-attribute source in-co res cluster-field cluster options))
                      out-co (get reverse-index avlabel [])))
            conn mapping)))
