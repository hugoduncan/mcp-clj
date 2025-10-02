(ns mcp-clj.mcp-server.subscriptions-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mcp-clj.mcp-server.subscriptions :as subs]))

(deftest subscribe-test
  (testing "subscribe!"
    (testing "adds a subscription for a session to a resource"
      (let [subscriptions {}
            result (subs/subscribe! subscriptions "session-1" "file:///test.txt")]
        (is (= {"file:///test.txt" #{"session-1"}} result))))

    (testing "adds multiple sessions to the same resource"
      (let [subscriptions {"file:///test.txt" #{"session-1"}}
            result (subs/subscribe! subscriptions "session-2" "file:///test.txt")]
        (is (= {"file:///test.txt" #{"session-1" "session-2"}} result))))

    (testing "adds subscriptions to multiple resources"
      (let [subscriptions {"file:///test.txt" #{"session-1"}}
            result (subs/subscribe! subscriptions "session-1" "file:///other.txt")]
        (is (= {"file:///test.txt" #{"session-1"}
                "file:///other.txt" #{"session-1"}}
               result))))))

(deftest unsubscribe-test
  (testing "unsubscribe!"
    (testing "removes a subscription for a session from a resource"
      (let [subscriptions {"file:///test.txt" #{"session-1" "session-2"}}
            result (subs/unsubscribe! subscriptions "session-1" "file:///test.txt")]
        (is (= {"file:///test.txt" #{"session-2"}} result))))

    (testing "removes the resource entry when last subscriber unsubscribes"
      (let [subscriptions {"file:///test.txt" #{"session-1"}}
            result (subs/unsubscribe! subscriptions "session-1" "file:///test.txt")]
        (is (= {} result))))

    (testing "handles unsubscribing from non-existent resource"
      (let [subscriptions {}
            result (subs/unsubscribe! subscriptions "session-1" "file:///test.txt")]
        (is (= {} result))))

    (testing "handles unsubscribing non-existent session"
      (let [subscriptions {"file:///test.txt" #{"session-1"}}
            result (subs/unsubscribe! subscriptions "session-2" "file:///test.txt")]
        (is (= {"file:///test.txt" #{"session-1"}} result))))))

(deftest unsubscribe-all-test
  (testing "unsubscribe-all!"
    (testing "removes all subscriptions for a session across all resources"
      (let [subscriptions {"file:///test.txt" #{"session-1" "session-2"}
                           "file:///other.txt" #{"session-1"}
                           "file:///third.txt" #{"session-2"}}
            result (subs/unsubscribe-all! subscriptions "session-1")]
        (is (= {"file:///test.txt" #{"session-2"}
                "file:///third.txt" #{"session-2"}}
               result))))

    (testing "removes resource entries when session was the last subscriber"
      (let [subscriptions {"file:///test.txt" #{"session-1"}
                           "file:///other.txt" #{"session-2"}}
            result (subs/unsubscribe-all! subscriptions "session-1")]
        (is (= {"file:///other.txt" #{"session-2"}} result))))

    (testing "handles removing all subscriptions when session has none"
      (let [subscriptions {"file:///test.txt" #{"session-2"}}
            result (subs/unsubscribe-all! subscriptions "session-1")]
        (is (= {"file:///test.txt" #{"session-2"}} result))))))

(deftest get-subscribers-test
  (testing "get-subscribers"
    (testing "returns set of session-ids subscribed to a resource"
      (let [subscriptions {"file:///test.txt" #{"session-1" "session-2"}}]
        (is (= #{"session-1" "session-2"}
               (subs/get-subscribers subscriptions "file:///test.txt")))))

    (testing "returns empty set for resource with no subscribers"
      (let [subscriptions {}]
        (is (= #{}
               (subs/get-subscribers subscriptions "file:///test.txt")))))))
