#!/usr/bin/env bash
#
# serve.sh — OpenMRS OpenAPI dev server
#
# Serves Swagger UI for one or more OpenMRS modules whose OpenAPI specs have
# already been generated (via `mvn openmrs-openapi:generate`
# or run-it.sh). The UI has a sidebar listing each module plus an "All" view
# that merges all modules into one combined spec.
#
# API calls made from the Swagger UI are proxied through this server to avoid
# CORS issues. You authenticate via Basic Auth in the Swagger UI "Authorize"
# dialog — credentials are forwarded with every proxied request.
#
# Cross-module $refs (e.g. Queue referencing Location from the REST module) are
# resolved automatically when all relevant modules are passed as arguments.
#
# Usage:
#   ./serve.sh --server=<url> [--port=<port>] <module-path>...
#
# Arguments:
#   --server=<url>   Base URL of the OpenMRS instance to proxy API calls to.
#                    Should NOT include /ws — e.g. https://dev3.openmrs.org/openmrs
#   --port=<port>    Local port to listen on. Defaults to 9000.
#   <module-path>    One or more paths to module root directories. Each must
#                    have a generated omod/target/classes/META-INF/openapi/ directory
#                    (or target/classes/META-INF/openapi/ for flat layouts).
#
# Examples:
#   ./serve.sh --server=https://dev3.openmrs.org/openmrs ../openmrs-module-webservices.rest
#   ./serve.sh --server=https://dev3.openmrs.org/openmrs --port=8080 \
#       ../openmrs-module-webservices.rest ../openmrs-module-queue
#
# Prerequisites:
#   - Run ./generate.sh <module-path> for each module first (or use run-it.sh, which
#     does this automatically for webservices.rest).
#   - The dev server JAR is built automatically on first run if not present.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/openapi-dev-server"
JAR="$SERVER_DIR/target/openapi-dev-server-1.0.0-SNAPSHOT.jar"

if [ $# -lt 1 ]; then
    echo "Usage: $0 --server=<url> [--port=9000] <module-path>..." >&2
    echo "  e.g. $0 --server=https://dev3.openmrs.org/openmrs ../openmrs-module-queue ../openmrs-module-emrapi" >&2
    exit 1
fi

if ! printf '%s\n' "$@" | grep -q '^--server='; then
    echo "Error: --server=<url> is required" >&2
    echo "Usage: $0 --server=<url> [--port=9000] <module-path>..." >&2
    exit 1
fi

# Rebuild when the JAR is missing or older than any source file / the pom, so an
# edited dev server does not silently keep serving a stale build.
NEEDS_BUILD=0
if [ ! -f "$JAR" ]; then
    NEEDS_BUILD=1
elif [ -n "$(find "$SERVER_DIR/src" "$SERVER_DIR/pom.xml" -newer "$JAR" -print -quit 2>/dev/null)" ]; then
    NEEDS_BUILD=1
fi

if [ "$NEEDS_BUILD" -eq 1 ]; then
    echo "=== Building dev server ==="
    mvn -q package -f "$SERVER_DIR/pom.xml"
fi

exec java -jar "$JAR" "$@"
