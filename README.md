# OpenMRS REST Representation Analyzer

![alt text](src/static/openmrs.png)

!! This plugin is currently in development and is not ready for general use.

This maven plugin aims to generate 100% complete and accurate documentation of OpenMRS REST endpoints and controllers, by inspecting the module with this plugin using reflection. It can be added to the OpenMRS REST module, or any other module that defines REST resources and controllers. It should be able to answer the following:
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

## What This Plugin Does

This plugin automatically discovers and analyzes **all OpenMRS REST resource handlers** in your project, extracting:

- **Resource metadata** (names, supported classes, handler classes)
- **Representation structures** (DEFAULT, FULL, REF property counts)
- **Version compatibility** (supported OpenMRS versions)
- **Error handling** (graceful handling of malformed resources)

Perfect for **automatic documentation generation**, **API discovery**, and **OpenMRS module analysis**.

## How Schema Generation Mirrors the REST Module

The generated schemas follow exactly how the OpenMRS REST module handles requests at runtime. The authoritative reference is the [High Level Overview of REST Request Handling](https://openmrs.atlassian.net/wiki/spaces/projects/pages/263323664/Enhancing+OpenAPI+Documentation+Generation) on the OpenMRS wiki. In summary:

1. For each resource, `getRepresentationDescription()` is called for the standard representations (`default`, `full`, `ref`). Each non-null result becomes a `ResourceGet_<rep>` schema.
2. If `getRepresentationDescription()` returns null for a representation, the plugin looks for a method annotated with `@RepHandler` to handle it — exactly as `BaseDelegatingResource.findAnnotatedMethodForRepresentation()` does at runtime. Each such method produces a `ResourceGet_<repName>` schema.
3. `getCreatableProperties()` produces a `ResourceCreate` schema; `getUpdatableProperties()` produces a `ResourceUpdate` schema.
4. All GET schemas are grouped under an intermediary `ResourceGet` schema (`anyOf`), and the top-level `Resource` schema is `anyOf: [ResourceGet, ResourceCreate, ResourceUpdate]`.

## Architecture

The plugin runs the OpenAPI spec generator in-process using an isolated `URLClassLoader`. The classloader combines the plugin's own classpath (OpenMRS platform, Swagger, Spring, etc.) with the target module's compiled classes and test artifacts, using the JDK system classloader as parent to avoid exposing Maven internals. The generator is invoked via reflection to prevent class identity conflicts between the isolated loader and the plugin's ClassRealm.
## Sample Output

```json
{
  "metadata": {
    "timestamp": "2025-07-13T11:24:00.360Z",
    "pluginVersion": "1.0.0-SNAPSHOT",
    "resourceCount": 94
  },
  "resources": [
    {
      "resourceName": "v1/person",
      "supportedClass": "Person",
      "handlerClass": "PersonResource1_8",
      "handlerPackage": "org.openmrs.module.webservices.rest.web.v1_0.resource.openmrs1_8",
      "supportedVersions": ["1.8.* - 9.*"],
      "representations": {
        "DEFAULT": {"propertyCount": 15, "linkCount": 3},
        "FULL": {"propertyCount": 25, "linkCount": 5},
        "REF": {"propertyCount": 2, "linkCount": 1}
      }
    }
  ]
}
```

## Quick Start

### Prerequisites
- **Java 8+** (same as OpenMRS requirement)
- **Maven 3.6+**
- **OpenMRS project** (with REST module dependencies)

### Installation

1. **Clone the repository:**
```bash
git clone https://github.com/your-username/openmrs-rest-representation-analyzer.git
cd openmrs-rest-representation-analyzer
```

2. **Install the plugin:**
```bash
mvn clean install
```

## Usage Commands

### Basic Analysis
```bash
# Run analysis on OpenMRS project
mvn openmrs-rest-analyzer:analyze-representations

# With custom timeout (default: 300 seconds)
mvn openmrs-rest-analyzer:analyze-representations -DtimeoutSeconds=600
```

### Integrate with Build Lifecycle
```bash
# Run during normal build (plugin auto-executes during process-classes phase)
mvn clean compile

# Full build with analysis
mvn clean install
```

### Development Commands
```bash
# Build and test the plugin itself
mvn clean install

# Run plugin tests
mvn test

# Debug mode with verbose output
mvn org.openmrs.plugin:openmrs-rest-analyzer:1.0.0-SNAPSHOT:analyze-representations -X

# Check plugin dependencies
mvn dependency:tree
```
### OpenMRS Dependencies

The plugin declares several OpenMRS dependencies, each serving a specific purpose:

| Dependency | Classifier | Why it's needed |
|---|---|---|
| `org.openmrs.web:openmrs-web` | — | Provides `openmrs-api` transitively; also puts `openmrs-servlet.xml` on the classpath, loaded by the Spring `XmlWebApplicationContext` during spec generation |
| `org.openmrs.web:openmrs-web` | `tests` | Provides test Spring context XML files (e.g. `TestingApplicationContext.xml`) loaded during context startup |
| `org.openmrs.test:openmrs-test` | — (pom) | Test infrastructure: brings in dbunit, H2, and related test utilities |
| `org.openmrs.module:webservices.rest-omod-common` | — | Directly imported in plugin code (`RestService`, `DelegatingResourceHandler`, REST annotations, etc.) |
| `org.openmrs.module:webservices.rest-omod-common` | `tests` | Provides test-scope Spring context XML for the REST module |

The target module's own classes (including version-specific REST resources) are loaded at runtime from the module's build output directory, not bundled into the plugin.

## Contributing

1. **Fork the repository**
2. **Create feature branch**: `git checkout -b feature/amazing-feature`
3. **Commit changes**: `git commit -m 'Add amazing feature'`
4. **Push to branch**: `git push origin feature/amazing-feature`
5. **Open Pull Request**

### Development Guidelines
- Follow OpenMRS coding standards
- Add tests for new functionality
- Update documentation for API changes
- Ensure backward compatibility

## 🏥 OpenMRS Community

- **OpenMRS Wiki**: https://wiki.openmrs.org/
- **Developer Documentation**: https://wiki.openmrs.org/display/docs/Developer+Documentation

## Related Projects

- [OpenMRS REST Web Services](https://github.com/openmrs/openmrs-module-webservices.rest)
- [OpenMRS Core](https://github.com/openmrs/openmrs-core)
- [OpenMRS Platform](https://github.com/openmrs/openmrs-distro-platform)

---

**Built with ❤️ for the OpenMRS Community**
