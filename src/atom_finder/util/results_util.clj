(in-ns 'atom-finder.util)

; Utilities for processing the edn output of print-atoms-in-dir

(defn read-data
  "Read edn files living in data/"
  [filename]
  (->> (str "data/" filename)
       ClassLoader/getSystemResource
       clojure.java.io/file
       slurp
       (#(if (= \( (first %1)) %1 (str "(" %1))) ; read partial files
       (#(if (= \) (last %1)) %1 (str %1 ")"))) ; read partial files
       read-string))

(defn read-lines
  "read edn file with one entry per line"
  [filename]
  (->> filename
       slurp-lines
       (map read-string)
       ))


; TODO: delete this once log files are generated with hashes
; instead of arrays
(defn read-patch-data
  "Parse files generated by log-atoms-changed-all-commits"
  [filename]
  (->> filename
       read-data
       (map (fn [[r, a, b]] (array-map :revstr r :atom-counts a :bug-ids b)))
       ))

(defn dedupe-preprocessors
  [results]
  "Only count 1 preprocessor statement per line"
  (for [result results]
    (update-in result [1 :preprocessor-in-statement] distinct)))

(defn sum-found-atoms
  "Generate a total count of each of the atoms in the result edn"
  [results]
  (->> results
       (map last)
       (map (partial map-values count))
       (reduce (partial merge-with +))
       ))

(defn found-atom-source
  "Return the source code for an atom found in a file"
  [atom-name results]
  (->> results
       (filter #(not-empty (atom-name (last %))))
       (map (fn [[filename hash]]
              [filename
              (map #(vector %1 (nth (slurp-lines (expand-home filename)) (dec %1)))
                   (atom-name hash))]))

       pprint))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;    Result flattening
;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(declare flatten-res)

(defn flatten-seq [sres]
  ; seq? intentionally doesn't match vectors.
  ; this distnguishes iterables from tuples.
  ; I model lists of things as (well) lists,
  ; and tuples as vectors.
  (let [[seq-k seq-v] (first (filter (comp seq? last) sres))
        dissoced (dissoc sres seq-k)]

    (if (nil? seq-k)
      (list sres)
      (->> seq-v
           (map #(merge dissoced %))
           (mapcat flatten-seq)
           ))))

(defn flatten-map [mres]
  (reduce
   (fn [h [k v]]
     (cond
       (map? v) (merge h (flatten-map v))
       (seq? v) (flatten-res v)
       :else (assoc h k v)
       )) {} mres))

(defn flatten-res [fres]
  (->> (if (seq? fres) fres (list fres))
       (mapcat flatten-seq)
       (map flatten-map)))

(defn merge-down
  "collapse names of nested maps"
  ([parent-k m]
   (if (not (map? m))
     {parent-k m}
     (->> m
          (mapcat (partial apply merge-down))
          (map (fn [[k v]]
                 {(if (nil? parent-k) k
                      (join-keywords "-" [parent-k k])) v}))
          (into {}))))
  ([m] (merge-down nil m)))
