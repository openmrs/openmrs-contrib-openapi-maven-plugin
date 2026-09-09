#!/usr/bin/env bash
#
# serve.sh — OpenMRS OpenAPI dev server
#
# Serves API reference docs for one or more OpenMRS modules whose OpenAPI specs have already
# been generated (via ./generate.sh).
#
# The UI is a searchable tree of every resource, sub-resource and controller across every module
# passed in, and renders one of them at a time. That is a performance decision, not just a
# navigation one: a renderer ingests a whole document — resolving every $ref — before anything is
# interactive, so a module-sized spec blocks for seconds no matter how little is on screen. One
# resource at a time keeps that cost proportional to what was asked for, and the search index makes
# it findable anyway.
#
# The UI's "Try it out" feature proxies API calls through this server to avoid CORS issues. Pass
# --auth=<file> to enable it: the file names the OpenMRS instance and the credentials to reach it,
# and this server attaches those credentials to every proxied request, so you never type a password
# into the UI. Without --auth the docs render read-only and "Try it out" is disabled.
#
# Cross-module $refs (e.g. queue referencing Location from the REST module) are resolved
# automatically when all relevant modules are passed as arguments.
#
# Usage:
#   ./serve.sh [--auth=<file>] [--port=<port>] [--self-check] <module-path>...
#
# Arguments:
#   --auth=<file>    JSON file with "server", "username" and "password" (see dev3.json). When given,
#                    "Try it out" is enabled and its requests are authenticated with these
#                    credentials. "server" is the base URL of the OpenMRS instance and should NOT
#                    include /ws — e.g. https://dev3.openmrs.org/openmrs. Optional; omit for
#                    read-only docs. The script fails if the file is missing any of the three fields.
#   --port=<port>    Local port to listen on. Defaults to 9000.
#   --self-check     Before serving, slice every resource and report the index and slice totals,
#                    how many slices needed a schema from another module, and any $ref that does
#                    not resolve. Exits non-zero if anything dangles.
#   <module-path>    One or more paths to module root directories. Each must have a generated
#                    omod/target/classes/META-INF/openapi directory (or target/classes/... for
#                    flat layouts).
#
# Examples:
#   ./serve.sh --auth=dev3.json ../openmrs-module-webservices.rest
#   ./serve.sh --self-check ../openmrs-module-webservices.rest       # read-only, no "Try it out"
#   ./serve.sh --auth=dev3.json --port=9000 --self-check \
#       ../openmrs-module-webservices.rest ../openmrs-module-queue \
#       ../openmrs-module-appointments ../openmrs-module-emrapi
#
# Prerequisites:
#   - Run ./generate.sh <module-path> ... for the modules first.
#   - The dev server JAR is built automatically on first run if not present.
#   - The Swagger UI renderer is vendored into the repo, so this works with no network access.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/openapi-dev-server"
JAR="$SERVER_DIR/target/openapi-dev-server-1.0.0-SNAPSHOT.jar"

if [ $# -lt 1 ]; then
    echo "Usage: $0 [--auth=<file>] [--port=9000] [--self-check] <module-path>..." >&2
    echo "  e.g. $0 --auth=dev3.json ../openmrs-module-queue ../openmrs-module-emrapi" >&2
    exit 1
fi

# Rebuild when the JAR is missing or older than any Java source / the pom, so an edited dev server
# does not silently keep serving a stale build.
#
# src/main/resources is deliberately not part of that test: the UI's HTML and JS are read from
# src/main/resources/web at request time whenever that directory is present, so editing the UI
# needs a browser reload and nothing else. The copy packaged into the JAR therefore lags until the
# next rebuild, which only matters for a JAR taken away from this source tree — run `mvn package`
# before doing that.
NEEDS_BUILD=0
if [ ! -f "$JAR" ]; then
    NEEDS_BUILD=1
elif [ -n "$(find "$SERVER_DIR/src/main/java" "$SERVER_DIR/pom.xml" -newer "$JAR" -print -quit 2>/dev/null)" ]; then
    NEEDS_BUILD=1
fi

if [ "$NEEDS_BUILD" -eq 1 ]; then
    echo "=== Building dev server ==="
    mvn -q package -f "$SERVER_DIR/pom.xml"
fi

exec java -jar "$JAR" "$@"
