(ns naiad.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [naiad :as df]))

(defmacro flow-result [& expr]
  `(let [p# (promise)]
     (df/flow
       (df/promise-accumulator p# (do ~@expr)))
     @p#))

(df/graph
  (df/map inc (df/map dec (df/map #(+ % %) [1 2 3]))))

(df/graph
  (let [data [1 2 3]]
    (flow-result
      (df/filter even? [1 2 3 4]))))

(deftest map-tests
  (testing "basic map usage"
    (is (= (flow-result
             (df/map inc [1 2 3 4]))
          [2 3 4 5])))

  (testing "map with multiple input"
    (is (= (flow-result
             (df/map vector [1 2 3 4] [5 6 7 8]))
          [[1 5] [2 6] [3 7] [4 8]]))

    (is (= (flow-result
             (df/map + [1 2] [1 2] [1 2] [1 2]))
          [4 8])))

  (testing "map chaining"
    (is (= (flow-result
             (df/map inc (df/map dec (df/map #(+ % %) [1 2 3]))))
          [2 4 6]))

    (is (= (flow-result
             (let [data [1 2 3]]
               (df/map +
                 (df/map dec data)
                 (df/map inc data))))
          [2 4 6]))))


(deftest basic-tansducer-tests
  (testing "filter"
    (is (= (flow-result
             (df/filter even? [1 2 3 4]))
          [2 4])))

  (testing "take"
    (is (= (flow-result
             (df/take 3 (df/filter even? [1 2 3 4 5 6 7 8])))
          [2 4 6]))

    (testing "constructor form"
      (is (= (flow-result
               (df/->take :n 3 :in (df/filter even? (range 8)))))
        [2 3 4])))

  (testing "partition"
    (is (= (flow-result
             (df/partition-all 2 [1 2 3 4]))
          [[1 2] [3 4]]))

    ))

(deftest multi-use-channels
  (testing "data distribution"
    (is (= (flow-result
             (let [filtered (df/filter pos? [1 2])]
               (df/map +
                 (df/map inc filtered)
                 (df/map dec filtered))))
          [2 4]))))

(deftest transducer-fusing
  (testing "multiple simple transducers become one"
    (let [make-graph (fn []
                       (->> [-1 1 2 3 -33]
                         (df/filter pos?)
                         (df/filter even?)
                         (df/filter integer?)
                         (df/filter number?)))]
      (is (= (flow-result
               (make-graph))
            [2]))

      (is (= (count (df/graph
                      (make-graph)))
            2)))))

(deftest merge-tests
  (testing "basic merge usage"
    (is (= (set (flow-result
                  (df/merge [1 2] [3 4] [5 6])))
          #{1 2 3 4 5 6}))))


(deftest parallel->>-tests
  (testing "basic parallel usage"
    (is (= (set (flow-result
                  (->> [1 2 3 4 5 6 7]
                    (df/parallel->> {:threads 3}
                      (df/map inc))
                    (df/take 7))))
          #{2 3 4 5 6 7 8}))))


(deftest channel-data-sources
  (testing "can use a bare channel as a data source"
    (is (= (let [input (async/to-chan [1 2 3])]
             (flow-result
               (df/map inc input)))
          [2 3 4])))

  (testing "can use a bare channel as a data sink"
    (is (= (let [output (async/chan 3)]
             (df/flow
               (df/->take :n 1 :in (range 10) :out output))
             [(async/<!! output)
              (async/<!! output)])
          [0 nil]))))


(deftest subscribe-test
  (df/graph
    (let [result (df/subscribe :something [1 2 3 4])]
      (is (= (:foo result) (:foo result)))
      (is (not= (:foo result) (:bar result))))

    (let [{:keys [foo bar] :as r} (df/subscribe identity [:foo :bar])]
      (is (not= foo bar))
      (is (= foo (:foo r)))))

  (is (= (set (flow-result
                (let [{evens true odds false} (df/subscribe even? (range 10))]
                  (df/merge evens odds))))
        (set (range 10)))))

#_(naiad.backends.graphviz/output-dotfile (df/graph
                                          (let [{evens true odds false} (df/subscribe even? (range 10))]
                                            (df/merge evens odds))
                                          )
  "test.dot")
