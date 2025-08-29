# OpenMRS REST OpenAPI Generator

![OpenMRS Logo](src/static/openmrs.png)

A Maven plugin that automatically generates **OpenAPI 3.0 specifications** from OpenMRS REST resources during the build process. This plugin uses runtime introspection to analyze REST resource handlers and produces comprehensive API documentation that's ready for tools like Swagger UI, Postman, or API clients.

## 🚀 What This Plugin Does

The plugin **automatically discovers and analyzes** OpenMRS REST resources in your module, generating:

- **📋 Complete OpenAPI 3.0 specification** with paths, parameters, and schemas
- **🔍 Resource introspection** using OpenMRS representation system (DEFAULT, FULL, REF)
- **📝 Interactive API documentation** compatible with Swagger UI
- **⚡ Build-time generation** integrated with Maven lifecycle
- **🛡️ Type-safe schemas** with proper validation and examples

Perfect for **API documentation**, **client SDK generation**, **testing**, and **module integration**.

## 🏗️ Architecture

### Forked Process Design
```
Maven Plugin (Lightweight)
    ↓ Spawns
Forked JVM (Full OpenMRS Context)
    ↓ Loads OpenMRS + H2 Database
    ↓ Analyzes REST Resources
    ↓ Generates
OpenAPI 3.0 JSON Specification
```

**Why Forked JVM?** The plugin runs OpenMRS in a separate process to avoid ClassLoader conflicts between Maven and OpenMRS runtime dependencies.

## 📊 Sample Output

The plugin generates a complete OpenAPI 3.0 specification like this:

```json
{
  "openapi": "3.0.1",
  "info": {
    "title": "OpenMRS REST API",
    "description": "Generated OpenAPI 3.0 specification for OpenMRS REST resources using introspection service",
    "version": "2.4.x"
  },
  "paths": {
    "/ws/rest/v1/queue/{uuid}": {
      "get": {
        "summary": "Get a Queue by UUID",
        "description": "Retrieve a Queue resource in the requested representation",
        "parameters": [
          {
            "name": "uuid",
            "in": "path",
            "description": "The UUID of the Queue",
            "required": true,
            "schema": { "type": "string" }
          },
          {
            "name": "v",
            "in": "query", 
            "description": "The representation to return. Allowed values: 'default', 'full', 'ref', or custom",
            "schema": {
              "type": "string",
              "enum": ["default", "full", "ref"]
            }
          }
        ],
        "responses": {
          "200": {
            "description": "Successful response",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/QueueDefault"
                }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "QueueDefault": {
        "type": "object",
        "properties": {
          "uuid": { "type": "string" },
          "display": { "type": "string" },
          "name": { "type": "string" },
          "description": { "type": "string" }
        }
      }
    }
  }
}
```

## ⚡ Quick Start

### Prerequisites
- **Java 8+** (OpenMRS requirement)
- **Maven 3.6+**
- **OpenMRS module** with REST resources

### 1. Install the Plugin

First, build and install the plugin to your local Maven repository:

```bash
git clone https://github.com/capernix/openmrs-maven-plugin-openapi.git
cd openmrs-maven-plugin-openapi/openmrs-rest-representation-analyzer
mvn clean install
```

### 2. Add to Your OpenMRS Module

Add the plugin to your module's `omod/pom.xml`:

```xml
<build>
  <plugins>
    <!-- OpenMRS REST OpenAPI Generator Plugin -->
    <plugin>
      <groupId>org.openmrs.plugin</groupId>
      <artifactId>openmrs-rest-analyzer</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <executions>
        <execution>
          <id>generate-openapi-spec</id>
          <phase>process-classes</phase>
          <goals>
            <goal>analyze-representations</goal>
          </goals>
          <configuration>
            <!-- Auto-detect REST resource packages -->
            <autoDetectResources>true</autoDetectResources>
            <!-- Or specify manually -->
            <scanPackages>
              <package>org.openmrs.module.yourmodule.web.resources</package>
            </scanPackages>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

### 3. Add Required Test Dependencies

Add these test dependencies to your `omod/pom.xml` (required for OpenMRS context):

```xml
<dependencies>
  <!-- OpenMRS Test Framework -->
  <dependency>
    <groupId>org.openmrs.test</groupId>
    <artifactId>openmrs-test</artifactId>
    <type>pom</type>
    <scope>test</scope>
  </dependency>
  
  <!-- JUnit Platform (for test execution) -->
  <dependency>
    <groupId>org.junit.platform</groupId>
    <artifactId>junit-platform-launcher</artifactId>
    <scope>test</scope>
  </dependency>
  
  <!-- OpenMRS Web Test Support -->
  <dependency>
    <groupId>org.openmrs.web</groupId>
    <artifactId>openmrs-web</artifactId>
    <classifier>tests</classifier>
    <scope>test</scope>
  </dependency>
