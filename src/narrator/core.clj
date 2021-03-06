(ns narrator.core
  (:use
    [potemkin])
  (:require
    [clojure.edn :as edn]
    [clojure.core.reducers :as r]
    [narrator.utils.rand :as rand])
  (:import
    [java.util ArrayList]))

;;;

(definterface+ IBufferedAggregator
  (process! [_ msg])
  (flush-operator [_]))

(definterface+ StreamOperatorGenerator
  (recur-to! [_ generator])
  (deserializer [_])
  (serializer [_])
  (aggregator? [_])
  (emitter- [_])
  (combiner [_])
  (concurrent? [_])
  (create [_ options])
  (descriptor [_]))

(defn emitter [x]
  (or
    (when (instance? StreamOperatorGenerator x)
      (emitter- x))
    (-> x meta ::emitter)
    identity))

(defn deref' [x]
  ((emitter x) @x))

(definterface+ StreamOperator
  (reducer [_])
  (reset-operator! [_])
  (process-all! [_ msgs]))

;;;

(defn stream-processor
  "A stream operator which for each message emits zero or more messages.  It must be given
   a `reducer` parameter, which describes the reducer function that processes the messages.

   Optional parameters are:

   `reset` - a no-arg function which can be used to reset internal state, if it exists"
  [& {:keys [reset reducer]}]
  (assert reducer)
  (reify StreamOperator
    (reset-operator! [_] (when reset (reset)))
    (reducer [_] reducer)))

(defn stream-processor-generator
  [& {:keys [concurrent? create descriptor]}]
  (assert create)
  (reify StreamOperatorGenerator
    (recur-to! [_ _])
    (emitter- [_] identity)
    (combiner [_] nil)
    (aggregator? [_] false)
    (concurrent? [_] concurrent?)
    (create [_ options] (create options))
    (descriptor [_] descriptor)))

(defn stream-aggregator
  "A stream operator which accepts messages, and can be dereferenced to get a description of
   all messages it has seen.  It must be given a `process` parameter, which is a single-arg
   function that takes a sequence of messages, and a `deref` parameter which is a no-arg
   function that returns a description of all messages it has seen.

   Optional parameters are:

   `reset` - a no-arg function which resets internal state
   `flush` - a no-arg function which flushes any messages which are currently buffered
   "
  [& {:keys [reset process flush deref]}]
  (assert (and process deref))
  (reify
    StreamOperator
     (reducer [_] nil)
    (reset-operator! [_] (when reset (reset)))
    (process-all! [_ msgs] (process msgs))

    IBufferedAggregator
    (process! [_ msg] (process [msg]))
    (flush-operator [_] (when flush (flush)))

    clojure.lang.IDeref
    (deref [_] (deref))))

(defn stream-aggregator-generator
  [& {:keys [concurrent? create descriptor combine emit serialize deserialize recur-to]
      :or {emit identity
           deserialize identity
           serialize identity}}]
  (assert create)
  (reify StreamOperatorGenerator
    (recur-to! [_ generator] (when recur-to (recur-to generator)))
    (serializer [_] serialize)
    (deserializer [_] deserialize)
    (emitter- [_] emit)
    (combiner [_] combine)
    (aggregator? [_] true)
    (concurrent? [_] concurrent?)
    (create [_ options] (with-meta (create options) {::emitter emit}))
    (descriptor [_] descriptor)))

(defn reducer-op
  "Returns a concurrent stream operator that applies the reducer `f` over the message stream."
  [f]
  (stream-processor-generator
    :concurrent? true
    :create (constantly
              (stream-processor
                :reducer f))))

(defn map-op
  "Returns a concurrent stream operator that maps `f` over every message."
  [f]
  (reducer-op (r/map f)))

(defn mapcat-op
  "Returns a concurrent stream operator that mapcats `f` over every message."
  [f]
  (reducer-op (r/mapcat f)))

