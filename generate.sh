#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <module-path>" >&2
    echo "  e.g. $0 ../openmrs-module-webservices.rest" >&2
    exit 1
fi

MODULE_PATH="$(cd "$1" && pwd)"

if [ ! -f "$MODULE_PATH/pom.xml" ]; then
    echo "Error: no pom.xml found at $MODULE_PATH" >&2
    exit 1
fi

# The plugin must run against the submodule that contains REST resources.
# For most OpenMRS modules that is omod/; fall back to the root for flat layouts.
if [ -f "$MODULE_PATH/omod/pom.xml" ]; then
    TARGET_POM="$MODULE_PATH/omod/pom.xml"
    TARGET_CLASSES="$MODULE_PATH/omod/target/classes"
    OUTPUT_DIR="$MODULE_PATH/omod/target/classes/META-INF/openapi"
else
    TARGET_POM="$MODULE_PATH/pom.xml"
    TARGET_CLASSES="$MODULE_PATH/target/classes"
    OUTPUT_DIR="$MODULE_PATH/target/classes/META-INF/openapi"
fi

if [ ! -d "$TARGET_CLASSES" ]; then
    echo "Error: $TARGET_CLASSES not found — build the module first:" >&2
    echo "  JAVA_HOME=$JAVA_HOME mvn clean install -DskipTests -f $MODULE_PATH/pom.xml" >&2
    exit 1
fi

JAVA_HOME="$JAVA_HOME" mvn openmrs-openapi:generate -f "$TARGET_POM"

echo ""
echo "=== Output ==="
if [ -f "$OUTPUT_DIR/openapi.json" ]; then
    echo "  openapi.json     $(wc -c < "$OUTPUT_DIR/openapi.json") bytes"
fi
if [ -d "$OUTPUT_DIR/resources" ]; then
    echo "  resources/       $(find "$OUTPUT_DIR/resources" -name '*.json' | wc -l) schema files"
fi
if [ -d "$OUTPUT_DIR/controllers" ]; then
    echo "  controllers/     $(find "$OUTPUT_DIR/controllers" -name '*.json' | wc -l) controller files"
fi
echo "Full output: $OUTPUT_DIR"
