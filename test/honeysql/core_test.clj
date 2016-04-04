(ns honeysql.core-test
  (:refer-clojure :exclude [format update])
  (:require [clojure.test :refer [deftest testing is]]
            [honeysql.core :as sql]
            [honeysql.helpers :refer :all]))

;; TODO: more tests

(deftest test-select
  (let [m1 (-> (select :f.* :b.baz :c.quux [:b.bla :bla-bla]
                       :%now (sql/raw "@x := 10"))
               ;;(un-select :c.quux)
               (modifiers :distinct)
               (from [:foo :f] [:baz :b])
               (join :draq [:= :f.b :draq.x])
               (left-join [:clod :c] [:= :f.a :c.d])
               (right-join :bock [:= :bock.z :c.e])
               (full-join :beck [:= :beck.x :c.y])
               (where [:or
                       [:and [:= :f.a "bort"] [:not= :b.baz :?param1]]
                       [:< 1 2 3]
                       [:in :f.e [1 (sql/param :param2) 3]]
                       [:between :f.e 10 20]])
               ;;(merge-where [:not= nil :b.bla])
               (group :f.a)
               (having [:< 0 :f.e])
               (order-by [:b.baz :desc] :c.quux [:f.a :nulls-first])
               (limit 50)
               (offset 10))
        m2 {:select [:f.* :b.baz :c.quux [:b.bla :bla-bla]
                     :%now (sql/raw "@x := 10")]
            ;;:un-select :c.quux
            :modifiers :distinct
            :from [[:foo :f] [:baz :b]]
            :join [:draq [:= :f.b :draq.x]]
            :left-join [[:clod :c] [:= :f.a :c.d]]
            :right-join [:bock [:= :bock.z :c.e]]
            :full-join [:beck [:= :beck.x :c.y]]
            :where [:or
                    [:and [:= :f.a "bort"] [:not= :b.baz :?param1]]
                    [:< 1 2 3]
                    [:in :f.e [1 (sql/param :param2) 3]]
                    [:between :f.e 10 20]]
            ;;:merge-where [:not= nil :b.bla]
            :group-by :f.a
            :having [:< 0 :f.e]
            :order-by [[:b.baz :desc] :c.quux [:f.a :nulls-first]]
            :limit 50
            :offset 10}
        m3 (sql/build m2)
        m4 (apply sql/build (apply concat m2))]
    (testing "Various construction methods are consistent"
      (is (= m1 m3 m4)))
    (testing "SQL data formats correctly"
      (is (= ["SELECT DISTINCT f.*, b.baz, c.quux, b.bla AS bla_bla, now(), @x := 10 FROM foo f, baz b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = ? AND b.baz <> ?) OR (1 < 2 AND 2 < 3) OR (f.e in (1, ?, 3)) OR f.e BETWEEN 10 AND 20) GROUP BY f.a HAVING 0 < f.e ORDER BY b.baz DESC, c.quux, f.a NULLS FIRST LIMIT 50 OFFSET 10 "
              "bort" "gabba" 2]
             (sql/format m1 {:param1 "gabba" :param2 2}))))
    (testing "SQL data prints and reads correctly"
      (is (= m1 (read-string (pr-str m1)))))
    (testing "SQL data formats correctly with alternate param naming"
      (is (= (sql/format m1 :params {:param1 "gabba" :param2 2} :parameterizer :postgresql)
             ["SELECT DISTINCT f.*, b.baz, c.quux, b.bla AS bla_bla, now(), @x := 10 FROM foo f, baz b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = $1 AND b.baz <> $2) OR (1 < 2 AND 2 < 3) OR (f.e in (1, $3, 3)) OR f.e BETWEEN 10 AND 20) GROUP BY f.a HAVING 0 < f.e ORDER BY b.baz DESC, c.quux, f.a NULLS FIRST LIMIT 50 OFFSET 10 "
              "bort" "gabba" 2])))
    (testing "Locking"
      (is (= ["SELECT DISTINCT f.*, b.baz, c.quux, b.bla AS bla_bla, now(), @x := 10 FROM foo f, baz b INNER JOIN draq ON f.b = draq.x LEFT JOIN clod c ON f.a = c.d RIGHT JOIN bock ON bock.z = c.e FULL JOIN beck ON beck.x = c.y WHERE ((f.a = ? AND b.baz <> ?) OR (1 < 2 AND 2 < 3) OR (f.e in (1, ?, 3)) OR f.e BETWEEN 10 AND 20) GROUP BY f.a HAVING 0 < f.e ORDER BY b.baz DESC, c.quux, f.a NULLS FIRST LIMIT 50 OFFSET 10 FOR UPDATE "
              "bort" "gabba" 2]
             (sql/format (assoc m1 :lock {:mode :update})
                         {:param1 "gabba" :param2 2}))))))

