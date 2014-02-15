(defproject org.clojure/tools.analyzer.jvm "0.1.0-typed-SNAPSHOT"
  :description "Additional jvm-specific passes for tools.analyzer."
  :url "https://github.com/clojure/tools.analyzer.jvm"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src/main/clojure"]
  :test-paths ["src/test/clojure"]
  :profiles {:dev {:repl-options {:port 64523}}}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/core.memoize "0.5.6"]
                 [org.clojure/core.typed "0.2.31"]
                 [org.clojure/tools.analyzer "0.1.0-typed-SNAPSHOT"]
                 [org.ow2.asm/asm-all "4.1"]])
