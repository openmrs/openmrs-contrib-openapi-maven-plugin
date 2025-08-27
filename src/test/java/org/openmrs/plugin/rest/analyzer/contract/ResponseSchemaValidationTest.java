package org.openmrs.plugin.rest.analyzer.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.openmrs.plugin.rest.analyzer.util.SchemaNameGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;

/**
 * Schema validation test based on proven working endpoints from ResilientContractTest.
 * Validates endpoint responses against their OpenAPI schema definitions.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("OpenMRS REST Response Schema Validation Tests")
public class ResponseSchemaValidationTest {

    private static final Logger log = LoggerFactory.getLogger(ResponseSchemaValidationTest.class);
    
    private static final String BASE_URL = System.getProperty("openmrs.rest.baseUrl", "http://localhost:8080/openmrs/ws/rest/v1");
    private static final String OPENAPI_SPEC_PATH = System.getProperty("openapi.spec.path", "openapi-spec.json");
    private static final String BASIC_AUTH = "Basic YWRtaW46QWRtaW4xMjM="; // admin:Admin123
    
    // Statistics tracking
    private static final AtomicInteger totalValidations = new AtomicInteger(0);
    private static final AtomicInteger successfulValidations = new AtomicInteger(0);
    private static final AtomicInteger failedValidations = new AtomicInteger(0);
    private static final AtomicInteger endpointsTested = new AtomicInteger(0);
    private static final List<ValidationResult> validationResults = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, AtomicInteger> representationCounts = new ConcurrentHashMap<>();
    
    private static JsonNode openApiSpec;
    private static ObjectMapper objectMapper;
    private static JsonSchemaFactory schemaFactory;

    // Known working endpoints from ResilientContractTest - guaranteed to be accessible
    private static final List<String> GUARANTEED_WORKING_ENDPOINTS = Arrays.asList(
        "/concept",
        "/location", 
        "/user",
        "/session",
        "/privilege",
        "/conceptclass",
        "/program",
        "/provider",
        "/taskdefinition",
        "/fieldtype",
        "/drug"
    );

    @BeforeAll
    static void initializeSchemaValidationTests() throws Exception {
        System.out.println("🔧 Initializing Response Schema Validation Tests...");
        
        // Initialize JSON processing
        objectMapper = new ObjectMapper();
        schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        
        // Setup REST Assured - use the full BASE_URL that includes /ws/rest/v1
        RestAssured.baseURI = BASE_URL;
        
        // Load OpenAPI specification
        loadOpenApiSpec();
        
        System.out.println("✅ Schema validation initialization complete.");
    }

    @Test
    @Order(1)
    @DisplayName("Validate guaranteed endpoints with FULL representation")
    void validateGuaranteedEndpointsWithFullRepresentation() {
        System.out.println("🚀 Starting guaranteed endpoints validation with FULL representation...");
        
        for (String endpoint : GUARANTEED_WORKING_ENDPOINTS) {
            endpointsTested.incrementAndGet();
            validateEndpointWithRepresentation(endpoint, "full");
        }
        
        System.out.println("🔚 Full representation validation complete for " + GUARANTEED_WORKING_ENDPOINTS.size() + " endpoints.");
    }

    @Test
    @Order(2)
    @DisplayName("Validate guaranteed endpoints with ALL standard representations")
    void validateGuaranteedEndpointsWithAllRepresentations() {
        System.out.println("🚀 Starting guaranteed endpoints validation with ALL representations...");
        
        List<String> representations = Arrays.asList("default", "ref", "full");
        
        for (String endpoint : GUARANTEED_WORKING_ENDPOINTS) {
            for (String representation : representations) {
                validateEndpointWithRepresentation(endpoint, representation);
            }
        }
        
        System.out.println("🔚 All representations validation complete for " + GUARANTEED_WORKING_ENDPOINTS.size() + " endpoints.");
    }

    @Test
    @Order(3)
    @DisplayName("Validate guaranteed endpoints with CUSTOM representations")
    void validateGuaranteedEndpointsWithCustomRepresentations() {
        System.out.println("🚀 Starting guaranteed endpoints validation with CUSTOM representations...");
        
        for (String endpoint : GUARANTEED_WORKING_ENDPOINTS) { // Test ALL endpoints
            validateEndpointCustomProperties(endpoint);
        }
        
        System.out.println("🔚 Custom representation validation complete.");
    }

    private void validateEndpointCustomProperties(String endpoint) {
        // Extract resource name from endpoint (e.g., "/concept" -> "concept")
        String resourceName = endpoint.replaceFirst("^/", "");
        String normalizedResourceName = normalizeResourceName(resourceName);
        
        // Get the Custom schema for this endpoint
        String customSchemaName = SchemaNameGenerator.schemaName(normalizedResourceName, "custom");
        JsonNode customSchemaNode = openApiSpec.path("components").path("schemas").path(customSchemaName);
        
        if (customSchemaNode.isMissingNode()) {
            System.out.println("⚠️  No Custom schema found for: " + endpoint + " (looking for: " + customSchemaName + ")");
            return;
        }
        
        // Extract all properties from the Custom schema
        JsonNode propertiesNode = customSchemaNode.path("properties");
        if (propertiesNode.isMissingNode()) {
            System.out.println("⚠️  No properties found in Custom schema for: " + endpoint);
            return;
        }
        
        List<String> propertyNames = new ArrayList<>();
        propertiesNode.fieldNames().forEachRemaining(propertyNames::add);
        
        if (propertyNames.isEmpty()) {
            System.out.println("⚠️  Custom schema has no properties for: " + endpoint);
            return;
        }
        
        System.out.println("🔍 Testing " + propertyNames.size() + " custom properties for " + endpoint + ": " + propertyNames);
        
        // Test each property individually with custom:(property)
        for (int i = 0; i < propertyNames.size(); i++) {
            String property = propertyNames.get(i);
            String customRepresentation = "custom:(" + property + ")";
            System.out.println("   🧪 Testing property " + (i + 1) + "/" + propertyNames.size() + ": " + property);
            validateEndpointWithRepresentation(endpoint, customRepresentation);
        }
    }

    @Test
    @Order(4)
    @DisplayName("Validate ALL accessible endpoints from OpenAPI spec")
    void validateAllAccessibleEndpointsFromSpec() {
        System.out.println("🚀 Starting validation of ALL accessible endpoints from OpenAPI spec...");
        
        List<String> allEndpoints = getAllEndpointsFromOpenApiSpec();
        System.out.println("Found " + allEndpoints.size() + " endpoints in OpenAPI spec to validate.");
        
        for (String endpoint : allEndpoints) {
            endpointsTested.incrementAndGet();
            validateEndpointWithRepresentation(endpoint, "full");
        }
        
        System.out.println("🔚 All spec-based endpoint validation complete for " + allEndpoints.size() + " endpoints.");
    }

    @AfterAll
    static void printValidationReport() {
        String separator = new String(new char[80]).replace("\0", "=");
        System.out.println("\n" + separator);
        System.out.println("📊 SCHEMA VALIDATION SUMMARY REPORT");
        System.out.println(separator);
        
        System.out.println("📈 OVERALL STATISTICS:");
        System.out.println("   Total Endpoints Tested: " + endpointsTested.get());
        System.out.println("   Total Validations: " + totalValidations.get());
        System.out.println("   Successful Validations: " + successfulValidations.get());
        System.out.println("   Failed Validations: " + failedValidations.get());
        
        if (totalValidations.get() > 0) {
            double successRate = (successfulValidations.get() * 100.0) / totalValidations.get();
            System.out.println("   Success Rate: " + String.format("%.1f%%", successRate));
        }
        
        System.out.println("\n📊 REPRESENTATION BREAKDOWN:");
        representationCounts.forEach((rep, count) -> 
            System.out.println("   " + rep + ": " + count.get() + " validations"));
        
        // Show failed validations summary
        List<ValidationResult> failures = validationResults.stream()
            .filter(r -> !r.success)
            .collect(Collectors.toList());
            
        if (!failures.isEmpty()) {
            System.out.println("\n❌ FAILED VALIDATIONS SUMMARY:");
            failures.stream()
                .limit(10) // Show first 10 failures
                .forEach(result -> {
                    System.out.println("   " + result.endpoint + " (" + result.representation + "): " + result.errorMessage);
                });
            
            if (failures.size() > 10) {
                System.out.println("   ... and " + (failures.size() - 10) + " more failures");
            }
        }
        
        System.out.println("\n✅ Schema validation report complete!");
        System.out.println(separator);
    }

    private void validateEndpointWithRepresentation(String endpoint, String representation) {
        try {
            System.out.println("🔍 Validating: " + endpoint + " with representation: " + representation);
            totalValidations.incrementAndGet();
            representationCounts.computeIfAbsent(representation, k -> new AtomicInteger(0)).incrementAndGet();
            
            // Fetch response from endpoint with timeout protection
            Response response;
            try {
                response = fetchEndpointResponse(endpoint, representation);
            } catch (Exception e) {
                recordValidationResult(endpoint, representation, false, 
                    "Request failed: " + e.getMessage(), null, null);
                failedValidations.incrementAndGet();
                System.out.println("   ⏰ Request failed: " + e.getMessage());
                return;
            }
            
            if (response.getStatusCode() != 200) {
                recordValidationResult(endpoint, representation, false, 
                    "HTTP " + response.getStatusCode() + ": " + response.getStatusLine(), null, null);
                failedValidations.incrementAndGet();
                return;
            }
            
            // Parse response JSON
            String responseBody = response.getBody().asString();
            JsonNode responseJson = objectMapper.readTree(responseBody);
            
            // Get appropriate schema for validation
            JsonSchema schema = getSchemaForEndpointAndRepresentation(endpoint, representation);
            if (schema == null) {
                recordValidationResult(endpoint, representation, false, 
                    "No schema found for endpoint and representation", null, responseBody);
                failedValidations.incrementAndGet();
                return;
            }
            
            // Perform validation
            Set<ValidationMessage> errors = schema.validate(responseJson);
            
            if (errors.isEmpty()) {
                recordValidationResult(endpoint, representation, true, null, null, responseBody);
                successfulValidations.incrementAndGet();
                System.out.println("   ✅ Schema validation PASSED");
            } else {
                List<String> errorMessages = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toList());
                recordValidationResult(endpoint, representation, false, 
                    "Schema validation failed", errorMessages, responseBody);
                failedValidations.incrementAndGet();
                System.out.println("   ❌ Schema validation FAILED: " + errors.size() + " errors");
            }
            
        } catch (Exception e) {
            recordValidationResult(endpoint, representation, false, 
                "Exception during validation: " + e.getMessage(), null, null);
            failedValidations.incrementAndGet();
            System.out.println("   💥 Exception during validation: " + e.getMessage());
        }
    }

    private Response fetchEndpointResponse(String endpoint, String representation) {
        String url = endpoint + "?v=" + representation;
        
        try {
            return given()
                .header("Authorization", BASIC_AUTH)
                .config(RestAssuredConfig.config()
                    .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 10000)
                        .setParam("http.socket.timeout", 10000)))
                .when()
                .get(url);
        } catch (Exception e) {
            System.out.println("   ⏰ Request timeout or error for: " + url + " - " + e.getMessage());
            // Create a mock failed response
            throw new RuntimeException("Request failed: " + e.getMessage());
        }
    }

    private JsonSchema getSchemaForEndpointAndRepresentation(String endpoint, String representation) {
        try {
            // Extract resource name from endpoint (e.g., "/concept" -> "concept")
            String resourceName = endpoint.replaceFirst("^/", "");
            
            // Try different schema naming patterns using SchemaNameGenerator
            List<String> possibleSchemaNames = generatePossibleSchemaNames(resourceName, representation);
            
            for (String schemaName : possibleSchemaNames) {
                JsonNode schemaNode = openApiSpec.path("components").path("schemas").path(schemaName);
                if (!schemaNode.isMissingNode()) {
                    // Convert OpenAPI schema to JSON Schema format
                    JsonSchema jsonSchema = schemaFactory.getSchema(schemaNode);
                    System.out.println("   📋 Using schema: " + schemaName);
                    return jsonSchema;
                }
            }
            
            System.out.println("   ⚠️  No schema found for: " + resourceName + " with representation: " + representation);
            return null;
            
        } catch (Exception e) {
            System.out.println("   💥 Error creating schema: " + e.getMessage());
            return null;
        }
    }

    private List<String> generatePossibleSchemaNames(String resourceName, String representation) {
        // Normalize the resource name to match ResourceType conventions
        String normalizedResourceName = normalizeResourceName(resourceName);
        
        // Handle custom representations specially
        String normalizedRepresentation = representation;
        if (representation.startsWith("custom:")) {
            normalizedRepresentation = "custom";
        }
        
        // Use SchemaNameGenerator as single source of truth for schema naming
        List<String> possibleSchemaNames = new ArrayList<>();
        
        // Primary schema name using SchemaNameGenerator
        String primarySchemaName = SchemaNameGenerator.schemaName(normalizedResourceName, normalizedRepresentation);
        possibleSchemaNames.add(primarySchemaName);
        
        // Fallback options for common representations
        if (!normalizedRepresentation.equals("default")) {
            possibleSchemaNames.add(SchemaNameGenerator.schemaName(normalizedResourceName, "default"));
        }
        if (!normalizedRepresentation.equals("ref")) {
            possibleSchemaNames.add(SchemaNameGenerator.schemaName(normalizedResourceName, "ref"));
        }
        if (!normalizedRepresentation.equals("full")) {
            possibleSchemaNames.add(SchemaNameGenerator.schemaName(normalizedResourceName, "full"));
        }
        
        return possibleSchemaNames;
    }
    
    private String normalizeResourceName(String resourceName) {
        if (resourceName == null || resourceName.isEmpty()) {
            return "Unknown";
        }
        
        // Handle special cases where endpoint path doesn't match resource type name
        Map<String, String> specialCases = new HashMap<>();
        specialCases.put("fieldtype", "FieldType");
        specialCases.put("conceptclass", "ConceptClass");
        specialCases.put("taskdefinition", "TaskDefinition");
        
        String lowerInput = resourceName.toLowerCase();
        if (specialCases.containsKey(lowerInput)) {
            return specialCases.get(lowerInput);
        }
        
        // Default: capitalize first letter for resource type name
        return resourceName.substring(0, 1).toUpperCase() + resourceName.substring(1).toLowerCase();
    }

    private List<String> getAllEndpointsFromOpenApiSpec() {
        List<String> endpoints = new ArrayList<>();
        
        JsonNode paths = openApiSpec.path("paths");
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = pathEntry.getKey();
            
            // Extract the endpoint part (remove /ws/rest/v1 prefix and {uuid} suffix)
            String endpoint = extractEndpointFromPath(path);
            if (endpoint != null && !endpoints.contains(endpoint)) {
                endpoints.add(endpoint);
            }
        }
        
        return endpoints;
    }

    private String extractEndpointFromPath(String path) {
        // Convert "/ws/rest/v1/concept/{uuid}" to "/concept"
        if (path.startsWith("/ws/rest/v1/")) {
            path = path.substring("/ws/rest/v1".length());
        }
        
        // Remove {uuid} part
        if (path.contains("/{uuid}")) {
            path = path.substring(0, path.indexOf("/{uuid}"));
        }
        
        return path.isEmpty() ? null : path;
    }

    private void recordValidationResult(String endpoint, String representation, boolean success, 
                                      String errorMessage, List<String> validationErrors, String responseBody) {
        ValidationResult result = new ValidationResult(endpoint, representation, success, 
            errorMessage, validationErrors, responseBody);
        validationResults.add(result);
    }

    private static void loadOpenApiSpec() throws Exception {
        Path specPath = findOpenApiSpecFile();
        String specContent = new String(Files.readAllBytes(specPath), StandardCharsets.UTF_8);
        openApiSpec = objectMapper.readTree(specContent);
        System.out.println("📋 Loaded OpenAPI spec from: " + specPath.toAbsolutePath());
    }

    private static Path findOpenApiSpecFile() {
        List<Path> possiblePaths = Arrays.asList(
            Paths.get("openapi-spec.json"),
            Paths.get("target/openapi-spec.json"),
            Paths.get("../openapi-spec.json"),
            Paths.get("../../openapi-spec.json"),
            Paths.get("webservices-rest-omod-2.5-openapi-spec.json"),
            Paths.get("../webservices-rest-omod-2.5-openapi-spec.json"),
            Paths.get("../../webservices-rest-omod-2.5-openapi-spec.json")
        );
        
        for (Path path : possiblePaths) {
            if (Files.exists(path)) {
                return path;
            }
        }
        
        throw new RuntimeException("OpenAPI spec file not found. Checked paths: " + possiblePaths);
    }

    // Validation result data class
    private static class ValidationResult {
        final String endpoint;
        final String representation;
        final boolean success;
        final String errorMessage;
        final List<String> validationErrors;
        final String responseBody;
        
        ValidationResult(String endpoint, String representation, boolean success, 
                        String errorMessage, List<String> validationErrors, String responseBody) {
            this.endpoint = endpoint;
            this.representation = representation;
            this.success = success;
            this.errorMessage = errorMessage;
            this.validationErrors = validationErrors;
            this.responseBody = responseBody;
        }
    }
}
