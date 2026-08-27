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

`serve.sh` starts a small local dev server (`openapi-dev-server/`, ~500 lines on the JDK's built-in
HTTP server) that renders the generated specs as browsable API reference documentation and lets you
fire real requests at a live OpenMRS instance from the page.

```bash
./serve.sh --server=https://dev3.openmrs.org/openmrs \
    ../openmrs-module-webservices.rest ../openmrs-module-queue
```

Then open <http://localhost:9000>.

| Argument | |
|---|---|
| `--server=<url>` | **required** — base URL of the OpenMRS instance to proxy API calls to. Do *not* include `/ws`. |
| `--port=<port>` | local port to listen on; defaults to `9000` |
| `<module-path>...` | one or more module roots that have already been generated |

What it gives you:

- **A sidebar of modules**, plus an `All` entry that merges every module passed in into a single
  combined document. Each module is labelled by its directory name.
- **Cross-module `$ref` resolution.** `queue` references `Location` from the REST module; when both
  modules are passed as arguments the missing schemas are pulled in from the sibling specs, applied
  transitively until stable. Pass the modules you want resolved together.
- **A reverse proxy at `/proxy/*`.** The served specs have their `servers` block rewritten to
  `/proxy`, so "try it" requests go to the dev server and are forwarded upstream — which sidesteps
  CORS entirely. The specs also declare an HTTP Basic security scheme, so entering your OpenMRS
  username and password in the UI's authentication controls is enough; the credentials are forwarded
  with every proxied request.

Notes:

- Run `./generate.sh <module-path>...` for the modules first. A module with no generated output is
  reported as a warning and skipped, not a fatal error.
- The dev server JAR is built on first run, and rebuilt whenever `openapi-dev-server/src` or its
  `pom.xml` is newer than the JAR — so an edited dev server never silently serves a stale build.
- The UI shell loads its renderer from a CDN, so the browser needs network access. Which renderer to
  standardise on is still open — see *Choosing a renderer* below.

Useful URLs, if you want to fetch a spec rather than read it:

```
/specs/all/openapi.json           merged spec from all loaded modules
/specs/<module>/openapi.json      one module
/specs/<module>/resources/*.json
/specs/<module>/controllers/*.json
```

## Choosing a renderer

Which renderer to put in front of the specs is not settled. `openapi-dev-server` wires in one current
pick; the evaluation lives beside it in
[`openapi-dev-server/renderer-compare/`](openapi-dev-server/renderer-compare/), a small Python
harness that serves the **same** spec to six renderers side by side — Scalar, Redoc, Stoplight
Elements, RapiDoc, OpenAPI Explorer, and Swagger UI as the baseline:

```bash
cd openapi-dev-server/renderer-compare
./run.sh                       # refresh from webservices.rest, serve on 9400
./run.sh 9400 <openapi-dir>    # ... from another module's generated output
```

It also serves a re-tagged copy of the spec alongside the real one, to see what tag hierarchy would
buy — one tag per resource, grouped under `x-tagGroups`. That copy is a throwaway made by a script;
the plugin does not emit it.

The harness's README records what has been observed per renderer, including the finding behind the
current pick. Read it before swapping the renderer in the dev server.

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
| `openapi-dev-server/renderer-compare/` | side-by-side renderer evaluation harness; plain Python, no build step |
| `src/test/` | unit tests and the `*SchemaIT` integration tests |
| `generate.sh` | run the plugin against one or more already-built modules |
| `serve.sh` | serve the generated docs |
| `CLAUDE.md` | in-depth design notes — why the classloader is isolated, how paths and schemas mirror REST request dispatch, and the reasoning behind individual decisions |

`CLAUDE.md` is the place to look before changing generation behaviour; it records what was measured
and what alternatives were rejected.
