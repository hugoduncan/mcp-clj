(ns mcp-clj.mcp-client.subscriptions-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-client.subscriptions :as subs]))

(deftest create-registry-test
  ;; Tests creation of subscription registry with proper structure
  (testing "create-registry"
    (testing "creates registry with all subscription types"
      (let [registry (subs/create-registry)]
        (is (map? registry))
        (is (contains? registry :resource-subscriptions))
        (is (contains? registry :tools-subscriptions))
        (is (contains? registry :prompts-subscriptions))
        (is (instance? clojure.lang.Atom (:resource-subscriptions registry)))
        (is (instance? clojure.lang.Atom (:tools-subscriptions registry)))
        (is (instance? clojure.lang.Atom (:prompts-subscriptions registry)))))

    (testing "initializes with empty collections"
      (let [registry (subs/create-registry)]
        (is (empty? @(:resource-subscriptions registry)))
        (is (empty? @(:tools-subscriptions registry)))
        (is (empty? @(:prompts-subscriptions registry)))))))

(deftest resource-subscription-test
  ;; Tests resource subscription and unsubscription
  (testing "subscribe-resource!"
    (testing "adds callback for URI"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :called)
            result (subs/subscribe-resource! registry "test://uri" callback-fn)]
        (is (= callback-fn result))
        (is (= callback-fn (get @(:resource-subscriptions registry) "test://uri")))))

    (testing "replaces existing callback for same URI"
      (let [registry (subs/create-registry)
            callback1 (fn [_] :first)
            callback2 (fn [_] :second)]
        (subs/subscribe-resource! registry "test://uri" callback1)
        (subs/subscribe-resource! registry "test://uri" callback2)
        (is (= callback2 (get @(:resource-subscriptions registry) "test://uri"))))))

  (testing "unsubscribe-resource!"
    (testing "removes subscription and returns callback"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :called)]
        (subs/subscribe-resource! registry "test://uri" callback-fn)
        (let [removed (subs/unsubscribe-resource! registry "test://uri")]
          (is (= callback-fn removed))
          (is (nil? (get @(:resource-subscriptions registry) "test://uri"))))))

    (testing "returns nil for non-existent subscription"
      (let [registry (subs/create-registry)
            removed (subs/unsubscribe-resource! registry "test://nonexistent")]
        (is (nil? removed))))))

(deftest tools-subscription-test
  ;; Tests tools list subscription and unsubscription
  (testing "subscribe-tools-changed!"
    (testing "adds callback to set"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :called)
            result (subs/subscribe-tools-changed! registry callback-fn)]
        (is (= callback-fn result))
        (is (contains? @(:tools-subscriptions registry) callback-fn))))

    (testing "supports multiple callbacks"
      (let [registry (subs/create-registry)
            callback1 (fn [_] :first)
            callback2 (fn [_] :second)]
        (subs/subscribe-tools-changed! registry callback1)
        (subs/subscribe-tools-changed! registry callback2)
        (is (= 2 (count @(:tools-subscriptions registry))))
        (is (contains? @(:tools-subscriptions registry) callback1))
        (is (contains? @(:tools-subscriptions registry) callback2)))))

  (testing "unsubscribe-tools-changed!"
    (testing "removes callback and returns true"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :called)]
        (subs/subscribe-tools-changed! registry callback-fn)
        (let [removed? (subs/unsubscribe-tools-changed! registry callback-fn)]
          (is (true? removed?))
          (is (not (contains? @(:tools-subscriptions registry) callback-fn))))))

    (testing "returns false for non-existent callback"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :never-added)
            removed? (subs/unsubscribe-tools-changed! registry callback-fn)]
        (is (false? removed?))))))

(deftest prompts-subscription-test
  ;; Tests prompts list subscription and unsubscription
  (testing "subscribe-prompts-changed!"
    (testing "adds callback to set"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :called)
            result (subs/subscribe-prompts-changed! registry callback-fn)]
        (is (= callback-fn result))
        (is (contains? @(:prompts-subscriptions registry) callback-fn))))

    (testing "supports multiple callbacks"
      (let [registry (subs/create-registry)
            callback1 (fn [_] :first)
            callback2 (fn [_] :second)]
        (subs/subscribe-prompts-changed! registry callback1)
        (subs/subscribe-prompts-changed! registry callback2)
        (is (= 2 (count @(:prompts-subscriptions registry))))
        (is (contains? @(:prompts-subscriptions registry) callback1))
        (is (contains? @(:prompts-subscriptions registry) callback2)))))

  (testing "unsubscribe-prompts-changed!"
    (testing "removes callback and returns true"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :called)]
        (subs/subscribe-prompts-changed! registry callback-fn)
        (let [removed? (subs/unsubscribe-prompts-changed! registry callback-fn)]
          (is (true? removed?))
          (is (not (contains? @(:prompts-subscriptions registry) callback-fn))))))

    (testing "returns false for non-existent callback"
      (let [registry (subs/create-registry)
            callback-fn (fn [_] :never-added)
            removed? (subs/unsubscribe-prompts-changed! registry callback-fn)]
        (is (false? removed?))))))

(deftest dispatch-notification-test
  ;; Tests notification dispatching to registered callbacks
  (testing "dispatch-notification!"
    (testing "dispatches resource updated notification"
      (let [registry (subs/create-registry)
            received (atom nil)
            callback-fn (fn [params] (reset! received params))]
        (subs/subscribe-resource! registry "test://uri" callback-fn)
        (let [count (subs/dispatch-notification!
                     registry
                     {:method "notifications/resources/updated"
                      :params {:uri "test://uri" :data "changed"}})]
          (is (= 1 count))
          (is (= {:uri "test://uri" :data "changed"} @received)))))

    (testing "ignores resource notification for unsubscribed URI"
      (let [registry (subs/create-registry)
            count (subs/dispatch-notification!
                   registry
                   {:method "notifications/resources/updated"
                    :params {:uri "test://unsubscribed"}})]
        (is (= 0 count))))

    (testing "dispatches tools list changed notification"
      (let [registry (subs/create-registry)
            call-count (atom 0)
            callback1 (fn [_] (swap! call-count inc))
            callback2 (fn [_] (swap! call-count inc))]
        (subs/subscribe-tools-changed! registry callback1)
        (subs/subscribe-tools-changed! registry callback2)
        (let [count (subs/dispatch-notification!
                     registry
                     {:method "notifications/tools/list_changed"
                      :params {}})]
          (is (= 2 count))
          (is (= 2 @call-count)))))

    (testing "dispatches prompts list changed notification"
      (let [registry (subs/create-registry)
            call-count (atom 0)
            callback1 (fn [_] (swap! call-count inc))
            callback2 (fn [_] (swap! call-count inc))]
        (subs/subscribe-prompts-changed! registry callback1)
        (subs/subscribe-prompts-changed! registry callback2)
        (let [count (subs/dispatch-notification!
                     registry
                     {:method "notifications/prompts/list_changed"
                      :params {}})]
          (is (= 2 count))
          (is (= 2 @call-count)))))

    (testing "ignores unknown notification types"
      (let [registry (subs/create-registry)
            count (subs/dispatch-notification!
                   registry
                   {:method "notifications/unknown/type"
                    :params {}})]
        (is (= 0 count))))))
