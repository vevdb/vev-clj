(ns vev.test-runner
  (:require [clojure.test :as test]
            [vev.core-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'vev.core-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