</dependencies>
```

### 4. Generate OpenAPI Specification

Run the plugin to generate your API spec:

```bash
# Generate during normal build
mvn clean process-classes

# Or run the plugin directly
mvn openmrs-rest-analyzer:analyze-representations

# Full build with spec generation
mvn clean install
```

### 5. View Your API Documentation

The generated OpenAPI specification will be at:
```
target/openapi/openapi.json
```

**Visualize with Swagger UI:**
1. Copy the JSON content
2. Go to https://editor.swagger.io/
3. Paste your specification
4. Enjoy interactive API documentation! 🎉

## 🔧 Usage Commands

### Basic Commands

```bash
# Generate OpenAPI spec during build (recommended)
mvn clean process-classes

# Run plugin directly
mvn openmrs-rest-analyzer:analyze-representations

# Full build with spec generation
mvn clean install

# Debug mode with verbose output
mvn openmrs-rest-analyzer:analyze-representations -X

# Custom timeout for large modules (default: 300 seconds)
mvn openmrs-rest-analyzer:analyze-representations -DtimeoutSeconds=600
```

### Integration with Build Lifecycle

The plugin automatically runs during the `process-classes` phase when configured in your `pom.xml`. No manual execution needed!

```bash
# These commands will automatically generate OpenAPI specs:
mvn compile
mvn test  
mvn package
mvn install
```

## ⚙️ Configuration Options

### Basic Configuration

```xml
<plugin>
  <groupId>org.openmrs.plugin</groupId>
  <artifactId>openmrs-rest-analyzer</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <configuration>
    <!-- Auto-detect resource packages (recommended) -->
    <autoDetectResources>true</autoDetectResources>
    
    <!-- OR specify packages manually -->
    <scanPackages>
      <package>org.openmrs.module.yourmodule.web.resources</package>
      <package>org.openmrs.module.yourmodule.api.resources</package>
    </scanPackages>
    
    <!-- OpenMRS version for compatibility -->
    <openmrsVersion>2.4.x</openmrsVersion>
  </configuration>
</plugin>
```

### Auto-Detection

The plugin automatically detects resource packages based on your module structure:

- `openmrs-module-queue` → `org.openmrs.module.queue.web.resources`
- `queue-omod` (groupId: `org.openmrs.module`) → `org.openmrs.module.queue.web.resources`
- Custom modules follow the same pattern

### Output Location

Generated specifications are always placed in:
```
target/openapi/openapi.json
```

## 🔍 Understanding the Output

### OpenAPI 3.0 Structure

The generated specification includes:

- **🛤️ Paths**: All REST endpoints with HTTP methods
- **📋 Parameters**: Path, query, and header parameters
- **🔄 Responses**: Success and error response schemas
- **📊 Schemas**: Data models for all representations (DEFAULT, FULL, REF)
- **📝 Examples**: Sample requests and responses

### Representation Support

OpenMRS REST resources support multiple representations:

- **DEFAULT**: Standard properties for most use cases
- **FULL**: Complete object with all properties and references
- **REF**: Minimal representation with UUID and display only
- **CUSTOM**: User-defined property selection (e.g., `custom:(uuid,name,person:(uuid,display))`)

### Sample Schema Generation

For a `Patient` resource, the plugin generates schemas like:

```json
{
  "components": {
    "schemas": {
      "PatientDefault": {
        "type": "object",
        "properties": {
          "uuid": { "type": "string" },
          "display": { "type": "string" },
          "identifiers": {
            "type": "array",
            "items": { "$ref": "#/components/schemas/PatientIdentifierRef" }
          },
          "person": { "$ref": "#/components/schemas/PersonRef" }
        }
      },
      "PatientFull": {
        "type": "object", 
        "properties": {
          "uuid": { "type": "string" },
          "display": { "type": "string" },
          "identifiers": {
            "type": "array",
            "items": { "$ref": "#/components/schemas/PatientIdentifierFull" }
          },
          "person": { "$ref": "#/components/schemas/PersonFull" },
          "voided": { "type": "boolean" },
          "dateCreated": { "type": "string", "format": "date-time" }
        }
      }
    }
  }
}
```

## 🛠️ Development & Testing

### Testing the Plugin

```bash
# Build plugin
cd openmrs-rest-representation-analyzer
mvn clean install

