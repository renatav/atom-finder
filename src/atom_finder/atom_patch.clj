(ns atom-finder.atom-patch
  (:require
   [atom-finder.util :refer :all]
   [atom-finder.constants :refer :all]
   [atom-finder.classifier :refer :all]
   [atom-finder.source-versions :refer :all]
   [atom-finder.patch :refer :all]
   [atom-finder.results-util :refer :all]
   [clojure.pprint :refer [pprint]]
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clj-jgit.porcelain :as gitp]
   [clj-jgit.querying :as gitq]
   [clj-jgit.internal :as giti]
   [schema.core :as s]
   )
  (:import
   [atom_finder.classifier Atom]
   [org.eclipse.jgit.lib ObjectReader Repository]
   [org.eclipse.jgit.api Git]
   [org.eclipse.jgit.treewalk TreeWalk filter.PathFilter]
   [org.eclipse.cdt.core.dom.ast IASTTranslationUnit]
   )
  )

(def AtomFinder (s/=> IASTTranslationUnit [IASTTranslationUnit]))
(def AtomFinders [(s/one AtomFinder "atom-finder") AtomFinder])
(def BeforeAfter [(s/one IASTTranslationUnit "before") (s/one IASTTranslationUnit "after")])
(def BeforeAfters [(s/one BeforeAfter "commit-file") BeforeAfter])
;(def BACounts [(s/one s/Num "count-before") (s/one s/Num "count-after")])
(def BACounts {(s/required-key :count-before) s/Int (s/required-key :count-after) s/Int})

;
;(do (def repo gcc-repo)(def commit-hash "3bb246b3c2d11eb3f45fab3b4893d46a47d5f931")(def file-name "gcc/c-family/c-pretty-print.c"))
;(do (def repo  ag-repo)(def commit-hash "05be1eddca2bce1cb923afda2b6ab5e67faa248c")(def file-name "src/print.c"))
;(def atom-classifier conditional-atom?)
;(def atom-finder (->> atom-lookup :conditional :finder))
;(def parent-hash (commit-parent-hash repo commit-hash))
;(def rev-commit (first (gitq/rev-list repo)))

(defn find-rev-commit
  "make a new revwalk to find given commit"
  [repo commit-hash]
  (gitq/find-rev-commit repo (giti/new-rev-walk repo) commit-hash)
  )

(defn object-loader-string
  "dump the contents of an ObjectLoader to a String"
  [loader]
  (->> loader .getBytes String.))

(s/defn commit-file-source :- String
  "Return full source for each file changed in a commit"
  [repo :- Git commit-hash :- String file-name :- String]
  (let [repository (.getRepository repo)
        rc (find-rev-commit repo commit-hash)
        tree      (.getTree rc)
        tree-walk (doto (TreeWalk. repository) (.setRecursive true) (.addTree tree))
        ]

    (.setFilter tree-walk (PathFilter/create file-name)) ; Use PathFilterGroup???? http://download.eclipse.org/jgit/docs/jgit-2.0.0.201206130900-r/apidocs/org/eclipse/jgit/treewalk/filter/PathFilter.html
    (.next tree-walk)

    (let [object-id (.getObjectId tree-walk 0)
          loader (.open repository object-id)]
      (object-loader-string loader)
      )
  ))


;(print (commit-file-source repo commit-hash "gcc/testsuite/g++.dg/debug/dwarf2/integer-typedef.C"))
;(print (commit-file-source repo commit-hash "gcc/c-family/ChangeLog"))

;(commit-file-atom-count gcc-repo "3bb246b3c2d11eb3f45fab3b4893d46a47d5f931" "gcc/c-family/c-pretty-print.c" conditional-atom?)
;(commit-file-atom-count gcc-repo commit-hash "gcc/c-family/c-pretty-print.c" conditional-atom?)

(s/defn commit-file-atom-count :- s/Int
  "count the occurence of an atom in commit's version of file"
  [repo commit-hash file-name atom-classifier]
    (->> (commit-file-source repo commit-hash file-name)
         parse-source
         (filter-tree atom-classifier)
         count ; timing hot spot. Make more efficient atom counting function?
         ))

