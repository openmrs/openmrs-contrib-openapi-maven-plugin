package org.openmrs.plugin.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CRUD integration tests for the Location resource.
 * Creates a fresh Location (prefixed "openapi-plugin-test-"), exercises GET/UPDATE/DELETE,
 * and validates each response against the generated Location.json schemas.
 *
 * Routes are read directly from the generated openapi.json so the test stays in sync
 * with the schema without any hardcoded paths.
 *
 * Tests run in declared order; later tests are skipped if the create step failed.
 *
 * Run with:
 *   mvn verify -Dopenapi.schemas.dir=<path-to-resources-dir>
 *
 * Skip with:
 *   mvn verify -DskipITs=true
 */
@ExtendWith(OpenMrsExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocationSchemaIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, JsonNode> schemas;

    /** e.g. "/ws/rest/v1/location" — resolved from openapi.json at setup time. */
    private static String collectionPath;

    /** e.g. "/ws/rest/v1/location/{uuid}" — resolved from openapi.json at setup time. */
    private static String instancePath;

    /** UUID of the location created in the first test; shared across the ordered tests. */
    private static String locationUuid;

    @BeforeAll
    static void setup() throws Exception {
        schemas = SchemaLoader.loadRepresentationSchemas(OpenMrsExtension.SCHEMAS_DIR, "Location.json");

        Map<String, PathItem> paths = SchemaLoader.loadResourcePaths(
                OpenMrsExtension.SCHEMAS_DIR, "location");
        collectionPath = SchemaLoader.collectionPath(paths);
        instancePath   = SchemaLoader.instancePath(paths);

        Assumptions.assumeTrue(collectionPath != null,
                "Skipping: no collection path found for Location in openapi.json");
        Assumptions.assumeTrue(instancePath != null,
                "Skipping: no instance path found for Location in openapi.json");
    }

    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void createLocation_returnsCreatedAndConformsToSchema() throws Exception {
        JsonNode schema = schemas.get("default");
        assertNotNull(schema, "No 'default' representation schema found in Location.json");

        Map<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("name", "openapi-plugin-test-location");
        payload.put("description", "Created by openapi-plugin IT tests");
        String requestBody = MAPPER.writeValueAsString(payload);

        OpenMrsExtension.HttpResult response = post(collectionUrl(), requestBody);
        assertEquals(201, response.statusCode(),
                "Expected 201 Created for new location: " + response.body());

        JsonNode created = MAPPER.readTree(response.body());
        locationUuid = created.path("uuid").asText(null);
        assertNotNull(locationUuid, "Response did not include a uuid");

        Set<ValidationMessage> errors = SchemaValidator.validate(schema, created);
        assertTrue(errors.isEmpty(),
                "Schema validation errors for Location create response:\n" + errors);
    }

    @Test
    @Order(2)
    void getLocation_conformsToSchema() throws Exception {
        Assumptions.assumeTrue(locationUuid != null,
                "Skipping: location was not created in the previous test");

        JsonNode schema = schemas.get("default");
        assertNotNull(schema, "No 'default' representation schema found in Location.json");

        JsonNode body = get(instanceUrl(locationUuid) + "?v=default");

        Set<ValidationMessage> errors = SchemaValidator.validate(schema, body);
        assertTrue(errors.isEmpty(),
                "Schema validation errors for Location 'default' representation:\n" + errors);
    }

    @Test
    @Order(3)
    void updateLocation_returnsUpdatedAndConformsToSchema() throws Exception {
        Assumptions.assumeTrue(locationUuid != null,
                "Skipping: location was not created in the previous test");

        JsonNode schema = schemas.get("default");
        assertNotNull(schema, "No 'default' representation schema found in Location.json");

        Map<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("description", "Updated by openapi-plugin IT tests");
        String requestBody = MAPPER.writeValueAsString(payload);

        OpenMrsExtension.HttpResult response = post(instanceUrl(locationUuid), requestBody);
        assertEquals(200, response.statusCode(),
                "Expected 200 OK for location update: " + response.body());

        JsonNode updated = MAPPER.readTree(response.body());
        assertEquals("Updated by openapi-plugin IT tests",
                updated.path("description").asText(),
                "Description was not updated in the response");

        Set<ValidationMessage> errors = SchemaValidator.validate(schema, updated);
        assertTrue(errors.isEmpty(),
                "Schema validation errors for Location update response:\n" + errors);
    }

    @Test
    @Order(4)
    void deleteLocation_returns204() throws Exception {
        Assumptions.assumeTrue(locationUuid != null,
                "Skipping: location was not created in the previous test");

        OpenMrsExtension.HttpResult response = delete(instanceUrl(locationUuid));
        assertEquals(204, response.statusCode(),
                "Expected 204 No Content when retiring location: " + response.body());
    }

    // -------------------------------------------------------------------------

    /** Full URL for the collection endpoint, e.g. http://localhost:8080/openmrs/ws/rest/v1/location */
    private static String collectionUrl() {
        return OpenMrsExtension.BASE_URL + OpenMrsExtension.CONTEXT_PATH + collectionPath;
    }

    /** Full URL for a specific location instance, with {uuid} substituted. */
    private static String instanceUrl(String uuid) {
        return OpenMrsExtension.BASE_URL + OpenMrsExtension.CONTEXT_PATH
                + instancePath.replace("{uuid}", uuid);
    }

    private static JsonNode get(String url) throws Exception {
        OpenMrsExtension.HttpResult response = OpenMrsExtension.get(url);
        assertEquals(200, response.statusCode(),
                "Unexpected HTTP status for GET " + url + ": " + response.body());
        return MAPPER.readTree(response.body());
    }

    private static OpenMrsExtension.HttpResult post(String url, String body) throws Exception {
        return OpenMrsExtension.post(url, body);
    }

    private static OpenMrsExtension.HttpResult delete(String url) throws Exception {
        return OpenMrsExtension.delete(url);
    }
}
