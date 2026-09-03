#!/usr/bin/env bash
set -euo pipefail

while [ $# -gt 0 ]; do
    case "$1" in
        --) shift; break ;;
        -*) echo "Unknown option: $1" >&2; exit 1 ;;
        *) break ;;
    esac
done

if [ $# -lt 1 ]; then
    echo "Usage: $0 <module-path> [<module-path> ...]" >&2
    echo "  e.g. $0 ../openmrs-module-webservices.rest ../openmrs-module-queue" >&2
    echo "" >&2
    echo "Writes the OpenAPI specs to <module>/omod/target/classes/META-INF/openapi and an" >&2
    echo "npm package of TypeScript clients for the module's controller endpoints to" >&2
    echo "<module>/omod/target/generated-typescript." >&2
    echo "" >&2
    echo "Each module must already be built, and this plugin must be installed" >&2
    echo "(mvn clean install). Set JAVA_HOME to the JDK you want Maven to use;" >&2
    echo "the plugin requires Java 21." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_POM="$SCRIPT_DIR/pom.xml"

# Read the plugin's own coordinates from its pom so the goal can be invoked fully
# qualified. The short "openmrs-openapi:generate" prefix would additionally require
# org.openmrs.maven.plugins in the reader's ~/.m2/settings.xml <pluginGroups>.
# Only the project's own coordinates are wanted, so stop at the first container
# element — anything after it belongs to a dependency or plugin declaration.
pom_coordinate() {
    awk -v tag="$1" '
        /<(properties|dependencies|dependencyManagement|build|profiles|modules|parent)>/ { exit }
        match($0, "<" tag ">[^<]*</" tag ">") {
            field = substr($0, RSTART, RLENGTH)
            gsub("</?" tag ">", "", field)
            gsub(/^[ \t]+|[ \t]+$/, "", field)
            print field
            exit
        }
    ' "$PLUGIN_POM"
}

if [ ! -f "$PLUGIN_POM" ]; then
    echo "Error: cannot find this plugin's pom.xml at $PLUGIN_POM" >&2
    exit 1
fi

PLUGIN_GROUP_ID="$(pom_coordinate groupId)"
PLUGIN_ARTIFACT_ID="$(pom_coordinate artifactId)"
PLUGIN_VERSION="$(pom_coordinate version)"

if [ -z "$PLUGIN_GROUP_ID" ] || [ -z "$PLUGIN_ARTIFACT_ID" ] || [ -z "$PLUGIN_VERSION" ]; then
    echo "Error: could not read groupId/artifactId/version from $PLUGIN_POM" >&2
    exit 1
fi

GOAL="$PLUGIN_GROUP_ID:$PLUGIN_ARTIFACT_ID:$PLUGIN_VERSION:generate"

FAILED=()

generate_for_module() {
    local module_arg="$1"

    if [ ! -d "$module_arg" ]; then
        echo "Error: $module_arg is not a directory" >&2
        return 1
    fi

    local module_path
    module_path="$(cd "$module_arg" && pwd)"

    if [ ! -f "$module_path/pom.xml" ]; then
        echo "Error: no pom.xml found at $module_path" >&2
        return 1
    fi

    # The plugin must run against the submodule that contains REST resources.
    # For most OpenMRS modules that is omod/; fall back to the root for flat layouts.
    local target_pom target_classes output_dir
    if [ -f "$module_path/omod/pom.xml" ]; then
        target_pom="$module_path/omod/pom.xml"
        target_classes="$module_path/omod/target/classes"
    else
        target_pom="$module_path/pom.xml"
        target_classes="$module_path/target/classes"
    fi
    output_dir="$target_classes/META-INF/openapi"

    if [ ! -d "$target_classes" ]; then
        echo "Error: $target_classes not found — build the module first:" >&2
        echo "  mvn clean install -DskipTests -f $module_path/pom.xml" >&2
        return 1
    fi

    mvn "$GOAL" -f "$target_pom" || return 1

    echo ""
    echo "=== Output: $(basename "$module_path") ==="
    if [ -f "$output_dir/openapi.json" ]; then
        echo "  openapi.json     $(wc -c < "$output_dir/openapi.json") bytes"
    fi
    if [ -d "$output_dir/resources" ]; then
        echo "  resources/       $(find "$output_dir/resources" -name '*.json' | wc -l) schema files"
    fi
    if [ -d "$output_dir/controllers" ]; then
        echo "  controllers/     $(find "$output_dir/controllers" -name '*.json' | wc -l) controller files"
    fi
    if [ -d "$output_dir/searchHandlers" ]; then
        echo "  searchHandlers/  $(find "$output_dir/searchHandlers" -name '*.json' | wc -l) search handler files"
    fi
    local package_dir="$(dirname "$target_classes")/generated-typescript"
    if [ -d "$package_dir" ]; then
        echo "  typescript/      $(find "$package_dir/src" -name '*.ts' | wc -l) source files"
        echo "npm package: $package_dir"
    fi
    echo "Full output: $output_dir"
}

for module in "$@"; do
    echo ""
    echo "########## $module ##########"
    if ! generate_for_module "$module"; then
        FAILED+=("$module")
    fi
done

if [ ${#FAILED[@]} -gt 0 ]; then
    echo "" >&2
    echo "=== Failed (${#FAILED[@]} of $#) ===" >&2
    for module in "${FAILED[@]}"; do
        echo "  $module" >&2
    done
    exit 1
fi