(deftest test-cast
  (is (= ["SELECT foo, CAST(bar AS integer)"]
         (sql/format {:select [:foo (sql/call :cast :bar :integer)]})))
  (is (= ["SELECT foo, CAST(bar AS integer)"]
         (sql/format {:select [:foo (sql/call :cast :bar 'integer)]}))))

(deftest test-value
  (is (= ["INSERT INTO foo (bar) VALUES (?)" {:baz "my-val"}]
         (->
           (insert-into :foo)
           (columns :bar)
           (values [[(honeysql.format/value {:baz "my-val"})]])
           sql/format))))

(deftest test-operators
  (testing "="
    (testing "with nil"
      (is (= ["SELECT * FROM customers WHERE name IS NULL"]
             (sql/format {:select [:*]
                          :from [:customers]
                          :where [:= :name nil]})))
      (is (= ["SELECT * FROM customers WHERE name = ?" nil]
             (sql/format {:select [:*]
                          :from [:customers]
                          :where [:= :name :?name]}
                         {:name nil})))))
  (testing "in"
    (doseq [[cname coll] [[:vector []] [:set #{}] [:list '()]]]
      (testing (str "with values from a " (name cname))
        (let [values (conj coll 1)]
          (is (= ["SELECT * FROM customers WHERE (id in (1))"]
                 (sql/format {:select [:*]
                              :from [:customers]
                              :where [:in :id values]})))
          (is (= ["SELECT * FROM customers WHERE (id in (?))" 1]
                 (sql/format {:select [:*]
                              :from [:customers]
                              :where [:in :id :?ids]}
                             {:ids values}))))))
    (testing "with more than one integer"
      (let [values [1 2]]
        (is (= ["SELECT * FROM customers WHERE (id in (1, 2))"]
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id values]})))
        (is (= ["SELECT * FROM customers WHERE (id in (?, ?))" 1 2]
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id :?ids]}
                           {:ids values})))))
    (testing "with more than one string"
      (let [values ["1" "2"]]
        (is (= ["SELECT * FROM customers WHERE (id in (?, ?))" "1" "2"]
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id values]})
               (sql/format {:select [:*]
                            :from [:customers]
                            :where [:in :id :?ids]}
                           {:ids values})))))))

(deftest test-case
  (is (= ["SELECT CASE WHEN foo < 0 THEN -1 WHEN (foo > 0 AND (foo mod 2) = 0) THEN (foo / 2) ELSE 0 END FROM bar"]
         (sql/format
          {:select [(sql/call
                     :case
                     [:< :foo 0] -1
                     [:and [:> :foo 0] [:= (sql/call :mod :foo 2) 0]] (sql/call :/ :foo 2)
                     :else 0)]
           :from [:bar]})))
  (let [param1 1
        param2 2
        param3 "three"]
    (is (= ["SELECT CASE WHEN foo = ? THEN 0 WHEN foo = bar THEN ? WHEN bar = 0 THEN (bar * ?) ELSE ? END FROM baz"
            param1 param2 param3 "param4"]
           (sql/format
            {:select [(sql/call
                       :case
                       [:= :foo :?param1] 0
                       [:= :foo :bar] (sql/param :param2)
                       [:= :bar 0] (sql/call :* :bar :?param3)
                       :else "param4")]
             :from [:baz]}
            {:param1 param1
             :param2 param2
             :param3 param3})))))

(deftest test-raw
  (is (= ["SELECT 1 + 1 FROM foo"]
         (-> (select (sql/raw "1 + 1"))
             (from :foo)
             sql/format))))

(deftest test-call
  (is (= ["SELECT min(?) FROM ?" "time" "table"]
         (-> (select (sql/call :min "time"))
             (from "table")
             sql/format))))

(deftest join-test
  (testing "nil join"
    (is (= ["SELECT * FROM foo INNER JOIN x ON foo.id = x.id INNER JOIN y"]
           (-> (select :*)
               (from :foo)
               (join :x [:= :foo.id :x.id] :y nil)
               sql/format)))))

(deftest distinct-on
  (testing "Distinct on"
    (is (= ["SELECT DISTINCT ON (name) name, created_by"]
           (-> (select (sql/call :distinct-on :name) :name :created_by)
               sql/format)))
    (is (= ["SELECT DISTINCT ON (name, created_by) name, created_by"]
           (-> (select (sql/call :distinct-on :name :created_by) :name :created_by)
               sql/format)))))
