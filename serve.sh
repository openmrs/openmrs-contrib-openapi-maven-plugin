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
# API calls made from the docs UI are proxied through this server to avoid CORS issues. The served
# specs declare an HTTP Basic security scheme, so entering your OpenMRS username and password in
# the UI's authentication controls is enough — credentials are forwarded with every proxied request.
#
# Cross-module $refs (e.g. queue referencing Location from the REST module) are resolved
# automatically when all relevant modules are passed as arguments.
#
# Usage:
#   ./serve.sh --server=<url> [--port=<port>] [--self-check] <module-path>...
#
# Arguments:
#   --server=<url>   Base URL of the OpenMRS instance to proxy API calls to.
#                    Should NOT include /ws — e.g. https://dev3.openmrs.org/openmrs
#   --port=<port>    Local port to listen on. Defaults to 9000.
#   --self-check     Before serving, slice every resource and report the index and slice totals,
#                    how many slices needed a schema from another module, and any $ref that does
#                    not resolve. Exits non-zero if anything dangles.
#   <module-path>    One or more paths to module root directories. Each must have a generated
#                    omod/target/classes/META-INF/openapi directory (or target/classes/... for
#                    flat layouts).
#
# Examples:
#   ./serve.sh --server=https://dev3.openmrs.org/openmrs ../openmrs-module-webservices.rest
#   ./serve.sh --server=https://dev3.openmrs.org/openmrs --port=9000 --self-check \
#       ../openmrs-module-webservices.rest ../openmrs-module-queue \
#       ../openmrs-module-appointments ../openmrs-module-emrapi
#
# Prerequisites:
#   - Run ./generate.sh <module-path> ... for the modules first.
#   - The dev server JAR is built automatically on first run if not present.
#   - The first run downloads the renderer bundle and caches it; after that it works offline.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/openapi-dev-server"
JAR="$SERVER_DIR/target/openapi-dev-server-1.0.0-SNAPSHOT.jar"

if [ $# -lt 1 ]; then
    echo "Usage: $0 --server=<url> [--port=9000] [--self-check] <module-path>..." >&2
    echo "  e.g. $0 --server=https://dev3.openmrs.org/openmrs ../openmrs-module-queue ../openmrs-module-emrapi" >&2
    exit 1
fi

if ! printf '%s\n' "$@" | grep -q '^--server='; then
    echo "Error: --server=<url> is required" >&2
    echo "Usage: $0 --server=<url> [--port=9000] [--self-check] <module-path>..." >&2
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
