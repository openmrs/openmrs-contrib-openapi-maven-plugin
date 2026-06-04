package org.openmrs.plugin.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates generated Visit.json schemas against a running OpenMRS instance.
 * Connection config is loaded by {@link OpenMrsExtension} from a .env file or env vars.
 *
 * Run with:
 *   mvn verify -Dopenapi.schemas.dir=<path-to-resources-dir>
 *
 * Skip with:
 *   mvn verify -DskipITs=true
 */
@ExtendWith(OpenMrsExtension.class)
class VisitSchemaIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Map<String, JsonNode> schemas;
    private static String visitUuid;

    @BeforeAll
    static void setup() throws Exception {
        schemas = SchemaLoader.loadRepresentationSchemas(OpenMrsExtension.SCHEMAS_DIR, "Visit.json");
        visitUuid = fetchFirstVisitUuid();
    }

    @Test
    void getVisitDefault_conformsToSchema() throws Exception {
        JsonNode schema = schemas.get("default");
        assertNotNull(schema, "No 'default' representation schema found in Visit.json");

        JsonNode body = get("/openmrs/ws/rest/v1/visit/" + visitUuid + "?v=default");

        Set<ValidationMessage> errors = SchemaValidator.validate(schema, body);
        assertTrue(errors.isEmpty(),
                "Schema validation errors for Visit 'default' representation:\n" + errors);
    }

    @Test
    void getVisitFull_conformsToSchema() throws Exception {
        JsonNode schema = schemas.get("full");
        assertNotNull(schema, "No 'full' representation schema found in Visit.json");

        JsonNode body = get("/openmrs/ws/rest/v1/visit/" + visitUuid + "?v=full");

        Set<ValidationMessage> errors = SchemaValidator.validate(schema, body);
        assertTrue(errors.isEmpty(),
                "Schema validation errors for Visit 'full' representation:\n" + errors);
    }

    // -------------------------------------------------------------------------

    private static String fetchFirstVisitUuid() throws Exception {
        JsonNode body = get("/openmrs/ws/rest/v1/visit?limit=1");
        JsonNode results = body.get("results");
        Assumptions.assumeTrue(
                results != null && results.size() > 0,
                "Skipping: no visits found in the test OpenMRS instance");
        return results.get(0).get("uuid").asText();
    }

    private static JsonNode get(String path) throws Exception {
        OpenMrsExtension.HttpResult response = OpenMrsExtension.get(OpenMrsExtension.BASE_URL + path);
        assertEquals(200, response.statusCode(),
                "Unexpected HTTP status for GET " + path + ": " + response.body());
        return MAPPER.readTree(response.body());
    }
}
