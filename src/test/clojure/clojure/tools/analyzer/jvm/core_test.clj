(ns clojure.tools.analyzer.jvm.core-test
  (:refer-clojure :exclude [macroexpand-1])
  (:require [clojure.tools.analyzer :as ana]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.analyzer.env :as env]
            [clojure.tools.analyzer.passes.elide-meta :refer [elides elide-meta]]
            [clojure.tools.analyzer.ast :refer [postwalk]]
            [clojure.test :refer [deftest is]]))

(defprotocol p (f [_]))
(defn f1 [^long x])
(def e (ana.jvm/empty-env))

(defmacro ast [form]
  `(binding [ana/macroexpand-1 ana.jvm/macroexpand-1
             ana/create-var    ana.jvm/create-var
             ana/parse         ana.jvm/parse
             ana/var?          var?
             elides            {:all #{:line :column :file}}
             *ns*              *ns*]
     (env/with-env (ana.jvm/global-env)
       (postwalk (ana/analyze '~form e) elide-meta))))

(defmacro ast1 [form]
  `(binding [ana/macroexpand-1 ana.jvm/macroexpand-1
             ana/create-var    ana.jvm/create-var
             ana/parse         ana.jvm/parse
             ana/var?          var?
             elides            {:all #{:line :column :file}}
             *ns*              *ns*]
     (ana.jvm/analyze '~form e)))

(defmacro mexpand [form]
  `(ana.jvm/macroexpand-1 '~form e))

(deftest macroexpander-test
  (is (= (list '. (list 'do java.lang.Object) 'toString)
         (mexpand (.toString Object))))
  (is (= (list '. java.lang.Integer '(parseInt "2")) (mexpand (Integer/parseInt "2")))))

(deftest analyzer-test

  (let [v-ast (ast #'+)]
    (is (= :the-var (:op v-ast)))
    (is (= #'+ (:var v-ast))))

  (let [mn-ast (ast (monitor-enter 1))]
    (is (= :monitor-enter (:op mn-ast)))
    (is (= 1 (-> mn-ast :target :form))))

  (let [mx-ast (ast (monitor-exit 1))]
    (is (= :monitor-exit (:op mx-ast)))
    (is (= 1 (-> mx-ast :target :form))))

  (let [i-ast (ast (clojure.core/import* "java.lang.String"))]
    (is (= :import (:op i-ast)))
    (is (= "java.lang.String" (:class i-ast))))

  (let [r-ast (ast ^:foo (reify
                           Object (toString [this] "")
                           Appendable (^Appendable append [this ^char x] this)))]
    (is (= :with-meta (-> r-ast :op))) ;; line/column info
    (is (= :reify (-> r-ast :expr :op)))
    (is (= #{Appendable clojure.lang.IObj} (-> r-ast :expr :interfaces)))
    (is (= '#{toString append} (->> r-ast :expr :methods (mapv :name) set))))

  (let [dt-ast (binding [*ns* (the-ns 'user)]
                 (ast (deftype* x user.x [a b]
                        :implements [Appendable]
                        (^Appendable append [this ^char x] this))))]
    (is (= :deftype (-> dt-ast :op)))
    (is (= '[a b] (->> dt-ast :fields (mapv :name))))
    (is (= '[append] (->> dt-ast :methods (mapv :name))))
    (is (= 'user.x (-> dt-ast :class-name))))

  (let [c-ast (ast (case* 1 0 0 :number {2 [2 :two] 3 [3 :three]} :compact :int))]
    (is (= :number (-> c-ast :default :form)))
    (is (= #{2 3} (->> c-ast :tests (mapv (comp :form :test)) set)))
    (is (= #{:three :two} (->> c-ast :thens (mapv (comp :form :then)) set)))
    (is (= 3 (-> c-ast :high)))
    (is (= :int (-> c-ast :test-type)))
    (is (= :compact (-> c-ast :switch-type)))
    (is (= 2 (-> c-ast :low)))
    (is (= 0 (-> c-ast :shift)))
    (is (= 0 (-> c-ast :mask))))

  (is (= Throwable (-> (ast1 (try (catch :default e))) :catches first :class :val)))
  (is (= Exception (-> (ast1 (try (catch Exception e e))) :catches first :body :tag))))

(deftest doseq-chunk-hint
  (let [tree (ast1 (doseq [item (range 10)]
                     (println item)))
        {[_ chunk] :bindings} tree]
    (is (= :loop (:op tree)))
    (is (.startsWith (name (:name chunk)) "chunk"))
    (is (= clojure.lang.IChunk (:tag chunk)))))

(def ^:dynamic x)
(deftest set!-dynamic-var
  (is (ast1 (set! x 1))))

(deftest analyze-proxy
  (is (ast1 (proxy [Object] []))))

(deftest analyze-record
  (is (ast1 (defrecord TestRecord [x y]))))

(deftest eq-no-reflection
  (is (:validated? (-> (ast1 (fn [s] (= s \f))) :methods first :body))))

(deftest analyze+eval-context-test
  (let [do-ast (ana.jvm/analyze+eval '(do 1 2 3))]
    (is (= :ctx/statement (-> do-ast :statements first :env :context)))))

(defmacro my-body [& body]
  `(do ~@body))

(defn change-to-clojure-repl-on-eval []
  (require 'clojure.repl)
  (in-ns 'clojure.repl)
  nil)

(defmacro change-to-clojure-repl-on-mexpand []
  (require 'clojure.repl)
  (in-ns 'clojure.repl)
  nil)

(defn eval-in-fresh-ns [& args]
  (binding [*ns* (create-ns (gensym 'eval-ns))]
    (refer-clojure)
    (apply eval args)))

(defn analyze+eval-in-fresh-ns [& args]
  (binding [*ns* (create-ns (gensym 'analyze+eval-ns))]
    (refer-clojure)
    (apply ana.jvm/analyze+eval args)))

(def chk analyze+eval-in-fresh-ns)

; Notes:
; - what we want is for (:ns env) and *ns* to be in sync as closely as possible
;   - especially during macroexpansion
; - this means order of analysis is particularly important and must follow order of
;   evaluation (similarly, order of macroexpansion)
;   - we mostly do fine here, things like :do, :let, :letfn have ordered :children
;     and we analyze in evaluation order.
;   - unordered collection literals like :set and :map are problematic
;     - but their order of evaluation/macroexpansion is undefined, so if anyone is
;       relying on it, that's not our problem.
; - every time we decide to sync (:ns env), we should call (update-ns-map!)
;   - but it's way more expensive to call (update-ns-map!) and probably way less
;     useful in practice
;     - even compared to the super obscure case of changing *ns* as a macroexpansion side effect!
; - it would be useful to have a platform-independent way of updating (:ns env)
;   - something like (assoc env :ns (:ns (empty-env)))
;   - or perhaps a new ^:dynamic function (assoc env :ns (current-ns-name))

(deftest gilardi-scenario-test
  (is (string? (:result
                 (chk `(do (change-to-clojure-repl-on-mexpand)
                           (~'demunge "a"))))))
  (is (string? (:result
                 (chk `(my-body (change-to-clojure-repl-on-mexpand)
                                (~'demunge "a"))))))
  ; FIXME this should evaluate to "a", as you can see from the test below it
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"Could not resolve var: demunge"
        (string? (:result
                   (chk `(let* []
                           (my-body (change-to-clojure-repl-on-mexpand)
                                    (~'demunge "a"))))))))
  (is (= "a" (eval-in-fresh-ns `(let* []
                                  (my-body (change-to-clojure-repl-on-mexpand)
                                           (~'demunge "a"))))))
  ; FIXME this should evaluate to "a", as you can see from the test below it
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"Could not resolve var: demunge"
        (string? (:result
                   (chk `(let* []
                           (do (change-to-clojure-repl-on-mexpand)
                               (~'demunge "a"))))))))
  (is (= "a" (eval-in-fresh-ns `(let* []
                                  (do (change-to-clojure-repl-on-mexpand)
                                      (~'demunge "a"))))))
  (is (string? (eval-in-fresh-ns `(do (let* []
                                        (change-to-clojure-repl-on-mexpand))
                                      (~'demunge "a")))))
  (is (string? (:result
                 (chk `(do (let* []
                             (change-to-clojure-repl-on-mexpand))
                           (~'demunge "a"))))))
  ; - on eval
  (is (string? (:result
                 (chk `(do (change-to-clojure-repl-on-eval)
                           (~'demunge "a"))))))
  (is (string? (:result
                 (chk `(my-body (change-to-clojure-repl-on-eval)
                                (~'demunge "a"))))))
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"Could not resolve var: demunge"
        (chk `(let* []
                (my-body (change-to-clojure-repl-on-eval)
                         (~'demunge "a"))))))
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"Could not resolve var: demunge"
        (chk `(let* []
                (do (change-to-clojure-repl-on-eval)
                    (~'demunge "a"))))))
  (is (string? (:result
                 (chk `(do (let* []
                             (change-to-clojure-repl-on-eval))
                           (~'demunge "a"))))))

  ; var is interned under let*
  (is (= 1 (:result (chk '(let* [] (def a 1) a)))))

  )
