(ns naiad.backends.csp
  (:require [clojure.core.async :as async :refer [close! <! >! go]]
            [clojure.core.async.impl.protocols :as aproto]
            [naiad.graph :refer [links]]
            [naiad.error-handlers :refer [print-and-continue]]
            [naiad.metrics :as metrics]))


(defmulti construct! :type)

(defn xf-pipe [to xf from {:keys [error-fn] :as opts
                           :or {error-fn print-and-continue}}]
  (assert error-fn (println error-fn))
  (go (try (let [a (volatile! [])
                 rf (xf (fn [_ v]
                          (vswap! a conj v)))]
             (loop []
               (if-some [v (<! from)]
                 (let [retval (rf nil v)
                       exit? (loop [[h & t] @a]
                               (when h
                                 (if (>! to h)
                                   (recur t)
                                   :exit)))]
                   (if (or exit?
                           (reduced? retval))
                     (do (close! to)
                         (close! from))
                     (do (vreset! a [])
                         (recur))))


                 (let [_ (xf nil)]
                   (doseq [v @a]
                     (>! to v))
                   (close! to)))))
           (catch Throwable t
             (condp = (<! (error-fn t))
               :error/continue (xf-pipe to xf from opts)
               :error/abort (do (close! to)
                                (close! from)))))))

(defmethod construct! :default
  [{:keys [transducer? inputs outputs f] :as node}]
  (assert (and transducer?
            (= (count inputs) 1)
            (= (count outputs) 1))
    (str "Cannot construct node: " node))
  (xf-pipe (:out outputs) f (:in inputs) node))

(defn inject-channels [mappings mp]
  (reduce-kv
    (fn [acc k v]
      (let [c (mappings v)]
        (assert c (str "Mapping not found for " v))
        (assoc acc k c)))
    mp
    mp))

(deftype MetricsWrappedBuffer [b m]
  aproto/Buffer
  (full? [this]
    (aproto/full? b))
  (remove! [this]
    (when-some [v (aproto/remove! b)]
      (metrics/histogram m :buffer-size (count b))
      v))
  (add!* [this v]
    (aproto/add!* b v)
    (metrics/histogram m :buffer-size (count b)))
  (close-buf! [this]
    (aproto/close-buf! b))
  clojure.lang.Counted
  (count [this]
    (count b)))


(defn construct-channel [{:keys [existing-channel metrics buffer buffer/size]}]
  (cond
    existing-channel existing-channel
    :else (let [b (or buffer (async/buffer (or size 1)))
                b (if metrics
                    (->MetricsWrappedBuffer b metrics)
                    b)]
            (async/chan b))))



(defn construct-graph [graph]
  (let [link-ids (links graph)
        chans (zipmap link-ids
                (->> link-ids
                  ;; Get any options for the links
                  (map graph)
                  (map construct-channel)))]
    (reduce-kv
      (fn [acc k {:keys [type] :as node}]
        (if (= type :naiad/link-annotation)
          (assoc acc k node)
          (let [{:keys [inputs outputs closes]} node
                new-node (assoc node
                           :inputs (inject-channels chans inputs)
                           :outputs (inject-channels chans outputs)
                           :closes (inject-channels chans closes))]
            (construct! new-node)
            (assoc acc k new-node))))
      graph
      graph)))
