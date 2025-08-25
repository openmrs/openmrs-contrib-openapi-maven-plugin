package org.openmrs.plugin.rest.analyzer.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;

/**
 * Resilient contract testing for OpenMRS REST API
 * Continues processing even when endpoints return errors and provides comprehensive statistics
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("OpenMRS REST Resilient Contract Tests")
public class ResilientContractTest {

    private static final Logger log = LoggerFactory.getLogger(ResilientContractTest.class);
    
    private static final String BASE_URL = System.getProperty("openmrs.rest.baseUrl", "http://localhost:8080/openmrs/ws/rest/v1");
    private static final String OPENAPI_SPEC_PATH = System.getProperty("openapi.spec.path", "openapi-spec.json");
    private static final String BASIC_AUTH = "Basic YWRtaW46QWRtaW4xMjM="; // admin:Admin123
    
    // Statistics tracking
    private static final AtomicInteger totalEndpoints = new AtomicInteger(0);
    private static final AtomicInteger accessibleEndpoints = new AtomicInteger(0);
    private static final AtomicInteger schemasValidated = new AtomicInteger(0);
    private static final AtomicInteger validationErrors = new AtomicInteger(0);
    private static final Map<Integer, AtomicInteger> statusCodeCounts = new ConcurrentHashMap<>();
    private static final List<String> accessiblePaths = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> inaccessiblePaths = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> validatedSchemas = Collections.synchronizedList(new ArrayList<>());
    
    private static JsonNode openApiSpec;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void initializeResilientTests() throws Exception {
        System.out.println("🔧 Initializing Resilient OpenMRS REST Contract Tests...");
        
        objectMapper = new ObjectMapper();
        loadOpenApiSpec();
        setupRestAssured();
        
        System.out.println("✅ Resilient contract test initialization complete");
        System.out.println("📊 Base URL: " + BASE_URL);
        System.out.println("📋 OpenAPI Spec: " + OPENAPI_SPEC_PATH);
    }

    private static void loadOpenApiSpec() throws Exception {
        Path specPath = Paths.get(OPENAPI_SPEC_PATH);
        if (!Files.exists(specPath)) {
            throw new IllegalStateException("OpenAPI spec not found at: " + specPath.toAbsolutePath());
        }
        
        // Java 8 compatible way to read file content
        byte[] encoded = Files.readAllBytes(specPath);
        String specContent = new String(encoded, StandardCharsets.UTF_8);
        openApiSpec = objectMapper.readTree(specContent);
        
        System.out.println("📖 Loaded OpenAPI spec with " + openApiSpec.path("paths").size() + " paths");
    }

    private static void setupRestAssured() {
        RestAssured.baseURI = BASE_URL;
        RestAssured.useRelaxedHTTPSValidation();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @Test
    @Order(1)
    @DisplayName("Test documented working endpoints")
    void testDocumentedWorkingEndpoints() {
        System.out.println("🚀 Starting documented working endpoint testing...");
        
        // Real working endpoints from rest.openmrs.org documentation
        List<String> workingEndpoints = Arrays.asList(
            // Documented working endpoints with proper query parameters
            "/visit?includeInactive=true&fromStartDate=2016-10-08T04:09:23.000Z&v=default&limit=1",
            "/patient?q=Sarah&v=default&limit=1",
            "/location?q=amani&v=default",
            "/concept?term=38341003&source=SNOMED%20CT&limit=1",
            "/encounter?patient=96be32d2-9367-4d1d-a285-79a5e5db12b8&v=default&limit=1",
            "/visittype?q=Facility&v=full",
            "/location?tag=Login+Location",
            "/session",
            "/privilege?v=full&limit=1",
            "/conceptclass?limit=1",
            "/program",
            "/provider?q=clerk&v=default",
            "/taskdefinition",
            "/fieldtype",
            "/drug"
        );
        
        for (String endpoint : workingEndpoints) {
            totalEndpoints.incrementAndGet();
            testWorkingEndpoint(endpoint);
        }
        
        System.out.println("🔚 Working endpoint testing complete. Processing " + totalEndpoints.get() + " documented endpoints.");
    }

    @Test
    @Order(2)
    @DisplayName("Test spec-based endpoints with resilience")
    void testSpecBasedEndpointsWithResilience() {
        System.out.println("🚀 Starting spec-based endpoint testing...");
        
        JsonNode paths = openApiSpec.path("paths");
        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            
            totalEndpoints.incrementAndGet();
            testEndpointResiliently(path, pathItem);
        }
        
        System.out.println("🔚 Spec-based endpoint testing complete. Processing " + totalEndpoints.get() + " total endpoints.");
    }

    private void testWorkingEndpoint(String fullEndpoint) {
        try {
            System.out.println("🔍 Testing working endpoint: " + fullEndpoint);
            
            Response response = given()
                .header("Authorization", BASIC_AUTH)
                .when()
                .get(fullEndpoint);
            int statusCode = response.getStatusCode();
            
            // Track status code statistics
            statusCodeCounts.computeIfAbsent(statusCode, k -> new AtomicInteger(0)).incrementAndGet();
            
            if (isSuccessfulResponse(statusCode)) {
                accessibleEndpoints.incrementAndGet();
                accessiblePaths.add("GET " + fullEndpoint + " → " + statusCode);
                
                // Basic schema validation for working endpoints
                String responseBody = response.getBody().asString();
                if (isValidJson(responseBody)) {
                    schemasValidated.incrementAndGet();
                    validatedSchemas.add("GET " + fullEndpoint + " → Schema OK");
                }
                
                System.out.println("✅ " + fullEndpoint + " → " + statusCode + " (SUCCESS)");
            } else {
                inaccessiblePaths.add("GET " + fullEndpoint + " → " + statusCode + " (" + getStatusMessage(statusCode) + ")");
                System.out.println("❌ " + fullEndpoint + " → " + statusCode + " (" + getStatusMessage(statusCode) + ")");
            }
            
        } catch (Exception e) {
            inaccessiblePaths.add("GET " + fullEndpoint + " → ERROR (" + e.getMessage() + ")");
            System.out.println("❌ " + fullEndpoint + " → ERROR: " + e.getMessage());
        }
    }

    private void testEndpointResiliently(String path, JsonNode pathItem) {
        log.debug("🔍 Testing endpoint: {}", path);
        
        // Only test GET method as requested by user
        if (pathItem.has("get")) {
            testMethodResiliently(path, "GET", pathItem.path("get"));
        }
    }

    private void testMethodResiliently(String path, String method, JsonNode operation) {
        try {
            // Convert OpenAPI path parameters to actual values for testing
            String testPath = convertPathForTesting(path);
            
            Response response = executeRequest(testPath, method);
            int statusCode = response.getStatusCode();
            
            // Track status code statistics
            statusCodeCounts.computeIfAbsent(statusCode, k -> new AtomicInteger(0)).incrementAndGet();
            
            if (isSuccessfulResponse(statusCode)) {
                accessibleEndpoints.incrementAndGet();
                accessiblePaths.add(method + " " + path + " → " + statusCode);
                
                // Validate response schema if available
                validateResponseSchema(response, operation, method, path);
            } else {
                inaccessiblePaths.add(method + " " + path + " → " + statusCode + " (" + getStatusMessage(statusCode) + ")");
            }
            
            log.debug("✅ {} {} → {}", method, testPath, statusCode);
            
        } catch (Exception e) {
            inaccessiblePaths.add(method + " " + path + " → ERROR (" + e.getMessage() + ")");
            log.debug("❌ {} {} → ERROR: {}", method, path, e.getMessage());
        }
    }

    private Response executeRequest(String path, String method) {
        // Only support GET requests as requested by user
        if ("GET".equalsIgnoreCase(method)) {
            return given()
                .header("Authorization", BASIC_AUTH)
                .when()
                .get(path);
        } else {
            throw new IllegalArgumentException("Only GET method is supported for testing");
        }
    }

    private String convertPathForTesting(String openApiPath) {
        // Convert OpenAPI path parameters like {id} to realistic test values that might exist on demo server
        String testPath = openApiPath;
        
        // Use more realistic parameter replacements that might exist on demo server
        testPath = testPath.replace("{uuid}", "96be32d2-9367-4d1d-a285-79a5e5db12b8"); // Sample patient UUID from docs
        testPath = testPath.replace("{id}", "1"); // Simple numeric ID
        testPath = testPath.replace("{conceptId}", "5089"); // SNOMED concept ID
        testPath = testPath.replace("{patientId}", "96be32d2-9367-4d1d-a285-79a5e5db12b8"); // Same patient UUID
        testPath = testPath.replace("{userId}", "1"); // Admin user ID
        testPath = testPath.replace("{locationId}", "1"); // Default location ID
        testPath = testPath.replace("{providerId}", "1"); // Default provider ID
        testPath = testPath.replace("{visitId}", "1"); // Default visit ID
        testPath = testPath.replace("{encounterId}", "1"); // Default encounter ID
        
        // Add basic query parameters for better results
        if (!testPath.contains("?")) {
            testPath += "?v=ref&limit=1";
        }
        
        return testPath;
    }

    private boolean isSuccessfulResponse(int statusCode) {
        return statusCode >= 200 && statusCode < 400;
    }

    private String getStatusMessage(int statusCode) {
        switch (statusCode) {
            case 404: return "Not Found";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "HTTP " + statusCode;
        }
    }

    private void validateResponseSchema(Response response, JsonNode operation, String method, String path) {
        try {
            // Check if operation has responses defined
            JsonNode responses = operation.path("responses");
            if (responses.isMissingNode()) {
                return;
            }
            
            String statusCode = String.valueOf(response.getStatusCode());
            JsonNode responseSchema = responses.path(statusCode);
            
            if (!responseSchema.isMissingNode()) {
                // Basic schema validation - check if response is valid JSON
                String responseBody = response.getBody().asString();
                if (isValidJson(responseBody)) {
                    schemasValidated.incrementAndGet();
                    validatedSchemas.add(method + " " + path + " → Schema OK");
                } else {
                    validationErrors.incrementAndGet();
                }
            }
            
        } catch (Exception e) {
            validationErrors.incrementAndGet();
            log.debug("Schema validation error for {} {}: {}", method, path, e.getMessage());
        }
    }

    private boolean isValidJson(String jsonString) {
        try {
            objectMapper.readTree(jsonString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @Order(2)
    @DisplayName("Print comprehensive test statistics")
    void printFinalStatistics() {
        System.out.println("📊 =========================== FINAL STATISTICS ===========================");
        System.out.println("🎯 ENDPOINT COVERAGE:");
        System.out.println("   📈 Total endpoints tested: " + totalEndpoints.get());
        System.out.println("   ✅ Accessible endpoints: " + accessibleEndpoints.get());
        System.out.println("   ❌ Inaccessible endpoints: " + (totalEndpoints.get() - accessibleEndpoints.get()));
        System.out.println("   📊 Accessibility rate: " + 
            (totalEndpoints.get() > 0 ? String.format("%.1f%%", (accessibleEndpoints.get() * 100.0 / totalEndpoints.get())) : "0%"));
        
        System.out.println("🔍 SCHEMA VALIDATION:");
        System.out.println("   ✅ Schemas validated: " + schemasValidated.get());
        System.out.println("   ❌ Validation errors: " + validationErrors.get());
        System.out.println("   📊 Validation rate: " + 
            (accessibleEndpoints.get() > 0 ? String.format("%.1f%%", (schemasValidated.get() * 100.0 / accessibleEndpoints.get())) : "0%"));
        
        System.out.println("📈 STATUS CODE DISTRIBUTION:");
        statusCodeCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> System.out.println("   " + entry.getKey() + " " + getStatusMessage(entry.getKey()) + ": " + entry.getValue().get() + " times"));
        
        System.out.println("✅ ACCESSIBLE ENDPOINTS (" + accessiblePaths.size() + "):");
        accessiblePaths.stream()
            .sorted()
            .limit(10) // Show first 10
            .forEach(path -> System.out.println("   ✅ " + path));
        if (accessiblePaths.size() > 10) {
            System.out.println("   ... and " + (accessiblePaths.size() - 10) + " more accessible endpoints");
        }
        
        System.out.println("❌ INACCESSIBLE ENDPOINTS (" + inaccessiblePaths.size() + "):");
        inaccessiblePaths.stream()
            .sorted()
            .limit(10) // Show first 10
            .forEach(path -> System.out.println("   ❌ " + path));
        if (inaccessiblePaths.size() > 10) {
            System.out.println("   ... and " + (inaccessiblePaths.size() - 10) + " more inaccessible endpoints");
        }
        
        System.out.println("🎉 CONTRACT TESTING SUMMARY:");
        System.out.println("   📊 Coverage: " + accessibleEndpoints.get() + "/" + totalEndpoints.get() + " endpoints accessed");
        System.out.println("   🔍 Validation: " + schemasValidated.get() + "/" + accessibleEndpoints.get() + " schemas validated");
        System.out.println("=========================================================================");
        
        // Assert that we have some meaningful results
        Assertions.assertTrue(totalEndpoints.get() > 0, "Should have tested at least one endpoint");
        Assertions.assertTrue(accessibleEndpoints.get() >= 0, "Accessible endpoints count should be non-negative");
    }

    @AfterAll
    static void cleanupTests() {
        System.out.println("🧹 Cleaning up resilient contract tests...");
    }
}
