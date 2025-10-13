(ns mcp-clj.client-transport.factory-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.client-transport.factory :as factory]
    [mcp-clj.client-transport.protocol :as transport-protocol]))

;; Test Helper - Mock Transport

(defrecord MockTransport
  [config]

  transport-protocol/Transport

  (send-request!
    [_ method params timeout-ms]
    {:mock-response true :method method :params params :timeout timeout-ms})


  (send-notification!
    [_ method params]
    {:mock-notification true :method method :params params})


  (close! [_] nil)


  (alive? [_] true)


  (get-json-rpc-client [_] nil))

(defn mock-transport-factory
  "Factory function for creating mock transports"
  [options]
  (->MockTransport options))

;; Test Setup and Cleanup

(defn clean-registry!
  "Clean registry state for testing"
  []
  ;; Unregister all test transports
  (doseq [transport-type (factory/list-transports)]
    (when (not (#{:http :stdio} transport-type))
      (factory/unregister-transport! transport-type)))

  ;; Ensure built-ins are registered
  (when-not (factory/transport-registered? :http)
    (factory/register-transport! :http
                                 (requiring-resolve 'mcp-clj.client-transport.http/create-transport)))
  (when-not (factory/transport-registered? :stdio)
    (factory/register-transport! :stdio
                                 (requiring-resolve 'mcp-clj.client-transport.stdio/create-transport))))

;; Registry Tests

(deftest transport-registration-test
  ;; Test transport registration and management
  (testing "transport registration"
    (clean-registry!)

    (testing "registers new transport type"
      (factory/register-transport! :mock mock-transport-factory)
      (is (factory/transport-registered? :mock))
      (is (contains? (set (factory/list-transports)) :mock)))

    (testing "unregisters transport type"
      (factory/unregister-transport! :mock)
      (is (not (factory/transport-registered? :mock)))
      (is (not (contains? (set (factory/list-transports)) :mock))))

    (testing "validates transport type is keyword"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Transport type must be a keyword"
            (factory/register-transport! "not-keyword" mock-transport-factory))))

    (testing "validates factory is function"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Factory must be a function"
            (factory/register-transport! :invalid "not-function"))))

    (clean-registry!)))

(deftest built-in-transports-test
  ;; Test that built-in transports are auto-registered
  (testing "built-in transports"
    (testing "http transport is registered"
      (is (factory/transport-registered? :http))
      (is (contains? (set (factory/list-transports)) :http)))

    (testing "stdio transport is registered"
      (is (factory/transport-registered? :stdio))
      (is (contains? (set (factory/list-transports)) :stdio)))))

;; Factory Function Tests

(deftest create-transport-success-test
  ;; Test successful transport creation
  (testing "create-transport success cases"
    (clean-registry!)
    (factory/register-transport! :mock mock-transport-factory)

    (testing "creates registered transport"
      (let [config {:transport {:type :mock :option1 "value1" :option2 42}}
            transport (factory/create-transport config)]
        (is (satisfies? transport-protocol/Transport transport))
        (is (= {:option1 "value1" :option2 42} (:config transport)))))

    (testing "passes options without :type"
      (let [config {:transport {:type :mock :url "test" :timeout 5000}}
            transport (factory/create-transport config)]
        (is (= {:url "test" :timeout 5000} (:config transport)))))

    (clean-registry!)))

(deftest create-transport-error-test
  ;; Test error handling in transport creation
  (testing "create-transport error cases"
    (clean-registry!)

    (testing "missing transport configuration"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Missing transport configuration"
            (factory/create-transport {}))))

    (testing "unregistered transport type"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unregistered transport type"
            (factory/create-transport {:transport {:type :nonexistent}}))))

    (testing "factory function failure"
      (let [failing-factory (fn [_] (throw (RuntimeException. "Factory failed")))]
        (factory/register-transport! :failing failing-factory)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Transport factory failed"
              (factory/create-transport {:transport {:type :failing}})))))

    (testing "factory returns invalid transport"
      (let [invalid-factory (fn [_] "not-a-transport")]
        (factory/register-transport! :invalid invalid-factory)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Transport factory failed"
              (factory/create-transport {:transport {:type :invalid}})))))

    (clean-registry!)))

;; Integration Tests

