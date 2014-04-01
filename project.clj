(defproject narrator "0.1.1-SNAPSHOT"
  :description "concise, expressive stream analysis"
  :license {:name "MIT License"
            :url ""}
  :repositories {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :dependencies [[potemkin "0.3.4"]
                 [org.codehaus.jsr166-mirror/jsr166y "1.7.0"]
                 [primitive-math "0.1.3"]
                 [byte-transforms "0.1.2"]
                 [com.clearspring.analytics/stream "2.6.0"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.6.0"]
                                  [criterium "0.4.3"]
                                  [codox-md "0.2.0" :exclusions [org.clojure/clojure]]
                                  [org.clojure/core.async "0.1.0-SNAPSHOT"]
                                  [lamina "0.5.2"]]}}
  :global-vars {*warn-on-reflection* true}
  :test-selectors {:default #(not (or (:stress %) (:benchmark %)))
                   :benchmark :benchmark
                   :stress :stress}
  :plugins [[codox "0.6.6"]]
  :codox {:writer codox-md.writer/write-docs}
  :jvm-opts ^:replace ["-server" "-Xmx2g"]
  :java-source-paths ["src"]
  :javac-options ["-target" "1.5" "-source" "1.5"])