# Test with a sample module
cd ../openmrs-module-queue/omod
mvn clean process-classes

# Check generated spec
cat target/openapi/openapi.json | jq .
```

### Plugin Development Commands

```bash
# Build and test the plugin itself
mvn clean install

# Run plugin tests only
mvn test

# Debug plugin with verbose output
mvn clean install -X

# Analyze plugin dependencies
mvn dependency:tree
```

## 🚀 Integration Examples

### Example 1: Queue Module

```xml
<!-- In openmrs-module-queue/omod/pom.xml -->
<plugin>
  <groupId>org.openmrs.plugin</groupId>
  <artifactId>openmrs-rest-analyzer</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <executions>
    <execution>
      <id>generate-openapi-spec</id>
      <phase>process-classes</phase>
      <goals>
        <goal>analyze-representations</goal>
      </goals>
      <configuration>
        <autoDetectResources>true</autoDetectResources>
        <scanPackages>
          <package>org.openmrs.module.queue.web.resources</package>
        </scanPackages>
      </configuration>
    </execution>
  </executions>
</plugin>
```

**Generated endpoints:**
- `GET /ws/rest/v1/queue/{uuid}`
- `POST /ws/rest/v1/queue`
- `GET /ws/rest/v1/queue-room/{uuid}`
- `GET /ws/rest/v1/queue-entry/{uuid}`

### Example 2: Webservices REST Module

```xml
<!-- In openmrs-module-webservices.rest/omod/pom.xml -->
<plugin>
  <groupId>org.openmrs.plugin</groupId>
  <artifactId>openmrs-rest-analyzer</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <executions>
    <execution>
      <id>generate-openapi-spec</id>
      <phase>process-classes</phase>
      <goals>
        <goal>analyze-representations</goal>
      </goals>
      <configuration>
        <autoDetectResources>true</autoDetectResources>
      </configuration>
    </execution>
  </executions>
</plugin>
```

**Generated endpoints:** 100+ core OpenMRS REST endpoints including:
- Patient, Person, Encounter, Observation resources
- Concept, ConceptClass, ConceptDatatype resources  
- User, Role, Privilege resources

### Example 3: Custom Module

```xml
<!-- For any custom OpenMRS module -->
<plugin>
  <groupId>org.openmrs.plugin</groupId>
  <artifactId>openmrs-rest-analyzer</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <executions>
    <execution>
      <phase>process-classes</phase>
      <goals>
        <goal>analyze-representations</goal>
      </goals>
      <configuration>
        <scanPackages>
          <package>org.openmrs.module.mymodule.web.resources</package>
          <package>org.openmrs.module.mymodule.rest</package>
        </scanPackages>
      </configuration>
    </execution>
  </executions>
</plugin>
```

## 🔧 Troubleshooting

### Common Issues

**1. ClassNotFoundException during execution**
```bash
# Solution: Ensure test dependencies are present
<dependency>
  <groupId>org.openmrs.test</groupId>
  <artifactId>openmrs-test</artifactId>
  <type>pom</type>
  <scope>test</scope>
</dependency>
```

**2. Empty OpenAPI specification**
```bash
# Solution: Check resource package paths
mvn openmrs-rest-analyzer:analyze-representations -X
# Look for "Auto-detected resource packages" or "Using configured scan packages"
```

**3. Build timeout**
```bash
# Solution: Increase timeout for large modules
mvn openmrs-rest-analyzer:analyze-representations -DtimeoutSeconds=600
```

**4. Plugin not found**
```bash
# Solution: Install plugin first
cd openmrs-rest-representation-analyzer
mvn clean install
```

### Debug Output

Enable debug logging to see what the plugin is doing:

```bash
mvn openmrs-rest-analyzer:analyze-representations -X
```

This shows:
- Classpath construction
- Resource discovery process
- OpenMRS context initialization
- Schema generation details

## 📦 Dependencies

### Auto-Resolved Dependencies
The plugin automatically handles these dependencies:

- **OpenMRS API** 2.4+ (Core platform)
- **OpenMRS Web Services REST** 2.50+ (REST framework)
- **Jackson** 2.11+ (JSON processing)
- **JUnit Platform** (Test execution)
- **H2 Database** (In-memory testing)
- **Spring Framework** (Dependency injection)

### Required Module Dependencies
Your OpenMRS module must include:

```xml
<dependencies>
  <!-- OpenMRS Web Services REST -->
  <dependency>
    <groupId>org.openmrs.module</groupId>
    <artifactId>webservices.rest-omod</artifactId>
  </dependency>
  
  <!-- Test Framework (required for plugin) -->
  <dependency>
    <groupId>org.openmrs.test</groupId>
    <artifactId>openmrs-test</artifactId>
    <type>pom</type>
    <scope>test</scope>
  </dependency>
