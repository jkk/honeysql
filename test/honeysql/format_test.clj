(ns honeysql.format-test
  (:refer-clojure :exclude [format])
  (:require [clojure.test :refer [deftest testing is are]]
            [honeysql.format :refer :all]))

(deftest test-quote
  (are
    [qx res]
    (= (apply quote-identifier "foo.bar.baz" qx) res)
    [] "foo.bar.baz"
    [:style :mysql] "`foo`.`bar`.`baz`"
    [:style :mysql :split false] "`foo.bar.baz`")
  (are
    [x res]
    (= (quote-identifier x) res)
    3 "3"
    'foo "foo"
    :foo-bar "foo_bar")
  (is (= (quote-identifier "*" :style :ansi) "*"))
  (is (= (quote-identifier "foo\"bar" :style :ansi) "\"foo\"\"bar\""))
  (is (= (quote-identifier "foo\"bar" :style :oracle) "\"foo\"\"bar\""))
  (is (= (quote-identifier "foo`bar" :style :mysql) "`foo``bar`"))
  (is (= (quote-identifier "foo]bar" :style :sqlserver) "[foo]]bar]")))

(deftest test-dashed-quote
  (binding [*allow-dashed-names?* true]
    (is (= (quote-identifier :foo-bar) "foo-bar"))
    (is (= (quote-identifier :foo-bar :style :ansi) "\"foo-bar\""))
    (is (= (quote-identifier :foo-bar.moo-bar :style :ansi)
           "\"foo-bar\".\"moo-bar\""))))

(deftest test-cte
  (is (= (format-clause
          (first {:with [[:query {:select [:foo] :from [:bar]}]]}) nil)
         "WITH query AS SELECT foo FROM bar"))
  (is (= (format-clause
          (first {:with-recursive [[:query {:select [:foo] :from [:bar]}]]}) nil)
         "WITH RECURSIVE query AS SELECT foo FROM bar"))
  (is (= (format {:with [[[:static {:columns [:a :b :c]}] {:values [[1 2 3] [4 5 6]]}]]})
         ["WITH static (a, b, c) AS (VALUES (?, ?, ?), (?, ?, ?))" 1 2 3 4 5 6]))
  (is (= (format
           {:with [[[:static {:columns [:a :b :c]}]
                    {:values [[1 2 3] [4 5 6]]}]]
            :select [:*]
            :from [:static]})
         ["WITH static (a, b, c) AS (VALUES (?, ?, ?), (?, ?, ?)) SELECT * FROM static" 1 2 3 4 5 6])))

(deftest insert-into
  (is (= (format-clause (first {:insert-into :foo}) nil)
         "INSERT INTO foo"))
  (is (= (format-clause (first {:insert-into [:foo {:select [:bar] :from [:baz]}]}) nil)
         "INSERT INTO foo SELECT bar FROM baz"))
  (is (= (format-clause (first {:insert-into [[:foo [:a :b :c]] {:select [:d :e :f] :from [:baz]}]}) nil)
         "INSERT INTO foo (a, b, c) SELECT d, e, f FROM baz"))
  (is (= (format {:insert-into :letters
                  :columns [:domain_key]
                  :values [["a"] ["b"] ["c"]]})
         ["INSERT INTO letters (domain_key) VALUES (?), (?), (?)" "a" "b" "c"]))
  (is (= (format {:insert-into :letters
                  :columns [:domain_key]
                  :values [["a"] ["b"] ["c"]]
                  :upsert {:mode :mysql
                           :updates {:id :id}}})
         ["INSERT INTO letters (domain_key) VALUES (?), (?), (?) ON DUPLICATE KEY UPDATE id=id" "a" "b" "c"]))
  (is (= (format {:insert-into :letters
                  :columns [:domain_key :rank]
                  :values [["a" 1] ["b" 2] ["c" 3]]
                  :upsert {:mode :mysql
                           :updates {:rank #sql/call [:values :rank]}}})
         ["INSERT INTO letters (domain_key, rank) VALUES (?, 1), (?, 2), (?, 3) ON DUPLICATE KEY UPDATE rank=values(rank)" "a" "b" "c"])))

(deftest exists-test
  (is (= (format {:exists {:select [:a] :from [:foo]}})
         ["EXISTS (SELECT a FROM foo)"]))
  (is (= (format {:select [:id]
                  :from [:foo]
                  :where [:exists {:select [1]
                                   :from [:bar]
                                   :where :deleted}]})
         ["SELECT id FROM foo WHERE EXISTS (SELECT ? FROM bar WHERE deleted)" 1])))

(deftest array-test
  (is (= (format {:insert-into :foo
                  :columns [:baz]
                  :values [[#sql/array [1 2 3 4]]]})
         ["INSERT INTO foo (baz) VALUES (ARRAY[?, ?, ?, ?])" 1 2 3 4]))
  (is (= (format {:insert-into :foo
                  :columns [:baz]
                  :values [[#sql/array ["one" "two" "three"]]]})
         ["INSERT INTO foo (baz) VALUES (ARRAY[?, ?, ?])" "one" "two" "three"])))

(deftest union-test
  (is (= (format {:union [{:select [:foo] :from [:bar1]}
                          {:select [:foo] :from [:bar2]}]})
         ["(SELECT foo FROM bar1) UNION (SELECT foo FROM bar2)"])))

(deftest union-all-test
  (is (= (format {:union-all [{:select [:foo] :from [:bar1]}
                              {:select [:foo] :from [:bar2]}]})
         ["(SELECT foo FROM bar1) UNION ALL (SELECT foo FROM bar2)"])))

(deftest intersect-test
  (is (= (format {:intersect [{:select [:foo] :from [:bar1]}
                              {:select [:foo] :from [:bar2]}]})
         ["(SELECT foo FROM bar1) INTERSECT (SELECT foo FROM bar2)"])))
