# OpenMRS OpenAPI Documentation Maven Plugin

| :zap: This plugin is currently in development and is not ready for general use. |
|--------------------------------------------------------------------------------|

This maven plugin aims to generate 100% complete and accurate OpenAPI documentation of REST resources and controllers for any OpenMRS module, by inspecting it via reflection at build time. It should be able to answer the following:
- For a given module:
  - What are its REST Resources?
  - What are its controllers?
- For a given OpenMRS REST Resource (ex: patient, encounter, visit):
  - what are its supported representations (ex: `default`, `ref`, `full`)?
  - what CRUD operations are supported?
  - what fields are included for each supported representation?
  - what fields are supported for custom representations: (ex: `?v=custom:(uuid,display)`)
  - what search handlers are supported, and what are the required fields for each search handler?
  - what are the required fields when creating the resource?
  - what are the required fields when updating the resource?
- For a given controller:
  - what URL path is the controller serves?
  - what are its inputs and outputs?

The generated documents are **OpenAPI 3.1**. Nothing is hand-written and nothing is inferred from
javadoc: the plugin executes the very same methods the REST module executes when it serves a request
(`getRepresentationDescription()`, `getCreatableProperties()`, `getUpdatableProperties()`, …), so the
spec describes what the module will actually do. No Spring context, no database, no running OpenMRS
instance is involved.

## Requirements

