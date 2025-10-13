#!/usr/bin/env bash
set -euo pipefail

# Smoke tests for built JAR artifacts
# Validates that JARs are well-formed and contain expected content

echo "🔍 Running smoke tests on built artifacts..."
echo ""

FAILED=0

# Function to test a JAR file
test_jar() {
  local project=$1
  local jar_path=$2
  local expected_namespace=$3

  echo "Testing $project JAR..."

  # Check if JAR exists
  if [ ! -f "$jar_path" ]; then
    echo "❌ JAR not found: $jar_path"
    FAILED=1
    return 1
  fi

  # Check JAR is not empty
  local size=$(stat -c%s "$jar_path" 2>/dev/null || stat -f%z "$jar_path")
  if [ "$size" -lt 1000 ]; then
    echo "❌ JAR is suspiciously small: $size bytes"
    FAILED=1
    return 1
  fi
  echo "  ✅ JAR exists and has reasonable size: $(numfmt --to=iec $size 2>/dev/null || echo "$size bytes")"

  # Check JAR can be read
  if ! jar tf "$jar_path" > /dev/null 2>&1; then
    echo "❌ JAR is not a valid JAR file"
    FAILED=1
    return 1
  fi
  echo "  ✅ JAR is valid and readable"

  # Check for pom.xml
  if ! jar tf "$jar_path" | grep -q "META-INF/maven/.*/pom.xml"; then
    echo "❌ pom.xml not found in JAR"
    FAILED=1
    return 1
  fi
  echo "  ✅ Maven metadata (pom.xml) present"

  # Check for expected namespace files (convert - to _ for file paths)
  local namespace_path="${expected_namespace//./\/}"
  namespace_path="${namespace_path//-/_}.clj"
  if ! jar tf "$jar_path" | grep -q "$namespace_path"; then
    echo "❌ Expected namespace file not found: $namespace_path"
    FAILED=1
    return 1
  fi
  echo "  ✅ Expected namespace files present"

  # Check for duplicate entries (a common JAR corruption issue)
  local duplicates=$(jar tf "$jar_path" | sort | uniq -d)
  if [ -n "$duplicates" ]; then
    echo "❌ Duplicate entries found in JAR:"
    echo "$duplicates"
    FAILED=1
    return 1
  fi
  echo "  ✅ No duplicate entries"

  echo "  ✅ $project smoke test passed"
  echo ""
  return 0
}

# Calculate version
VERSION=$(clojure -T:build version | grep "Version:" | awk '{print $2}')
echo "Testing artifacts for version: $VERSION"
echo ""

# Test each project JAR
test_jar "server" \
  "projects/server/target/mcp-clj-server-${VERSION}.jar" \
  "mcp-clj/mcp-server/core"

test_jar "client" \
  "projects/client/target/mcp-clj-client-${VERSION}.jar" \
  "mcp-clj/mcp-client/core"

test_jar "in-memory-transport" \
  "projects/in-memory-transport/target/mcp-clj-in-memory-transport-${VERSION}.jar" \
  "mcp-clj/in-memory-transport/client"

# Summary
echo ""
if [ $FAILED -eq 0 ]; then
  echo "✅ All smoke tests passed!"
  exit 0
else
  echo "❌ Some smoke tests failed"
  exit 1
fi
