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

import java.io.InputStream;
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
    private static final String OPENAPI_SPEC_RESOURCE = "/openapi.json";  // Standardized location in test resources
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

    @Test
    @Order(4)
    @DisplayName("Validate ALL endpoints with ALL representations (comprehensive test)")
    void validateAllEndpointsWithAllRepresentations() {
        System.out.println("🚀 Starting COMPREHENSIVE validation of ALL endpoints with ALL representations...");
        
        // Standard representations that work for all endpoints
        List<String> standardRepresentations = Arrays.asList("default", "ref", "full");
        
        for (String endpoint : GUARANTEED_WORKING_ENDPOINTS) {
            System.out.println("\n📍 Testing endpoint: " + endpoint);
            
            // Test standard representations
            System.out.println("  🔹 Testing standard representations...");
            for (String representation : standardRepresentations) {
                validateEndpointWithRepresentation(endpoint, representation);
            }
            
            // Test custom property representations
            System.out.println("  🔹 Testing custom property representations...");
            validateEndpointCustomProperties(endpoint);
        }
        
        System.out.println("\n🔚 COMPREHENSIVE validation complete for " + GUARANTEED_WORKING_ENDPOINTS.size() + " endpoints.");
    }

    private void validateEndpointCustomProperties(String endpoint) {
        // Extract resource name from endpoint (e.g., "/concept" -> "concept")
        String resourceName = endpoint.replaceFirst("^/", "");
        
        // Use the same normalization logic as the Maven plugin for consistency
        String resourceType = normalizeEndpointToResourceType(resourceName);
        
        // Get the Custom schema for this endpoint using SchemaNameGenerator (same as Maven plugin)
        String customSchemaName = SchemaNameGenerator.schemaName(resourceType, "custom");
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
    @Order(5)
    @DisplayName("Validate ALL accessible endpoints from OpenAPI spec")
    void validateAllAccessibleEndpointsFromSpec() {
        System.out.println("🚀 Starting validation of ALL accessible endpoints from OpenAPI spec...");
        
        List<String> allEndpoints = getAllEndpointsFromOpenApiSpec();
        System.out.println("Found " + allEndpoints.size() + " endpoints in OpenAPI spec to test accessibility.");
        
        // Discover which endpoints are actually accessible
        List<String> accessibleEndpoints = discoverAccessibleEndpoints(allEndpoints);
        System.out.println("✅ Found " + accessibleEndpoints.size() + " accessible endpoints out of " + allEndpoints.size() + " total.");
        
        for (String endpoint : accessibleEndpoints) {
            endpointsTested.incrementAndGet();
            validateEndpointWithRepresentation(endpoint, "full");
        }
        
        System.out.println("🔚 All spec-based endpoint validation complete for " + accessibleEndpoints.size() + " accessible endpoints.");
    }

    @Test
    @Order(6)
    @DisplayName("Dynamic validation of ALL accessible endpoints with path-to-schema mapping")
    void validateAllAccessibleEndpointsWithDynamicMapping() {
        System.out.println("🚀 Starting DYNAMIC validation with path-to-schema mapping...");
        
        // Get all endpoints from OpenAPI spec
        List<String> allEndpoints = getAllEndpointsFromOpenApiSpec();
        System.out.println("📋 Found " + allEndpoints.size() + " endpoints in OpenAPI spec.");
        
        // Discover accessible endpoints dynamically
        List<String> accessibleEndpoints = discoverAccessibleEndpoints(allEndpoints);
        System.out.println("✅ Discovered " + accessibleEndpoints.size() + " accessible endpoints.");
        
        // For each accessible endpoint, determine available representations and validate
        for (String endpoint : accessibleEndpoints) {
            System.out.println("\n📍 Processing endpoint: " + endpoint);
            
            // Get available representations for this endpoint from OpenAPI spec
            List<String> availableRepresentations = getAvailableRepresentationsForEndpoint(endpoint);
            System.out.println("  🔹 Available representations: " + availableRepresentations);
            
            // Validate each available representation
            for (String representation : availableRepresentations) {
                validateEndpointWithDynamicSchemaMapping(endpoint, representation);
            }
            
            // Also test custom properties if available
            validateEndpointCustomPropertiesWithDynamicMapping(endpoint);
        }
        
        System.out.println("\n🔚 Dynamic validation complete for " + accessibleEndpoints.size() + " accessible endpoints.");
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
        
        // Show failed validations summary
        List<ValidationResult> failures = validationResults.stream()
            .filter(r -> !r.success)
            .collect(Collectors.toList());
            
        if (!failures.isEmpty()) {
            System.out.println("\n❌ FAILED VALIDATIONS SUMMARY:");
            failures.stream()
                .forEach(result -> {
                    System.out.println("   " + result.endpoint + " (" + result.representation + "): " + result.errorMessage);
                });
            
            // if (failures.size() > 10) {
            //     System.out.println("   ... and " + (failures.size() - 10) + " more failures");
            // }
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
        // Normalize the resource name to match ResourceType conventions (same as Maven plugin)
        String resourceType = normalizeEndpointToResourceType(resourceName);
        
        // Handle custom representations specially
        String normalizedRepresentation = representation;
        if (representation.startsWith("custom:")) {
            normalizedRepresentation = "custom";
        }
        
        // Use SchemaNameGenerator as single source of truth for schema naming
        List<String> possibleSchemaNames = new ArrayList<>();
        
        // Primary schema name using SchemaNameGenerator (same as Maven plugin)
        String primarySchemaName = SchemaNameGenerator.schemaName(resourceType, normalizedRepresentation);
        possibleSchemaNames.add(primarySchemaName);
        
        // Fallback options for common representations
        if (!normalizedRepresentation.equals("default")) {
            possibleSchemaNames.add(SchemaNameGenerator.schemaName(resourceType, "default"));
        }
        if (!normalizedRepresentation.equals("ref")) {
            possibleSchemaNames.add(SchemaNameGenerator.schemaName(resourceType, "ref"));
        }
        if (!normalizedRepresentation.equals("full")) {
            possibleSchemaNames.add(SchemaNameGenerator.schemaName(resourceType, "full"));
        }
        
        return possibleSchemaNames;
    }
    
    /**
     * Normalizes endpoint path to resource type name for schema lookup.
     * This ensures consistency with how the Maven plugin generates schema names.
     * 
     * Examples:
     * "/concept" -> "Concept"
     * "/fieldtype" -> "FieldType" 
     * "/conceptclass" -> "ConceptClass"
     */
    private String normalizeEndpointToResourceType(String resourceName) {
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
        // Load OpenAPI spec from standardized location in test resources
        try (InputStream specStream = ResponseSchemaValidationTest.class.getResourceAsStream(OPENAPI_SPEC_RESOURCE)) {
            if (specStream == null) {
                throw new RuntimeException("OpenAPI spec file not found at: " + OPENAPI_SPEC_RESOURCE + 
                    ". Please ensure openapi.json exists in src/test/resources/");
            }
            
            // Read the InputStream using ObjectMapper directly
            openApiSpec = objectMapper.readTree(specStream);
            System.out.println("📋 Loaded OpenAPI spec from: " + OPENAPI_SPEC_RESOURCE);
        }
    }

    /**
     * Dynamically discover which endpoints are accessible by testing them
     */
    private List<String> discoverAccessibleEndpoints(List<String> allEndpoints) {
        List<String> accessibleEndpoints = new ArrayList<>();
        System.out.println("🔍 Testing accessibility of " + allEndpoints.size() + " endpoints...");
        
        for (String endpoint : allEndpoints) {
            try {
                Response response = given()
                    .header("Authorization", BASIC_AUTH)
                    .config(RestAssuredConfig.config()
                        .httpClient(HttpClientConfig.httpClientConfig()
                            .setParam("http.connection.timeout", 10000)
                            .setParam("http.socket.timeout", 10000)))
                    .when()
                    .get(endpoint + "?v=default&limit=1")
                    .then()
                    .extract()
                    .response();
                
                int statusCode = response.getStatusCode();
                
                // Consider 200, 404 (empty result), and even 500 as "accessible"
                // 401, 403, 405 indicate the endpoint exists but has access issues
                if (statusCode == 200 || statusCode == 404 || statusCode == 500 || 
                    statusCode == 401 || statusCode == 403) {
                    accessibleEndpoints.add(endpoint);
                    System.out.println("  ✅ " + endpoint + " → " + statusCode + " (accessible)");
                } else {
                    System.out.println("  ❌ " + endpoint + " → " + statusCode + " (not accessible)");
                }
                
            } catch (Exception e) {
                System.out.println("  ❌ " + endpoint + " → ERROR: " + e.getMessage());
            }
        }
        
        return accessibleEndpoints;
    }

    /**
     * Get available representations for an endpoint by analyzing OpenAPI spec paths
     */
    private List<String> getAvailableRepresentationsForEndpoint(String endpoint) {
        Set<String> representations = new HashSet<>();
        
        // Find the path in OpenAPI spec that matches this endpoint
        JsonNode paths = openApiSpec.path("paths");
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = pathEntry.getKey();
            
            // Check if this path corresponds to our endpoint
            if (pathMatchesEndpoint(path, endpoint)) {
                // Examine the response schemas to detect available representations
                JsonNode pathItem = pathEntry.getValue();
                JsonNode getOperation = pathItem.path("get");
                
                if (!getOperation.isMissingNode()) {
                    JsonNode responses = getOperation.path("responses");
                    JsonNode response200 = responses.path("200");
                    
                    if (!response200.isMissingNode()) {
                        JsonNode content = response200.path("content");
                        JsonNode applicationJson = content.path("application/json");
                        JsonNode schema = applicationJson.path("schema");
                        
                        // Look for oneOf schemas to detect different representations
                        if (schema.has("oneOf")) {
                            JsonNode oneOfSchemas = schema.path("oneOf");
                            for (JsonNode oneOfSchema : oneOfSchemas) {
                                String ref = oneOfSchema.path("$ref").asText();
                                String representationType = extractRepresentationFromRef(ref);
                                if (representationType != null) {
                                    representations.add(representationType);
                                }
                            }
                        } else if (schema.has("$ref")) {
                            // Single schema reference
                            String ref = schema.path("$ref").asText();
                            String representationType = extractRepresentationFromRef(ref);
                            if (representationType != null) {
                                representations.add(representationType);
                            }
                        }
                    }
                }
            }
        }
        
        // If we couldn't detect from schema, use standard representations
        if (representations.isEmpty()) {
            representations.addAll(Arrays.asList("default", "ref", "full"));
        } else {
            // Always include default if not detected
            representations.add("default");
        }
        
        return new ArrayList<>(representations);
    }

    /**
     * Check if an OpenAPI path matches an endpoint
     */
    private boolean pathMatchesEndpoint(String path, String endpoint) {
        // Convert "/ws/rest/v1/concept/{uuid}" to "/concept" and compare with endpoint
        String extractedEndpoint = extractEndpointFromPath(path);
        return endpoint.equals(extractedEndpoint);
    }

    /**
     * Extract representation type from schema $ref
     * e.g., "#/components/schemas/ConceptDefault" -> "default"
     */
    private String extractRepresentationFromRef(String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        
        // Extract schema name from #/components/schemas/ConceptDefault
        String[] parts = ref.split("/");
        if (parts.length > 0) {
            String schemaName = parts[parts.length - 1];
            
            // Extract representation from schema name
            if (schemaName.endsWith("Default")) {
                return "default";
            } else if (schemaName.endsWith("Full")) {
                return "full";
            } else if (schemaName.endsWith("Ref")) {
                return "ref";
            } else if (schemaName.endsWith("Custom")) {
                return "custom";
            }
        }
        
        return null;
    }

    /**
     * Validate endpoint with dynamic schema mapping - finds the correct schema based on OpenAPI spec
     */
    private void validateEndpointWithDynamicSchemaMapping(String endpoint, String representation) {
        // Extract resource name and normalize it
        String resourceName = endpoint.replaceFirst("^/", "");
        String resourceType = normalizeEndpointToResourceType(resourceName);
        
        // Find the actual schema name from OpenAPI spec for this endpoint and representation
        String schemaName = findSchemaNameFromSpec(endpoint, representation);
        
        if (schemaName == null) {
            // Fallback to our naming convention
            schemaName = SchemaNameGenerator.schemaName(resourceType, representation);
        }
        
        System.out.println("  🔍 Validating " + endpoint + " with representation '" + representation + "' using schema: " + schemaName);
        
        // Use the existing validation logic but with dynamically determined schema
        validateEndpointAgainstSchema(endpoint, representation, schemaName);
    }

    /**
     * Find the correct schema name from OpenAPI spec for given endpoint and representation
     */
    private String findSchemaNameFromSpec(String endpoint, String representation) {
        JsonNode paths = openApiSpec.path("paths");
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = pathEntry.getKey();
            
            if (pathMatchesEndpoint(path, endpoint)) {
                JsonNode pathItem = pathEntry.getValue();
                JsonNode getOperation = pathItem.path("get");
                
                if (!getOperation.isMissingNode()) {
                    JsonNode responses = getOperation.path("responses");
                    JsonNode response200 = responses.path("200");
                    JsonNode content = response200.path("content");
                    JsonNode applicationJson = content.path("application/json");
                    JsonNode schema = applicationJson.path("schema");
                    
                    // Look for oneOf schemas and find the one matching our representation
                    if (schema.has("oneOf")) {
                        JsonNode oneOfSchemas = schema.path("oneOf");
                        for (JsonNode oneOfSchema : oneOfSchemas) {
                            String ref = oneOfSchema.path("$ref").asText();
                            String detectedRepresentation = extractRepresentationFromRef(ref);
                            
                            if (representation.equals(detectedRepresentation)) {
                                // Extract schema name from ref
                                String[] parts = ref.split("/");
                                if (parts.length > 0) {
                                    return parts[parts.length - 1];
                                }
                            }
                        }
                    } else if (schema.has("$ref")) {
                        // Single schema - check if it matches our representation
                        String ref = schema.path("$ref").asText();
                        String detectedRepresentation = extractRepresentationFromRef(ref);
                        
                        if (representation.equals(detectedRepresentation)) {
                            String[] parts = ref.split("/");
                            if (parts.length > 0) {
                                return parts[parts.length - 1];
                            }
                        }
                    }
                }
            }
        }
        
        return null; // Schema not found in spec
    }

    /**
     * Validate endpoint against a specific schema
     */
    private void validateEndpointAgainstSchema(String endpoint, String representation, String schemaName) {
        totalValidations.incrementAndGet();
        
        try {
            // Make the API call
            Response response = given()
                .header("Authorization", BASIC_AUTH)
                .config(RestAssuredConfig.config()
                    .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 10000)
                        .setParam("http.socket.timeout", 10000)))
                .when()
                .get(endpoint + "?v=" + representation + "&limit=1")
                .then()
                .extract()
                .response();
            
            if (response.getStatusCode() != 200) {
                failedValidations.incrementAndGet();
                String errorMsg = "HTTP " + response.getStatusCode() + ": " + response.getStatusLine();
                recordValidationResult(endpoint, representation, false, errorMsg, 
                    Collections.singletonList("Non-200 response"), response.getBody().asString());
                return;
            }
            
            // Parse response
            JsonNode responseJson = objectMapper.readTree(response.getBody().asString());
            
            // Get schema
            JsonNode schemaNode = openApiSpec.path("components").path("schemas").path(schemaName);
            if (schemaNode.isMissingNode()) {
                failedValidations.incrementAndGet();
                String errorMsg = "Schema not found: " + schemaName;
                recordValidationResult(endpoint, representation, false, errorMsg, 
                    Collections.singletonList("Missing schema"), response.getBody().asString());
                return;
            }
            
            // Validate
            JsonSchema schema = schemaFactory.getSchema(schemaNode);
            Set<ValidationMessage> validationMessages = schema.validate(responseJson);
            
            if (validationMessages.isEmpty()) {
                successfulValidations.incrementAndGet();
                recordValidationResult(endpoint, representation, true, null, Collections.emptyList(), 
                    response.getBody().asString());
                representationCounts.computeIfAbsent(representation, k -> new AtomicInteger(0)).incrementAndGet();
            } else {
                failedValidations.incrementAndGet();
                List<String> errors = validationMessages.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toList());
                String errorMsg = "Schema validation failed: " + String.join(", ", errors);
                recordValidationResult(endpoint, representation, false, errorMsg, errors, 
                    response.getBody().asString());
            }
            
        } catch (Exception e) {
            failedValidations.incrementAndGet();
            String errorMsg = "Exception: " + e.getMessage();
            recordValidationResult(endpoint, representation, false, errorMsg, 
                Collections.singletonList("Execution exception"), "");
        }
    }

    /**
     * Validate custom properties with dynamic mapping
     */
    private void validateEndpointCustomPropertiesWithDynamicMapping(String endpoint) {
        // Extract resource name from endpoint (e.g., "/concept" -> "concept")
        String resourceName = endpoint.replaceFirst("^/", "");
        String resourceType = normalizeEndpointToResourceType(resourceName);
        
        // Try to find Custom schema using dynamic mapping first
        String customSchemaName = findSchemaNameFromSpec(endpoint, "custom");
        
        // Fallback to naming convention if not found in spec
        if (customSchemaName == null) {
            customSchemaName = SchemaNameGenerator.schemaName(resourceType, "custom");
        }
        
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
            
            // For individual properties, we can't use full schema validation, just test accessibility
            validateEndpointAccessibility(endpoint, customRepresentation);
        }
    }

    /**
     * Simple accessibility test for custom property representations
     */
    private void validateEndpointAccessibility(String endpoint, String representation) {
        totalValidations.incrementAndGet();
        
        try {
            Response response = given()
                .header("Authorization", BASIC_AUTH)
                .config(RestAssuredConfig.config()
                    .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 10000)
                        .setParam("http.socket.timeout", 10000)))
                .when()
                .get(endpoint + "?v=" + representation + "&limit=1")
                .then()
                .extract()
                .response();
            
            if (response.getStatusCode() == 200) {
                successfulValidations.incrementAndGet();
                recordValidationResult(endpoint, representation, true, null, Collections.emptyList(), 
                    response.getBody().asString());
                representationCounts.computeIfAbsent(representation, k -> new AtomicInteger(0)).incrementAndGet();
            } else {
                failedValidations.incrementAndGet();
                String errorMsg = "HTTP " + response.getStatusCode() + ": " + response.getStatusLine();
                recordValidationResult(endpoint, representation, false, errorMsg, 
                    Collections.singletonList("Non-200 response"), response.getBody().asString());
            }
            
        } catch (Exception e) {
            failedValidations.incrementAndGet();
            String errorMsg = "Exception: " + e.getMessage();
            recordValidationResult(endpoint, representation, false, errorMsg, 
                Collections.singletonList("Execution exception"), "");
        }
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