(deftest pluggable-transport-integration-test
  ;; Test complete workflow of registering and using custom transport
  (testing "pluggable transport integration"
    (clean-registry!)

    (testing "custom transport end-to-end workflow"
      ;; Register custom transport
      (factory/register-transport! :test-transport mock-transport-factory)

      ;; Verify registration
      (is (factory/transport-registered? :test-transport))

      ;; Create transport instance
      (let [config {:transport {:type :test-transport
                                :custom-option "test-value"
                                :number-option 123}}
            transport (factory/create-transport config)]

        ;; Verify transport is created correctly
        (is (satisfies? transport-protocol/Transport transport))
        (is (= {:custom-option "test-value" :number-option 123}
               (:config transport)))

        ;; Test transport functionality
        (let [request-result (transport-protocol/send-request!
                               transport "test-method" {:arg1 "value1"} 5000)]
          (is (:mock-response request-result))
          (is (= "test-method" (:method request-result)))
          (is (= {:arg1 "value1"} (:params request-result))))

        (let [notification-result (transport-protocol/send-notification!
                                    transport "test-notification" {:arg2 "value2"})]
          (is (:mock-notification notification-result))
          (is (= "test-notification" (:method notification-result)))
          (is (= {:arg2 "value2"} (:params notification-result))))

        ;; Test lifecycle methods
        (is (transport-protocol/alive? transport))
        (transport-protocol/close! transport)))

    (clean-registry!)))

(deftest registry-state-management-test
  ;; Test registry state management across operations
  (testing "registry state management"
    (clean-registry!)

    (testing "registry survives multiple operations"
      ;; Register multiple transports
      (factory/register-transport! :transport1 mock-transport-factory)
      (factory/register-transport! :transport2 mock-transport-factory)

      (is (= 4 (count (factory/list-transports)))) ; :http, :stdio, :transport1, :transport2

      ;; Unregister one
      (factory/unregister-transport! :transport1)
      (is (= 3 (count (factory/list-transports))))
      (is (not (factory/transport-registered? :transport1)))
      (is (factory/transport-registered? :transport2))

      ;; Built-ins still work
      (is (factory/transport-registered? :http))
      (is (factory/transport-registered? :stdio)))

    (clean-registry!)))

(deftest unregister-transport-test
  ;; Test unregister-transport! behavior in various scenarios
  (testing "unregister-transport!"
    (clean-registry!)

    (testing "removes registered transport"
      (factory/register-transport! :temp-transport mock-transport-factory)
      (is (factory/transport-registered? :temp-transport))
      (factory/unregister-transport! :temp-transport)
      (is (not (factory/transport-registered? :temp-transport)))
      (is (not (contains? (set (factory/list-transports)) :temp-transport))))

    (testing "returns nil"
      (factory/register-transport! :another-temp mock-transport-factory)
      (is (nil? (factory/unregister-transport! :another-temp))))

    (testing "is idempotent for non-existent transport"
      (is (nil? (factory/unregister-transport! :never-registered)))
      (is (not (factory/transport-registered? :never-registered))))

    (testing "does not affect other registered transports"
      (factory/register-transport! :transport-a mock-transport-factory)
      (factory/register-transport! :transport-b mock-transport-factory)
      (factory/unregister-transport! :transport-a)
      (is (not (factory/transport-registered? :transport-a)))
      (is (factory/transport-registered? :transport-b)))

    (clean-registry!)))

(deftest transport-registered-test
  ;; Test transport-registered? predicate function
  (testing "transport-registered?"
    (clean-registry!)

    (testing "returns true for registered transport"
      (factory/register-transport! :test-registered mock-transport-factory)
      (is (true? (factory/transport-registered? :test-registered))))

    (testing "returns false for unregistered transport"
      (is (false? (factory/transport-registered? :never-registered))))

    (testing "returns false after unregistration"
      (factory/register-transport! :will-be-removed mock-transport-factory)
      (is (true? (factory/transport-registered? :will-be-removed)))
      (factory/unregister-transport! :will-be-removed)
      (is (false? (factory/transport-registered? :will-be-removed))))

    (testing "works with built-in transports"
      (is (true? (factory/transport-registered? :http)))
      (is (true? (factory/transport-registered? :stdio))))

    (testing "returns false for non-keyword types"
      (is (false? (factory/transport-registered? "string-type")))
      (is (false? (factory/transport-registered? 'symbol-type)))
      (is (false? (factory/transport-registered? nil))))

    (clean-registry!)))