</dependencies>
```

## 🚀 Using the Generated Specification

### Swagger UI Integration

1. **Copy the generated spec:**
   ```bash
   cp target/openapi/openapi.json docs/api-spec.json
   ```

2. **Host with Swagger UI:**
   ```html
   <!DOCTYPE html>
   <html>
   <head>
     <title>Your Module API</title>
     <link rel="stylesheet" type="text/css" href="https://unpkg.com/swagger-ui-dist@3.25.0/swagger-ui.css" />
   </head>
   <body>
     <div id="swagger-ui"></div>
     <script src="https://unpkg.com/swagger-ui-dist@3.25.0/swagger-ui-bundle.js"></script>
     <script>
       SwaggerUIBundle({
         url: './api-spec.json',
         dom_id: '#swagger-ui'
       });
     </script>
   </body>
   </html>
   ```

### Client SDK Generation

Use OpenAPI Generator to create client SDKs:

```bash
# Generate JavaScript client
npx @openapitools/openapi-generator-cli generate \
  -i target/openapi/openapi.json \
  -g javascript \
  -o clients/javascript

# Generate Python client  
openapi-generator generate \
  -i target/openapi/openapi.json \
  -g python \
  -o clients/python
```

### Postman Integration

1. Open Postman
2. Click **Import**
3. Select **OpenAPI 3.0** 
4. Upload `target/openapi/openapi.json`
5. Your API collection is ready for testing! 🎉

## 🤝 Contributing

### How to Contribute

1. **🍴 Fork the repository**
2. **🌿 Create feature branch**: `git checkout -b feature/amazing-improvement`
3. **💾 Make your changes** with tests
4. **✅ Commit changes**: `git commit -m 'Add amazing improvement'`
5. **🚀 Push to branch**: `git push origin feature/amazing-improvement`
6. **📬 Open Pull Request**

### Development Guidelines
- Follow OpenMRS coding standards
- Add tests for new functionality  
- Update documentation for API changes
- Ensure backward compatibility
- Test with actual OpenMRS modules

### Feature Requests & Bug Reports

Found an issue or have an idea? Please [create an issue](https://github.com/capernix/openmrs-maven-plugin-openapi/issues) with:

- **🐛 Bug reports**: Steps to reproduce, expected vs actual behavior
- **✨ Feature requests**: Use case description and implementation ideas
- **📖 Documentation**: Improvements and clarifications

## 📚 Documentation & Resources

### OpenMRS Resources
- **📖 OpenMRS Wiki**: https://wiki.openmrs.org/
- **👨‍💻 Developer Documentation**: https://wiki.openmrs.org/display/docs/Developer+Documentation
- **🏗️ REST Web Services**: https://wiki.openmrs.org/display/docs/REST+Web+Services+API+For+Clients

### Related Projects
- **🌐 [OpenMRS REST Web Services](https://github.com/openmrs/openmrs-module-webservices.rest)** - Core REST framework
- **⚡ [OpenMRS Core](https://github.com/openmrs/openmrs-core)** - Platform foundation
- **📦 [OpenMRS Platform](https://github.com/openmrs/openmrs-distro-platform)** - Reference distribution

### API Specification Standards
- **📋 [OpenAPI 3.0 Specification](https://swagger.io/specification/)** - Industry standard
- **🛠️ [Swagger Tools](https://swagger.io/tools/)** - Ecosystem of tools
- **📖 [OpenAPI Generator](https://openapi-generator.tech/)** - Client SDK generation

## 📄 License

This project is licensed under the **Mozilla Public License 2.0** - see the [LICENSE](LICENSE) file for details.

## 🏥 OpenMRS Community

**Built with ❤️ for the OpenMRS Community**

> *Improving healthcare through open collaboration*

**Join the community:**
- 💬 [OpenMRS Talk](https://talk.openmrs.org/) - Community discussions
- 📧 [Developer Mailing List](https://wiki.openmrs.org/display/RES/Mailing+Lists) - Development updates
- 🐦 [Twitter](https://twitter.com/openmrs) - Latest news and updates

---

**Made possible by contributors from around the world 🌍**