(defn edited-files
  "which files were edited in commit"
  [repo commit-hash]
  (->> (find-rev-commit repo commit-hash)
       (gitq/changed-files repo)
       (filter #(= (last %) :edit))
       (map first)
       ))


;(atoms-removed-in-commit repo commit-hash atom-classifier)
;(atom-removed-in-commit-file? repo commit-hash "gcc/c-family/ChangeLog" atom-classifier)
;(def commit-hash "97574c57cf26ace9b8609575bbab66465924fef7")
;(def file-name "gcc/c-family/ChangeLog")

;(apply-before-after repo commit-hash file-name count-nodes)

(defn commit-parent-hash
  [repo commit-hash]
  (.name (first (.getParents (find-commit repo commit-hash)))))

(defn apply-before-after
  "parse a commit and it's parent and apply f to the root of both"
  [repo commit-hash file-name f]
  (let [parent-hash (commit-parent-hash repo commit-hash)]
    [(f (parse-source (commit-file-source repo parent-hash file-name)))
     (f (parse-source (commit-file-source repo commit-hash file-name)))]))

(defn source-before-after
  "Return the ast of changed files before/after a commit"
  [repo commit-hash file-name]
  (apply-before-after repo commit-hash file-name identity))

(s/defn atom-removed-in-file? :- Boolean
  "Count the number of atoms in two ASTs and see if they've decreased"
  [atom-finder :- AtomFinder srcs :- BeforeAfter]
  (apply > (atom-in-file-counts atom-finder srcs)))

(s/defn atom-in-file-counts :- BACounts
  "Count the number of atoms in two ASTs"
  [atom-finder :- AtomFinder srcs :- BeforeAfter]
   (zipmap [:count-before :count-after] (map (comp count atom-finder) srcs)))

(s/defn atoms-in-file-counts ;{s/Keyword BACounts}
  "Check multiple atoms in a single file"
  [atoms :- [Atom] srcs :- BeforeAfter]
  (map (fn [atom] {:atom (:name atom)
                   :counts (atom-in-file-counts (:finder atom) srcs)}) atoms))

(s/defn commit-files-before-after :- [{(s/required-key :file) s/Str
                                       (s/required-key :source-before) IASTTranslationUnit
                                       (s/required-key :source-after) IASTTranslationUnit}]
  "For every file changed in this commit, give both before and after ASTs"
  [repo commit-hash]
  (->> (edited-files repo commit-hash)
       (map #(merge {:file %1}
                    (zipmap [:source-before :source-after]
                            (source-before-after repo commit-hash %1))))
       ))

(s/defn atom-removed-in-commit? :- s/Bool
  [atom-finder :- AtomFinder srcs :- BeforeAfters]
  (exists? (map (partial atom-removed-in-file? atom-finder) srcs)))

(s/defn atoms-changed-in-commit ;:- {s/Str {s/Keyword BACounts}}
  [repo :- Git atoms :- [Atom] commit-hash :- s/Str]
  (->> (commit-files-before-after repo commit-hash)
       (map
        (fn [{file :file
              src-before :source-before
              src-after :source-after}]
          {:file file
           ;{:atoms
            :atoms-counts (atoms-in-file-counts atoms [src-before src-after])
            ;:lines-before 0
                                        ;:lines-after 0}
           }))
       ))

;(atoms-changed-in-commit gcc-repo atoms "c565e664faf3102b80218481ea50e7028ecd646e")

