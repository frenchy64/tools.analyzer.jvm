(ns clojure.tools.analyzer.jvm.infer-types
  (:require [clojure.test :refer :all]
            [clojure.core.typed :as t]
            [clojure.core.typed.runtime-infer :as infer]))

(defn delete-anns [nss]
  (doseq [ns nss]
    (infer/delete-generated-annotations
      ns
      {:ns ns})))

(defn infer-anns [nss]
  (doseq [ns nss]
    (prn "infer" ns)
    (t/runtime-infer :ns ns)))

(def infer-files
  '[clojure.tools.analyzer.jvm
    clojure.tools.analyzer.passes.jvm.box
])

;; FIXME shouldn't need this, but some types
;; don't compile
(delete-anns infer-files)
(apply require (conj infer-files :reload))

(def tests 
  '[clojure.tools.analyzer.jvm.passes-test
    clojure.tools.analyzer.jvm.core-test
    ])

(apply require tests)
(apply run-tests tests)

(infer-anns infer-files)
