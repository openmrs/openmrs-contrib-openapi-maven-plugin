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
 * Validates generated Patient.json schemas against a running OpenMRS instance.
 * Connection config is loaded by {@link OpenMrsExtension} from a .env file or env vars.
 *
 * Run with:
 *   mvn verify -Dopenapi.schemas.dir=<path-to-resources-dir>
 *
 * Skip with:
 *   mvn verify -DskipITs=true
 */
@ExtendWith(OpenMrsExtension.class)
class PatientSchemaIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Map<String, JsonNode> schemas;
    private static String patientUuid;

    @BeforeAll
    static void setup() throws Exception {
        schemas = SchemaLoader.loadRepresentationSchemas(OpenMrsExtension.SCHEMAS_DIR, "PatientResource1_9.json");
        patientUuid = fetchFirstPatientUuid();
    }

    @Test
    void getPatientDefault_conformsToSchema() throws Exception {
        JsonNode schema = schemas.get("default");
        assertNotNull(schema, "No 'default' representation schema found in Patient.json");

        JsonNode body = get("/openmrs/ws/rest/v1/patient/" + patientUuid + "?v=default");

        Set<ValidationMessage> errors = SchemaValidator.validate(schema, body);
        assertTrue(errors.isEmpty(),
                "Schema validation errors for Patient 'default' representation:\n" + errors);
    }

    @Test
    void getPatientFull_conformsToSchema() throws Exception {
        JsonNode schema = schemas.get("full");
        assertNotNull(schema, "No 'full' representation schema found in Patient.json");

        JsonNode body = get("/openmrs/ws/rest/v1/patient/" + patientUuid + "?v=full");

        Set<ValidationMessage> errors = SchemaValidator.validate(schema, body);
        assertTrue(errors.isEmpty(),
                "Schema validation errors for Patient 'full' representation:\n" + errors);
    }

    // -------------------------------------------------------------------------

    private static String fetchFirstPatientUuid() throws Exception {
        JsonNode body = get("/openmrs/ws/rest/v1/patient?limit=1");
        JsonNode results = body.get("results");
        Assumptions.assumeTrue(
                results != null && results.size() > 0,
                "Skipping: no patients found in the test OpenMRS instance");
        return results.get(0).get("uuid").asText();
    }

    private static JsonNode get(String path) throws Exception {
        OpenMrsExtension.HttpResult response = OpenMrsExtension.get(OpenMrsExtension.BASE_URL + path);
        assertEquals(200, response.statusCode(),
                "Unexpected HTTP status for GET " + path + ": " + response.body());
        return MAPPER.readTree(response.body());
    }
}
