# OpenMRS OpenAPI Documentation Maven Plugin

| :zap: This plugin is currently in development and is not ready for general use. |
|--------------------------------------------------------------------------------|

This maven plugin aims to generate 100% complete and accurate OpenAPI documentation of REST resources and controllers
for any OpenMRS module, by inspecting it via reflection at build time. It should be able to answer the following:
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
spec describes what the module will actually do.

## Requirements

| | |
|---|---|
| Java | 21 (the plugin's own bytecode is Java 21; Java 8 is not supported) |
| Maven | 3.x |
| openmrs-core | 2.4.2 is the lowest verified version; older is untested, and 1.10.x is known-broken |
| REST module | 3.6.x or 4.0.x |


## Development Quick Start

If you have multiple versions of Java, make sure to use the right one.

```bash
export JAVA_HOME=/path/to/jdk-21
```

Build:

```bash
mvn clean install
```

While this project is a maven plugin, meant to be included in any OpenMRS module's `pom.xml` as part of 
its build steps, the OpenAPI doc generation can be invoked directly. You'll first need to build the 
target module.

```bash
# do this in a separate terminal, especially if the target module requires a different Java version
cd <path-to-module-root>
mvn clean install -DskipTests
```

Then invoke the plugin with the `generate.sh` script.

```bash
# Adjust the list of target modules as needed
./generate.sh \
    ../openmrs-module-webservices.rest ../openmrs-module-queue \
    ../openmrs-module-appointments ../openmrs-module-emrapi
```

For each path given, the script picks the submodule that holds the REST resources — `omod/` when
present, otherwise the project root — and runs the plugin there.

### Where the output goes

```
<module>/omod/target/classes/META-INF/openapi/
├── openapi.json                  the whole module in one document
├── resources/<Resource>.json     one file per REST resource
└── controllers/<Controller>.json one file per @Controller
```

The generated OpenAPI JSON docs are in the module's `target/classes/META-INF` directory, allowing them 
to be packaged as part of the omod JAR..

## Browsing the docs

This repo also includes a development server at `openapi-dev-server` to browse the generated docs. The server provides a 
customized [Swagger UI](https://swagger.io/open-source/swagger-ui/) frontend, with improved performance for displaying
large number of Resources and Controllers. The server also supports Swagger UI's "Try it out" feature, which allows the
UI to send HTTP requests to a live OpenMRS server, by proxying its requests (to workaround CORS restrictions).

```bash
# Adjust the list of target modules as needed
./serve.sh --server=https://dev3.openmrs.org/openmrs \
    ../openmrs-module-webservices.rest ../openmrs-module-queue \
    ../openmrs-module-appointments ../openmrs-module-emrapi
```

Then open <http://localhost:9000>.

| Argument | |
|---|---|
| `--server=<url>` | **required** — base URL of the OpenMRS instance to proxy API calls to.. |
| `--port=<port>` | local port to listen on; defaults to `9000` |
| `--self-check` | slice every resource before serving and report the totals, cross-module borrowing and any unresolved `$ref`; exits non-zero if anything dangles |
| `<module-path>...` | one or more module roots that have already been generated |


## Target modules under test

The plugin is actively developed against these four modules, chosen to span the range of layouts, core
versions and REST-resource styles in the ecosystem:

| Module | Branch | 
|---|---|
| [`openmrs-module-webservices.rest`](https://github.com/openmrs/openmrs-module-webservices.rest) | 3.x |
| [`openmrs-module-queue`](https://github.com/openmrs/openmrs-module-queue) | `omod/` | master |
| [`openmrs-module-appointments`](https://github.com/openmrs/openmrs-module-appointments) | master |
| [`openmrs-module-emrapi`](https://github.com/openmrs/openmrs-module-emrapi) | master |

## Integration tests (WIP)

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