(defn monoid-aggregator
  "Returns a concurrent stream aggregator that combines messages via the two-arity `combine`
   function, starting with an initial value from the zero-arity `initial`. If the combined
   value needs to be processed before emitting, a custom `emit` function may be defined."
  [& {:keys [initial combine pre-process emit clear-on-reset? serialize deserialize recur-to]
      :or {emit identity
           clear-on-reset? true
           serialize identity
           deserialize identity}}]
  (stream-aggregator-generator
    :recur-to recur-to
    :serialize serialize
    :deserialize deserialize
    :concurrent? false
    :combine combine
    :emit emit
    :create (fn [{:keys [serialize deserialize]}]
              (let [val (atom (initial))]
                (stream-aggregator
                  :reset (when clear-on-reset? #(reset! val (initial)))
                  :deref #(deref val)
                  :process (fn [msgs]
                             (let [msgs (if pre-process
                                          (map pre-process msgs)
                                          msgs)
                                   val' (reduce #(combine [%1 %2]) (initial) msgs)]
                               (swap! val #(combine [%1 %2]) val'))))))))

;;;

(defmacro defn-operator [name & rest]
  `(do
     (defn ~name ~@rest)
     (alter-meta! (var ~name) assoc ::generator-generator true)
     (alter-var-root (var ~name) (fn [f#] (with-meta f# {::generator-generator true})))
     (var ~name)))

(defn create-stream-processor [gen]
  (if (aggregator? gen)
    (let [combiner-fn (or (combiner gen) first)
          emitter-fn (emitter gen)
          op (create gen nil)]
      (stream-processor
        :reducer (r/map
                   (fn [x]
                     (when-not (nil? x)
                       (reset-operator! op)
                       (process! op x)
                       (flush-operator op)
                       (->> [(deref' op)] combiner-fn))))))
    (create gen nil)))

(declare accumulator split)

(defn ->operator-generator [x]
  (cond
    (instance? StreamOperatorGenerator x) x
    (-> x meta ::generator-generator) (x)
    (map? x) (split x)
    (ifn? x) (map-op x)
    :else (throw (IllegalArgumentException. (str "Don't know how to handle " (pr-str x))))))

(defn- combine-processors [fs]
  (when-let [fs (->> fs
                  (map reducer)
                  reverse
                  seq)]
    (apply comp fs)))

(defn compile-operators
  "Takes a descriptor of stream operations, and returns a function that generates a single
   stream operator that is the composition of all described operators."
  [op-descriptor]
  (cond
    (-> op-descriptor meta ::compiled)
    op-descriptor

    (not (sequential? op-descriptor))
    (compile-operators [op-descriptor])

    :else
    (let [generators (map ->operator-generator op-descriptor)
          [pre [aggr & post]] [(take-while (complement aggregator?) generators)
                               (drop-while (complement aggregator?) generators)]]
      (if-not aggr
        (compile-operators (concat op-descriptor [(accumulator)]))
        (let [generator (promise)
              combiner (when (every? concurrent? pre) (combiner aggr))
              concurrent? (and (every? concurrent? pre) (concurrent? aggr))]
          (deliver generator
            (with-meta
              (stream-aggregator-generator
                :recur-to #(recur-to! aggr %)
                :descriptor op-descriptor
                :serialize (serializer aggr)
                :deserialize (deserializer aggr)
                :concurrent? concurrent?
                :combine combiner
                :emit (let [aggr-emitter (emitter aggr)]
                        (if post
                          (fn [x]
                            (let [post (->> post
                                         (map create-stream-processor)
                                         combine-processors)]
                              (first (into [] (post [(aggr-emitter x)])))))
                          aggr-emitter))
                :create (fn [{:keys [aggregator-generator-wrapper
                                     compiled-operator-wrapper]
                              :or {compiled-operator-wrapper (fn [op _] op)
                                   aggregator-generator-wrapper identity}
                              :as options}]
                          (let [pre (map create-stream-processor pre)
                                post (map create-stream-processor post)
                                ops (concat pre post)
                                pre (combine-processors pre)
                                post (combine-processors post)
                                aggr (create (aggregator-generator-wrapper aggr) options)
                                ops (conj ops aggr)
                                process-fn (if pre
                                             (if concurrent?
                                               #(process-all! aggr (r/foldcat (pre %)))
                                               #(process-all! aggr (into [] (pre %))))
                                             #(process-all! aggr %))
                                flush-ops (filterv #(instance? IBufferedAggregator %) ops)]
                            (compiled-operator-wrapper
                              (stream-aggregator
                                :concurrent? concurrent?
                                :reset #(doseq [r ops] (reset-operator! r))
                                :flush #(doseq [r flush-ops] (flush-operator r))
                                :deref #(deref aggr)
                                :process process-fn)
                              options))))
              {::compiled true}))
          @generator)))))

(defn compile-operators*
  "Given a descriptor of stream operations, returns an instance of an operator that is the
   composition of all operations."
  ([op-descriptor]
     (compile-operators* op-descriptor nil))
  ([op-descriptor options]
     (create (compile-operators op-descriptor) options)))

;;;

(defn-operator accumulator
  "Yields a list of all messages seen since it has been reset."
  []
  (stream-aggregator-generator
    :concurrent? true
    :combine #(apply concat %)
    :create (fn [options]
              (let [acc (atom (ArrayList.))]
                (stream-aggregator
                  :process #(locking acc (.addAll ^ArrayList @acc %))
                  :deref #(deref acc)
                  :reset #(clojure.core/reset! acc (ArrayList.)))))))

(defn-operator split
  ""
  [name->ops]
  (let [ks (keys name->ops)
        generators (map compile-operators (vals name->ops))]
    (stream-aggregator-generator
      :recur-to #(doseq [g generators] (recur-to! g %))
      :descriptor name->ops
      :concurrent? (every? concurrent? generators)
      :serialize (fn [m]
                   (zipmap ks
                     (map
                       (fn [f k] (f (get m k)))
                       (map serializer generators)
                       ks)))
      :deserialize (fn [m]
                     (zipmap ks
                       (map
                         (fn [f k] (f (get m k)))
                         (map deserializer generators)
                         ks)))
      :combine (when (->> generators
                       (map combiner)
                       (every? (complement nil?)))
                 (fn [xs]
                   (let [cs (map combiner generators)]
                     (zipmap
                       ks
                       (map
                         (fn [f k]
                           (->> xs
                             (map #(get % k ::none))
                             (remove #(identical? ::none %))
                             (f)))
                         cs
                         ks)))))
      :emit (fn [m]
              (let [emitters (map emitter generators)]
                (zipmap
                  ks
                  (map #(%1 (get m %2)) emitters ks))))
      :create (fn [options]
                (let [ops (map
                            #(create %
                               (assoc options
                                 :execution-affinity (when-not (concurrent? %)
                                                       (rand/rand-int))))
                            generators)]
                  (stream-aggregator
                    :process (fn [msgs]
                               (doseq [op ops]
                                 (process-all! op msgs)))
                    :flush #(doseq [x ops]
                              (flush-operator x))
                    :deref #(zipmap ks (map deref ops))
                    :reset #(doseq [x ops]
                              (reset-operator! x))))))))
