(in-ns 'atom-finder.util)
(import '(org.eclipse.cdt.core.dom.ast
          IASTNode IASTExpression IASTExpressionList IASTUnaryExpression))

(s/defn atom-map-to-seq
  [atoms :- {s/Keyword [IASTNode]}]
  (->> atoms (mapcat (fn [[k atoms]] (map #(array-map :type k :node %) atoms))) (sort-by (comp offset :node))))

(s/defn find-all-atoms-seq :- [{:type s/Keyword :node IASTNode}]
  [node :- IASTNode]
  (->> node find-all-atoms atom-map-to-seq))

(s/defn atom-map-diff :- (s/maybe {s/Keyword [{:type s/Keyword :node IASTNode}]})
  [before :- {s/Keyword [IASTNode]} after :- {s/Keyword [IASTNode]}]
   (->>
    (diff-by (comp write-node :node) (atom-map-to-seq before) (atom-map-to-seq after))
    (map (partial-right select-keys [:original :revised]))
    (apply merge-with concat)))

(s/defn atom-seq-diff
  [before :- [IASTNode] after :- [IASTNode]]
   (->>
    (diff-by write-node before after)
    (map (partial-right select-keys [:original :revised]))
    (apply merge-with concat)))

(s/defn author-name [rev-commit :- RevCommit]
  (-> rev-commit .getAuthorIdent .getName))

(s/defn author-email [rev-commit :- RevCommit]
  (-> rev-commit .getAuthorIdent .getEmailAddress))

(s/defn intersects-lines?
  [range-set node :- IASTNode]
  (.intersects range-set
               (com.google.common.collect.Range/closed
                (start-line node) (end-line node))))

(s/defn contained-by-lines?
  [range-set node :- IASTNode]
  (or (.contains range-set (start-line node))
      (.contains range-set (end-line node))))

(s/defn has-lines? [node]
  (and (start-line node) (end-line node)))

(s/defn added-atoms
  "Ignore parts of the files not contained in the patch"
  [srcs]
  (let [patch-bounds (-<>> srcs :patch-str (patch-file <> (:file srcs))
                           patch-change-bounds flatten1 (map change-bound-to-ranges))
        [old-bounds new-bounds] (->> patch-bounds (map #(select-values % [:old :new]))
                                     transpose (map range-set-co))
        {_ :original new-atoms     :revised}
          (atom-map-diff
           (->> srcs :atoms-before (map-values (partial filter #(or (not (has-lines? %)) (contained-by-lines? old-bounds %)))))
           (->> srcs :atoms-after  (map-values (partial filter #(or (not (has-lines? %)) (contained-by-lines? new-bounds %))))))
        {_ :original new-non-atoms :revised}
          (atom-seq-diff
           (->> srcs :non-atoms-before (filter #(or (not (has-lines? %)) (contained-by-lines? old-bounds %))))
           (->> srcs :non-atoms-after  (filter #(or (not (has-lines? %)) (contained-by-lines? new-bounds %)))))]

    {
     :rev-str (:rev-str srcs)
     :file (:file srcs)
     :added-atoms new-atoms
     :added-non-atoms new-non-atoms
     :author-name  (->> srcs :rev-commit author-name)
     :author-email (->> srcs :rev-commit author-email)
     }))

(s/defn added-atoms-count
  [srcs]
  (-> srcs added-atoms
      (update-in [:added-atoms] (partial frequencies-by :type))
      (update-in [:added-non-atoms] count)))
