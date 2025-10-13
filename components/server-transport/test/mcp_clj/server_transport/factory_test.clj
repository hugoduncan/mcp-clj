(ns mcp-clj.server-transport.factory-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [mcp-clj.server-transport.factory :as factory]))

;; Test Helper - Mock Server

(defrecord MockServer
  [config handlers]

  Object

  (toString [_] (str "MockServer(" config ")")))

(defn mock-server-factory
  "Factory function for creating mock servers"
  [options handlers]
  (->MockServer options handlers))

;; Test Setup and Cleanup

(defn clean-registry!
  "Clean registry state for testing"
  []
  ;; Unregister all test transports
  (doseq [transport-type (factory/list-transports)]
    (when (not (#{:http :sse :stdio :in-memory} transport-type))
      (factory/unregister-transport! transport-type)))

  ;; Ensure built-ins are registered
  (when-not (factory/transport-registered? :http)
    (factory/register-transport! :http
                                 (requiring-resolve 'mcp-clj.server-transport.factory/create-http-server)))
  (when-not (factory/transport-registered? :sse)
    (factory/register-transport! :sse
                                 (requiring-resolve 'mcp-clj.server-transport.factory/create-sse-server)))
  (when-not (factory/transport-registered? :stdio)
    (factory/register-transport! :stdio
                                 (requiring-resolve 'mcp-clj.server-transport.factory/create-stdio-server)))
  (when-not (factory/transport-registered? :in-memory)
    (factory/register-transport! :in-memory
                                 (requiring-resolve 'mcp-clj.server-transport.factory/create-in-memory-server))))

;; Registry Tests

(deftest transport-registration-test
  ;; Test transport registration and management
  (testing "transport registration"
    (clean-registry!)

    (testing "registers new transport type"
      (factory/register-transport! :mock mock-server-factory)
      (is (factory/transport-registered? :mock))
      (is (contains? (set (factory/list-transports)) :mock)))

    (testing "unregisters transport type"
      (factory/unregister-transport! :mock)
      (is (not (factory/transport-registered? :mock)))
      (is (not (contains? (set (factory/list-transports)) :mock))))

    (testing "validates transport type is keyword"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Transport type must be a keyword"
            (factory/register-transport! "not-keyword" mock-server-factory))))

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

    (testing "sse transport is registered"
      (is (factory/transport-registered? :sse))
      (is (contains? (set (factory/list-transports)) :sse)))

    (testing "stdio transport is registered"
      (is (factory/transport-registered? :stdio))
      (is (contains? (set (factory/list-transports)) :stdio)))

    (testing "in-memory transport is registered"
      (is (factory/transport-registered? :in-memory))
      (is (contains? (set (factory/list-transports)) :in-memory)))))

;; Factory Function Tests

(deftest create-transport-success-test
  ;; Test successful transport creation
  (testing "create-transport success cases"
    (clean-registry!)
    (factory/register-transport! :mock mock-server-factory)

    (testing "creates registered transport"
      (let [config {:type :mock :option1 "value1" :option2 42}
            handlers {:test-method (fn [_] {:result "ok"})}
            server (factory/create-transport config handlers)]
        (is (instance? MockServer server))
        (is (= {:option1 "value1" :option2 42} (:config server)))
        (is (= handlers (:handlers server)))))

    (testing "passes options without :type"
      (let [config {:type :mock :url "test" :timeout 5000}
            handlers {}
            server (factory/create-transport config handlers)]
        (is (= {:url "test" :timeout 5000} (:config server)))))

    (clean-registry!)))

(deftest create-transport-error-test
  ;; Test error handling in transport creation
  (testing "create-transport error cases"
    (clean-registry!)

    (testing "unregistered transport type"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unregistered transport type"
            (factory/create-transport {:type :nonexistent} {}))))

    (testing "factory function failure"
      (let [failing-factory (fn [_ _] (throw (RuntimeException. "Factory failed")))]
        (factory/register-transport! :failing failing-factory)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Transport factory failed"
              (factory/create-transport {:type :failing} {})))))

    (clean-registry!)))

;; Integration Tests

(deftest pluggable-transport-integration-test
  ;; Test complete workflow of registering and using custom transport
  (testing "pluggable transport integration"
    (clean-registry!)

    (testing "custom transport end-to-end workflow"
      ;; Register custom transport
      (factory/register-transport! :test-transport mock-server-factory)

      ;; Verify registration
      (is (factory/transport-registered? :test-transport))

      ;; Create transport instance
      (let [config {:type :test-transport
                    :custom-option "test-value"
                    :number-option 123}
            handlers {:initialize (fn [_] {:result "initialized"})}
            server (factory/create-transport config handlers)]

        ;; Verify server is created correctly
        (is (instance? MockServer server))
        (is (= {:custom-option "test-value" :number-option 123}
               (:config server)))
        (is (= handlers (:handlers server)))))

    (clean-registry!)))

(deftest registry-state-management-test
  ;; Test registry state management across operations
  (testing "registry state management"
    (clean-registry!)

    (testing "registry survives multiple operations"
      ;; Register multiple transports
      (factory/register-transport! :transport1 mock-server-factory)
      (factory/register-transport! :transport2 mock-server-factory)

      (is (= 6 (count (factory/list-transports)))) ; :http, :sse, :stdio, :in-memory, :transport1, :transport2

      ;; Unregister one
      (factory/unregister-transport! :transport1)
      (is (= 5 (count (factory/list-transports))))
      (is (not (factory/transport-registered? :transport1)))
      (is (factory/transport-registered? :transport2))

      ;; Built-ins still work
      (is (factory/transport-registered? :http))
      (is (factory/transport-registered? :sse))
      (is (factory/transport-registered? :stdio))
      (is (factory/transport-registered? :in-memory)))

    (clean-registry!)))

(deftest unregister-transport-test
  ;; Test unregister-transport! behavior in various scenarios
  (testing "unregister-transport!"
    (clean-registry!)

    (testing "removes registered transport"
      (factory/register-transport! :temp-transport mock-server-factory)
      (is (factory/transport-registered? :temp-transport))
      (factory/unregister-transport! :temp-transport)
      (is (not (factory/transport-registered? :temp-transport)))
      (is (not (contains? (set (factory/list-transports)) :temp-transport))))

    (testing "returns nil"
      (factory/register-transport! :another-temp mock-server-factory)
      (is (nil? (factory/unregister-transport! :another-temp))))

    (testing "is idempotent for non-existent transport"
      (is (nil? (factory/unregister-transport! :never-registered)))
      (is (not (factory/transport-registered? :never-registered))))

    (testing "does not affect other registered transports"
      (factory/register-transport! :transport-a mock-server-factory)
      (factory/register-transport! :transport-b mock-server-factory)
      (factory/unregister-transport! :transport-a)
      (is (not (factory/transport-registered? :transport-a)))
      (is (factory/transport-registered? :transport-b)))

    (clean-registry!)))

(deftest transport-registered-test
  ;; Test transport-registered? predicate function
  (testing "transport-registered?"
    (clean-registry!)

    (testing "returns true for registered transport"
      (factory/register-transport! :test-registered mock-server-factory)
      (is (true? (factory/transport-registered? :test-registered))))

    (testing "returns false for unregistered transport"
      (is (false? (factory/transport-registered? :never-registered))))

    (testing "returns false after unregistration"
      (factory/register-transport! :will-be-removed mock-server-factory)
      (is (true? (factory/transport-registered? :will-be-removed)))
      (factory/unregister-transport! :will-be-removed)
      (is (false? (factory/transport-registered? :will-be-removed))))

    (testing "works with built-in transports"
      (is (true? (factory/transport-registered? :http)))
      (is (true? (factory/transport-registered? :sse)))
      (is (true? (factory/transport-registered? :stdio)))
      (is (true? (factory/transport-registered? :in-memory))))

    (testing "returns false for non-keyword types"
      (is (false? (factory/transport-registered? "string-type")))
      (is (false? (factory/transport-registered? 'symbol-type)))
      (is (false? (factory/transport-registered? nil))))

    (clean-registry!)))
