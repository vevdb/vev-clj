(ns vev.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [vev.core :as d]))

(def report-keys #{:db-before :db-after :tx-data :tempids})

(deftest peer-shaped-transaction-report
  (with-open [conn (d/create-conn)]
    (let [report (d/transact conn [{:db/id "ada" :person/name "Ada"}])
          datom (first (filter #(= :person/name (:a %)) (:tx-data report)))]
      (is (= report-keys (set (keys report))))
      (is (= #{} (d/q '[:find ?e :where [?e :person/name "Ada"]]
                       (:db-before report))))
      (is (= 1 (count (d/q '[:find ?e :where [?e :person/name "Ada"]]
                            (:db-after report)))))
      (is (instance? vev.core.Datom datom))
      (is (= (:e datom) (nth datom 0)))
      (is (= (:a datom) (nth datom 1)))
      (is (= (:v datom) (nth datom 2)))
      (is (= (:tx datom) (nth datom 3)))
      (is (= (:added datom) (nth datom 4))))))

(deftest rejected-transactions-throw-or-return-explicit-result
  (with-open [conn (d/create-conn)]
    (testing "canonical operation throws structured ExceptionInfo"
      (let [error (try
                    (d/transact conn [[:db/add 1 :person/name nil]])
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (some? error))
        (is (= :vev.error/transaction-failed
               (:vev/error (ex-data error))))
        (is (string? (:vev/message (ex-data error))))))
    (testing "Vev extension preserves result-style control flow"
      (let [result (d/try-transact
                    conn
                    [[:db/add 1 :person/name nil]])]
        (is (false? (:ok result)))
        (is (instance? clojure.lang.ExceptionInfo (:error result)))))))

(deftest with-and-sync-return-direct-values
  (with-open [conn (d/create-conn)]
    (d/transact conn [{:db/id 1 :person/name "Ada"}])
    (with-open [snapshot (d/db conn)
                synchronized (d/sync conn)]
      (is (false? (d/is-history snapshot)))
      (with-open [history (d/history snapshot)]
        (is (true? (d/is-history history))))
      (let [report (d/with snapshot
                           [[:db/add 1 :person/name "Augusta Ada"]])]
        (is (= report-keys (set (keys report))))
        (is (= "Ada"
               (d/q '[:find ?name . :where [1 :person/name ?name]]
                    (:db-before report))))
        (is (= "Augusta Ada"
               (d/q '[:find ?name . :where [1 :person/name ?name]]
                    (:db-after report)))))
      (let [error (try
                    (d/with snapshot [[:db/add 1 :person/name nil]])
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= :vev.error/transaction-failed
               (:vev/error (ex-data error)))))
      (is (= (d/basis-t snapshot) (d/basis-t synchronized))))))

(deftest schema-introspection-tempids-and-squuids
  (with-open [conn (d/create-conn)]
    (d/transact conn
                [{:db/id 100
                  :db/ident :person/email
                  :db/valueType :db.type/string
                  :db/cardinality :db.cardinality/one
                  :db/unique :db.unique/identity}
                 {:db/id 101
                  :db/ident :person/number
                  :db/valueType :db.type/long
                  :db/cardinality :db.cardinality/one
                  :db/unique :db.unique/identity}])
    (let [report (d/transact conn
                             [{:db/id "person"
                               :person/email "ada@example.com"
                               :person/number 1815}])
          db (:db-after report)
          attr (d/attribute db :person/email)
          stats (d/db-stats db)]
      (is (= 100 (:id attr)))
      (is (= :person/email (:ident attr)))
      (is (= :db.type/string (:value-type attr)))
      (is (= :db.cardinality/one (:cardinality attr)))
      (is (= :db.unique/identity (:unique attr)))
      (is (true? (:has-avet attr)))
      (is (= (get (:tempids report) "person")
             (d/resolve-tempid db (:tempids report) "person")))
      (is (= (:db/id (d/entity db [:person/email "ada@example.com"]))
             (:db/id (d/entity db [:person/number 1815]))))
      (is (some? (d/entid db [:person/number 1815])))
      (is (= :person/email (d/ident db 100)))
      (is (= ["ada@example.com"]
             (mapv :v (d/datoms db :avet 100))))
      (is (= ["ada@example.com"]
             (->> (d/seek-datoms db :avet 100 "a")
                  (take-while #(= :person/email (:a %)))
                  (mapv :v))))
      (is (= ["ada@example.com"]
             (mapv :v (d/index-range db 100 "a" "b"))))
      (is (= [{:person/email "ada@example.com"}]
             (d/index-pull db
                           {:index :avet
                            :selector [:person/email]
                            :start [:person/email]})))
      (is (pos? (:datoms stats)))
      (is (= {:count 1} (get-in stats [:attrs :person/email])))))
  (let [before (System/currentTimeMillis)
        value (d/squuid)
        encoded (d/squuid-time-millis value)
        after (System/currentTimeMillis)]
    (is (instance? java.util.UUID value))
    (is (<= (- before 999) encoded after))))
