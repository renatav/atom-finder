(ns atom-finder.classifier-util
  (:require [atom-finder.util :refer :all]
            [clojure.string :as str]
            [schema.core :as s]
            )
  (:use     [clojure.pprint :only [pprint print-table]])
  (:import [org.eclipse.cdt.core.dom.ast IASTNode IASTExpression IASTUnaryExpression IASTLiteralExpression]))

(defn default-finder [classifier] (partial filter-tree classifier))

(defn paren-node?
  "Does this node just represent ()'s"
  [node]
  (and (instance? IASTUnaryExpression node)
       (= (.getOperator node) IASTUnaryExpression/op_bracketedPrimary)))

(def literal-types {
  IASTLiteralExpression/lk_integer_constant :int
  IASTLiteralExpression/lk_float_constant   :float
  IASTLiteralExpression/lk_char_constant    :char
  IASTLiteralExpression/lk_string_literal   :string
  IASTLiteralExpression/lk_this             :this
  IASTLiteralExpression/lk_true             :true
  IASTLiteralExpression/lk_false            :false
  IASTLiteralExpression/lk_nullptr          :null})

(s/defn literal-type :- s/Keyword [node :- IASTExpression]
  (->> node .getKind literal-types))

(s/defn parse-binary-literal :- s/Int
  "Parse a binary literal from string"
  [s :- String]
  (-> s
      (str/replace #"[bB]" "")
      (Long/parseLong 2)))

(s/defn real-number? :- s/Bool
  "Is the number represented by this string a Real number"
  [s :- String]
  (->> s
       (re-find #"[.eE]")
       boolean))

(defmulti radix "In which base is the number specified" class)
(s/defmethod radix String :- s/Keyword
  [num :- s/Str]
  ; This is a simplification. See for details:
  ; cdt/core/org.eclipse.cdt.core/parser/org/eclipse/cdt/internal/core/dom/parser/cpp/CPPASTLiteralExpression.java
  (condp re-find num
    #"^[+-]?0[xX]" :hex
    #"^[+-]?0[bB]" :bin
    #"^[+-]?0([^0-9]|$)" :dec ; Matches "0", "0.1" This may not technically be true buuuut...
    #"^[+-]?0.[0-9]*[.eE]" :dec ; There is no octal float in C
    #"^[+-]?0"  :oct
                :dec
  ))

(s/defmethod radix IASTNode :- s/Keyword [n] :non-literal)

(s/defmethod radix IASTLiteralExpression :- (s/maybe s/Keyword) [node]
  (if (#{:int :float} (literal-type node))
    (radix (String. (.getValue node)))
    :non-numeric))

(s/defn numeric-literal? :- s/Bool
  [node :- IASTNode]
  (not (#{:non-literal :non-numeric} (radix node))))

(s/defn parse-numeric-literal :- (s/maybe s/Num)
  "Parse any valid C++ numeric literal from a string for it's value"
  [s-in :- String]
  (let [s (str/replace s-in #"[uUlL]*$" "")] ; remove suffix
    (condp contains? (radix s)
          #{:oct :dec :hex} (if (real-number? s) (Double/parseDouble s) (Long/decode s))
          #{:bin} (parse-binary-literal s)
          nil)))

(defn log2 [n] (/ (Math/log n) (Math/log 2)))
(def number-bits "How many bits are requited to store an integer value" log2)