(s/defn parse-commit-for-atom
  ;:- [(s/one s/Str "commit-hash")
  ;    (s/one {s/Str {s/Keyword s/Bool}} "files")
  ;    (s/optional #{[(s/one s/Str "name") (s/one s/Int "id")]} "bugs")]
  [repo atoms rev-commit]
  (let [commit-hash (.name rev-commit)]
    (try
      {:revstr commit-hash
       :atoms (atoms-changed-in-commit repo atoms commit-hash)
       :bug-ids (bugzilla-ids rev-commit)
       }
      (catch Exception e (do (printf "-- exception parsing commit: \"%s\"\n" commit-hash) [commit-hash nil nil]))
      (catch Error e     (do (printf "-- error parsing commit: \"%s\"\n" commit-hash) [commit-hash nil nil]))
      )))

;(parse-commit-for-atom gcc-repo atoms (find-rev-commit gcc-repo "c565e664faf3102b80218481ea50e7028ecd646e"))

(defn atoms-changed-all-commits
  [repo atoms]
  (->>
   (gitq/rev-list repo)
   (pmap (partial parse-commit-for-atom repo atoms))
  ))

(take 2 (atoms-changed-all-commits gcc-repo (take 2 atoms)))

(defn log-atoms-changed-all-commits
  [filename repo atoms]
  (binding [*out* (clojure.java.io/writer filename)]
    (->> atoms
         (atoms-changed-all-commits repo)
         (map prn)
         ;(take 10)
         dorun
         time)))

(defmulti flatten-child (fn [k v parent] (class v)))
(defmethod flatten-child clojure.lang.PersistentVector [k v parent]
  (map #(merge parent %) {k v}))
(defmethod flatten-child :default [k v parent] parent)

(defmulti flatten-res class)
(def m {:a {:b 2 :c 3} :d 4})
(defmethod flatten-res clojure.lang.PersistentArrayMap [m]
 (reduce-kv
   (fn [m k v]
     (cond
       (map? v) v
       (vector? v) v
       :else {k v}
       )
       ) m)
)
(defmethod flatten-res clojure.lang.PersistentVector [v] v)
(defmethod flatten-res :default [x] x)

(apply hash-map (mapcat (fn [[k v]] (map #(vector % k) (pap v))) {:a [:b :c]}))

;(defmethod flatten-res clojure.lang.PersistentHashSet [_] :set)


(defn flatten-res
  "Take the heavily nested structure and flatten it"
  [res]
  (->> res
       (mapcat
        (fn [{revstr :revstr
             atom-counts :atom-counts
             bug-ids :bug-ids}]
          (mapcat
           (fn [[file atoms]] atoms
             (map
              (fn [[atom count]]
                {:revstr revstr
                 :bug-ids bug-ids
                 :file file
                 :atom atom
                 :count count}) atoms)) atom-counts)))))

(defn group-by-atom-bug
  [flat-res]
  (->> flat-res
       (map #(merge %1 {:change (- (apply - (:count %1)))}))
       (map #(merge %1 {:bug? (empty? (:bug-ids %1))}))
       (map #(select-keys % [:atom :change :bug?]))
       (group-by :atom)
       (map-values (partial group-by :bug?))
       (map-values (partial map-values (partial map :change)))))

(defn atom-removal-sums
  [flat-res]
  (->> flat-res
       group-by-atom-bug
       (map-values (partial map-values (partial reduce +)))))

;(def filename "gcc-bugs-atoms_2017-03-20_2.edn")
;(def gcc-bugs (->> filename read-patch-data))
;(def bac-sum (time (sum-bac-by-bugs gcc-bugs)))
;(def flat-gcc-bugs (->> gcc-bugs flatten-res))

;(def atom-bug-groups (group-by-atom-bug flat-gcc-bugs))

;(->> flat-gcc-bugs atom-removal-p-values pprint)
;(->> flat-gcc-bugs atom-removal-sums pprint)

;(def grouped-bugs (->> flat-gcc-bugs group-by-atom-bug))

(defn write-res-csv
  [filename flat-res]
  (with-open [out-file (io/writer filename)]
    (csv/write-csv out-file
                   [(->> flat-res first keys
                        (map #(subs (str %) 1)))])
    (csv/write-csv out-file (->> flat-res (map vals)))))

(defn add-convenience-columns
  [flat-res]
    (for [m flat-res]
      (merge m {:count-before (-> m :count first)
                :count-after (-> m :count last)
                :n-bugs (-> m :bug-ids count)})))

;(write-res-csv "gcc-bugs.csv" (take 10000 (add-convenience-colums flat-gcc-bugs)))