| | |
|---|---|
| Java | 21 (the plugin's own bytecode is Java 21; Java 8 is not supported) |
| Maven | 3.x |
| openmrs-core | 2.4.2 is the lowest verified version; older is untested, and 1.10.x is known-broken |
| REST module | 3.6.x or 4.0.x |

`JAVA_HOME` is never set by the scripts in this repo — export it yourself if the JDK on your `PATH`
is not Java 21:

```bash
export JAVA_HOME=/path/to/jdk-21
```

## Installing the plugin

The plugin is not published to any repository yet, so install it into your local `~/.m2`:

```bash
mvn clean install
```

That is all the setup there is — no `~/.m2/settings.xml` change is needed. `generate.sh` invokes the
goal by its full coordinates, reading them out of this repo's `pom.xml`:

```bash
mvn org.openmrs.maven.plugins:openmrs-openapi-maven-plugin:1.0.0-SNAPSHOT:generate \
    -f <module>/omod/pom.xml
```

The short form `mvn openmrs-openapi:generate` is equivalent but resolves only if you add this repo's
group to `~/.m2/settings.xml`, since Maven looks up short prefixes in its own default plugin groups
only:

```xml
<settings>
  <pluginGroups>
    <pluginGroup>org.openmrs.maven.plugins</pluginGroup>
  </pluginGroups>
</settings>
```

Without that you get `No plugin found for prefix 'openmrs-openapi'` — which is why the scripts do not
rely on it.

## Generating specs for a module — `./generate.sh`

`generate.sh` only runs the plugin. It builds **neither** the plugin nor the target module, so both
must already be built:

```bash
# 1. install this plugin into ~/.m2
mvn clean install

# 2. build the target module so its compiled classes exist
mvn clean install -DskipTests -f ../openmrs-module-queue/pom.xml

# 3. generate
./generate.sh ../openmrs-module-queue
```

Step 2 matters because the plugin reads the module's **compiled classes**, not its sources. If
`target/classes` is missing the script tells you so and prints the command to build it.

Several modules can be done in one invocation:

```bash
./generate.sh ../openmrs-module-webservices.rest ../openmrs-module-queue ../openmrs-module-emrapi
```

Each module is announced with a banner and followed by a summary of what it produced. A module that
fails does not stop the others; the failures are listed at the end and the script exits non-zero.

For each path given, the script picks the submodule that holds the REST resources — `omod/` when
present, otherwise the project root — and runs the plugin there.

### Where the output goes

```
<module>/omod/target/classes/META-INF/openapi/
├── openapi.json                  the whole module in one document
├── resources/<Resource>.json     one file per REST resource
└── controllers/<Controller>.json one file per @Controller
```

That directory is the module's compiled-resources tree, which is deliberate: the plugin's `generate`
goal binds to `process-classes`, so when a module runs it as part of its own build, `package` ships
the specs inside the omod JAR at `META-INF/openapi/` with no copy step. That is the intended
end state — no module declares the plugin yet, so today generation is driven from the outside by
`generate.sh`. The declaration would be an ordinary plugin block:

```xml
<plugin>
  <groupId>org.openmrs.maven.plugins</groupId>
  <artifactId>openmrs-openapi-maven-plugin</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>generate</goal></goals>
    </execution>
  </executions>
</plugin>
```

The output directory is cleared of its generated `*.json` at the start of every run, so a resource
that stops qualifying does not leave a stale file behind to be packaged.

## Browsing the docs — `./serve.sh`

`serve.sh` starts a local dev server (`openapi-dev-server/`) that renders the generated specs as
browsable API reference documentation and lets you fire real requests at a live OpenMRS instance
from the page.

```bash
./serve.sh --server=https://dev3.openmrs.org/openmrs \
    ../openmrs-module-webservices.rest ../openmrs-module-queue \
    ../openmrs-module-appointments ../openmrs-module-emrapi
```

Then open <http://localhost:9000>.

| Argument | |
|---|---|
| `--server=<url>` | **required** — base URL of the OpenMRS instance to proxy API calls to. Do *not* include `/ws`. |
| `--port=<port>` | local port to listen on; defaults to `9000` |
| `--self-check` | slice every resource before serving and report the totals, cross-module borrowing and any unresolved `$ref`; exits non-zero if anything dangles |
| `<module-path>...` | one or more module roots that have already been generated |

### What the UI does

A search box and a tree on the left, one resource rendered on the right:

```
webservices.rest
  RESOURCES
    Patient
      PatientIdentifier          ← sub-resources nest under the resource they hang off
    Concept
  CONTROLLERS
    SessionController
queue
  ...
```

- **Search covers every module at once** — names, routes, operation summaries and property names.
  Exact and prefix name matches rank above incidental field hits, so `patient` leads with the
  Patient resource rather than the dozen resources that merely have a `patient` field. A match on
  a sub-resource still draws its parent, greyed, so the hierarchy never breaks.
- **Each resource is a link.** `#/<module>/<Name>` — `#/webservices.rest/Patient` — so a resource
  can be bookmarked or pasted into a ticket.
- **Sub-resource nesting is derived, not configured.** `MainSubResourceController` serves every
  sub-resource as `/{resource}/{parentUuid}/{subResource}`, so the parent is recoverable from the
  routes. It is resolved by matching the parent segment against the resource that serves it, not by
  lower-casing — `FormResource` hangs off `Form`, and `UserResource1_8`'s delegate is
  `UserAndPassword1_8`.

### Why one resource at a time

This is the reason the UI is shaped the way it is. A renderer's dominant cost is **spec ingestion,
not rendering**: it converts the whole document to its internal structures and resolves every
`$ref` before anything is interactive, at roughly 4 ms per schema and regardless of how much is on
screen. Measured on `webservices.rest`:

| Document | KB | Schemas | DOM nodes | Longest task |
|---|---:|---:|---:|---:|
| whole module | 969 | 768 | 102 | 2876 ms |
| one resource (Patient) | 17 | 31 | — | ~500 ms |
| one resource (Account, emrapi) | 6 | 11 | — | ~330 ms |

The whole-module row displays two collapsed tag rows and 102 DOM nodes and still blocks the main
thread for nearly three seconds — so it is not rendering, and `JSON.parse` of the same bytes is
5 ms, so it is not parsing either. No renderer setting turns that work off. A smaller document is
the only lever, which is what makes per-resource serving a correctness-free performance win: the
specs are unchanged, only the unit of delivery is smaller.

### Cross-module `$ref` resolution

A dependent module's spec references the REST module's schemas without carrying them, by design:

| Module | Schemas | `$ref`s | Unresolved in its own spec |
|---|---:|---:|---:|
| `webservices.rest` | 768 | 671 | 0 |
| `queue` | 42 | 52 | 15 |
| `emrapi` | 126 | 130 | 10 |
| `appointments` | 23 | 23 | 0 |

All 25 resolve in `webservices.rest`. So **no single module's output is enough to render one of its
own resources**, and the server resolves schema names across every loaded module — the owning
module's definition wins, then other modules in name order, so the answer never depends on the
order the modules were passed. Pass every module you want resolved together.

Served with all four loaded, every document resolves completely: `queue` 42 → 97 schemas,
`emrapi` 126 → 153, and the merged view 895 — **0 dangling `$ref`s** in the slices and in every
whole-module spec.

`--self-check` is what proves it: on the four modules above it reports 149 resources/controllers,
149 slices averaging 7 KB, **9 slices pulling 170 schemas from another module, and 0 unresolved
`$ref`s**. That last number is the one that matters — a slice whose closure stops short renders
"Could not resolve reference" in place of half its schema, and that failure is invisible when
spot-checking.

### Other URLs

```
/index.json                     navigation index — every resource and controller, all modules
/slices/<module>/<Name>.json    one resource or controller, self-contained
/specs/<module>/openapi.json    a whole module, cross-module $refs resolved
/specs/all/openapi.json         every loaded module in one document
/proxy/*                        reverse proxy to --server
```

Notes:

- Run `./generate.sh <module-path>...` first. A module with no generated output is reported as a
  warning and skipped, not a fatal error.
- The JAR is rebuilt when `openapi-dev-server/src/main/java` or its `pom.xml` is newer. The UI's
  HTML and JS are read from `src/main/resources/web` at request time when that directory is
  present, so editing the UI needs a browser reload and no rebuild.
- The renderer bundle is fetched from a CDN on first run and cached under the system temp
  directory, so subsequent runs work offline. The version is pinned in `RendererAssets.java`.

## Choosing a renderer

The current renderer is Swagger UI, picked on measurement, not settled by fiat. The evaluation
lives in [`openapi-dev-server/renderer-compare/`](openapi-dev-server/renderer-compare/), a small
Python harness that serves the **same** spec to six renderers side by side — Scalar, Redoc,
Stoplight Elements, RapiDoc, OpenAPI Explorer and Swagger UI:

```bash
cd openapi-dev-server/renderer-compare
./run.sh                       # refresh from webservices.rest, serve on 9400
./run.sh 9400 <openapi-dir>    # ... from another module's generated output
```

It also serves stripped and re-tagged copies of the spec, to separate what a renderer costs from
what the document costs. Those copies are throwaways made by a script — the plugin does not emit
them, and only `openapi.json` is a valid description of the API.

The harness's README records what was observed per renderer, including the ingestion finding above
and the load ladder behind it. Read it before swapping the renderer in the dev server.

One patch travels with Swagger UI: `openapi-dev-server/src/main/resources/web/named-types.js`. Its
JSON Schema 2020-12 renderer dereferences `$ref`s before rendering and computes the type expression
from structure alone, so a union of named schemas renders as
`object | (object | object | object | object)`. The plugin names the branch by reading the `$ref`
pointer, giving
`object | (AlertGet_default | AlertGet_full | AlertGet_ref | AlertGet_custom)` — which is what
matters when the spec is also the input to TypeScript generation.

## Modules under active test

The plugin is developed against these four modules, chosen to span the range of layouts, core
versions and REST-resource styles in the ecosystem:

| Module | Layout | `openmrs-core` | Why it's in the set |
|---|---|---|---|
| [`openmrs-module-webservices.rest`](https://github.com/openmrs/openmrs-module-webservices.rest) | `omod/` | 2.8.7 | The REST module itself — by far the largest surface, and the source of the sub-resource / subclass-handler / multi-representation edge cases |
| [`openmrs-module-queue`](https://github.com/openmrs/openmrs-module-queue) | `omod/` | 2.7.4 | A modern, compact module; exercises sub-resources and cross-module `$ref`s into core resources |
| [`openmrs-module-appointments`](https://github.com/openmrs/openmrs-module-appointments) | `omod/` | 2.4.2 | The oldest core version verified, and an older Jackson without `jackson-datatype-jsr310` — the case the generator classloader's tool-chain fallback exists for |
| [`openmrs-module-emrapi`](https://github.com/openmrs/openmrs-module-emrapi) | `omod/` | 2.8.0 | Controller-heavy relative to its resource count, and the only verified user of the `/rest/**` version-wildcard mapping |

Byte-identical output across two consecutive runs is verified on `webservices.rest`, `queue` and
`emrapi`; the absence of typeless schemas is verified on `webservices.rest` and `queue`.

A note on scope — the plugin's resource list is a deliberate **superset** of
`RestServiceImpl.getResourceHandlers()`. That method keys resources by `supportedClass`, which
silently drops one of any two resources describing the same domain object (`concepttree`,
`orderable` and `queue-entry` were all missing this way). Discovery here keys by REST name, the same
key request dispatch uses.

## Integration tests

The integration tests fetch live data from a running OpenMRS instance and validate the responses
against the generated schemas — the end-to-end check that the spec matches reality.

```bash
mvn test          # unit tests only
mvn verify        # unit + integration tests
mvn verify -DskipITs=true
mvn verify -Dit.test=VisitSchemaIT
```

They need a live instance. Copy `example.env` to `.env`, or set the variables in the environment:

```
OPENMRS_BASE_URL=https://dev3.openmrs.org/openmrs
OPENMRS_USERNAME=...
OPENMRS_PASSWORD=...
OPENAPI_SCHEMAS_DIR=/path/to/module/omod/target/classes/META-INF/openapi/resources
```

`OPENAPI_SCHEMAS_DIR` points the tests at the generated schemas and can also be given on the command
line as `-Dopenapi.schemas.dir=<path>`. Note that the placeholder in `example.env` still names the
pre-`META-INF` output layout; use the path above.

With no configuration present the integration tests skip rather than fail.

## Repository layout

| Path | |
|---|---|
| `src/main/java/org/openmrs/plugin/openapi/` | the Maven plugin |
| `openapi-dev-server/` | standalone dev server behind `serve.sh` (separate build, not part of the plugin) |
| `openapi-dev-server/src/main/resources/web/` | the docs UI — plain HTML/CSS/JS, no build step, served from disk during development |
| `openapi-dev-server/renderer-compare/` | side-by-side renderer evaluation harness; plain Python, no build step |
| `src/test/` | unit tests and the `*SchemaIT` integration tests |
| `generate.sh` | run the plugin against one or more already-built modules |
| `serve.sh` | serve the generated docs |
| `CLAUDE.md` | in-depth design notes — why the classloader is isolated, how paths and schemas mirror REST request dispatch, and the reasoning behind individual decisions |

`CLAUDE.md` is the place to look before changing generation behaviour; it records what was measured
and what alternatives were rejected.
