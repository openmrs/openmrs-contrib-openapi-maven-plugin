package org.openmrs.plugin.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assembly step that turns the generator's per-controller files into the one document a
 * TypeScript client is generated from.
 */
class TypeScriptSpecAssemblerTest {

    private static final String DIAGNOSIS_CONTROLLER =
            "{"
            + "\"components\":{\"schemas\":{"
            + "  \"Diagnosis\":{\"type\":\"object\",\"properties\":{"
            + "     \"concept\":{\"$ref\":\"#/components/schemas/Concept\"}}}}},"
            + "\"paths\":{\"/ws/rest/{version}/emrapi/patientdiagnoses\":{\"get\":{"
            + "  \"tags\":[\"Diagnosis\",\"Controllers\",\"Clinical\"],"
            + "  \"operationId\":\"getDiagnoses\","
            + "  \"parameters\":["
            + "    {\"name\":\"patient\",\"in\":\"query\",\"required\":true,"
            + "     \"schema\":{\"type\":\"string\"}},"
            + "    {\"name\":\"version\",\"in\":\"path\",\"required\":true,"
            + "     \"schema\":{\"type\":\"string\",\"default\":\"v1\",\"enum\":[\"v1\"]}}],"
            + "  \"responses\":{\"200\":{\"description\":\"OK\",\"content\":{\"application/json\":{"
            + "     \"schema\":{\"type\":\"array\",\"items\":"
            + "        {\"$ref\":\"#/components/schemas/Diagnosis\"}}}}}}}}}}";

    /** Holds the Concept schema that DiagnosisController references but does not define. */
    private static final String MAIN_SPEC =
            "{\"openapi\":\"3.1.0\",\"paths\":{},\"components\":{\"schemas\":{"
            + "  \"Concept\":{\"type\":\"object\",\"properties\":{"
            + "     \"name\":{\"$ref\":\"#/components/schemas/ConceptName\"}}},"
            + "  \"ConceptName\":{\"type\":\"object\"},"
            + "  \"Unreferenced\":{\"type\":\"object\"}}}}";

    private TypeScriptSpecAssembler.Result assemble(Path dir, boolean inlineVersionSegment)
            throws IOException {
        Path controllers = dir.resolve("controllers");
        Files.createDirectories(controllers);
        Files.write(controllers.resolve("DiagnosisController.json"),
                DIAGNOSIS_CONTROLLER.getBytes(StandardCharsets.UTF_8));
        Path mainSpec = dir.resolve("openapi.json");
        Files.write(mainSpec, MAIN_SPEC.getBytes(StandardCharsets.UTF_8));

        return new TypeScriptSpecAssembler().assemble(
                controllers, mainSpec, "@openmrs/test-api", "desc", "1.0.0", inlineVersionSegment);
    }

    @Test
    void passesEveryTagThroughUntouched(@TempDir Path dir) throws IOException {
        TypeScriptSpecAssembler.Result result = assemble(dir, true);

        JsonNode tags = result.document.path("paths")
                .path("/ws/rest/v1/emrapi/patientdiagnoses").path("get").path("tags");
        // The assembler used to overwrite this array with the controller name, which threw away
        // every other tag. Grouping is handled by KEEP_ONLY_FIRST_TAG_IN_OPERATION at generation
        // time instead, so the document keeps them all.
        assertEquals(3, tags.size());
        assertEquals("Diagnosis", tags.get(0).asText());
        assertEquals("Controllers", tags.get(1).asText());
        assertEquals("Clinical", tags.get(2).asText());
    }

    @Test
    void takesTheApiClassFromTheFirstTag(@TempDir Path dir) throws IOException {
        TypeScriptSpecAssembler.Result result = assemble(dir, true);
        assertEquals(java.util.Collections.singletonList("Diagnosis"), result.apiTags);
        assertEquals(java.util.Collections.singleton("DiagnosisController"),
                result.tagOwners.get("Diagnosis"));
    }

    @Test
    void inlinesTheVersionSegmentAndDropsItsParameter(@TempDir Path dir) throws IOException {
        TypeScriptSpecAssembler.Result result = assemble(dir, true);
        JsonNode paths = result.document.path("paths");

        assertTrue(paths.has("/ws/rest/v1/emrapi/patientdiagnoses"));
        assertFalse(paths.has("/ws/rest/{version}/emrapi/patientdiagnoses"));

        JsonNode parameters = paths.path("/ws/rest/v1/emrapi/patientdiagnoses")
                .path("get").path("parameters");
        assertEquals(1, parameters.size(), "only the version parameter should have been removed");
        assertEquals("patient", parameters.get(0).path("name").asText());
    }

    @Test
    void keepsTheVersionParameterWhenNotInlining(@TempDir Path dir) throws IOException {
        TypeScriptSpecAssembler.Result result = assemble(dir, false);
        JsonNode paths = result.document.path("paths");

        assertTrue(paths.has("/ws/rest/{version}/emrapi/patientdiagnoses"));
        assertEquals(2, paths.path("/ws/rest/{version}/emrapi/patientdiagnoses")
                .path("get").path("parameters").size());
    }

    @Test
    void backfillsReferencedSchemasTransitively(@TempDir Path dir) throws IOException {
        TypeScriptSpecAssembler.Result result = assemble(dir, true);
        JsonNode schemas = result.document.path("components").path("schemas");

        // Concept is referenced by the controller's own DTO; ConceptName only by Concept, so it is
        // reached on a second pass.
        assertTrue(schemas.has("Concept"));
        assertTrue(schemas.has("ConceptName"));
        assertEquals(java.util.Arrays.asList("Concept", "ConceptName"), result.backfilledSchemas);
        assertTrue(result.unresolvedRefs.isEmpty());
    }

    @Test
    void doesNotDragInSchemasNothingReferences(@TempDir Path dir) throws IOException {
        TypeScriptSpecAssembler.Result result = assemble(dir, true);
        assertFalse(result.document.path("components").path("schemas").has("Unreferenced"));
    }

    @Test
    void stubsAndReportsARefNoLoadedDocumentDefines(@TempDir Path dir) throws IOException {
        Path controllers = dir.resolve("controllers");
        Files.createDirectories(controllers);
        Files.write(controllers.resolve("GhostController.json"),
                ("{\"paths\":{\"/ws/rest/v1/ghost\":{\"get\":{\"operationId\":\"get\","
                 + "\"responses\":{\"200\":{\"description\":\"OK\",\"content\":"
                 + "{\"application/json\":{\"schema\":"
                 + "{\"$ref\":\"#/components/schemas/Missing\"}}}}}}}}}")
                        .getBytes(StandardCharsets.UTF_8));

        TypeScriptSpecAssembler.Result result = new TypeScriptSpecAssembler().assemble(
                controllers, dir.resolve("absent.json"), "t", null, "1.0.0", true);

        // Reported, so a schema this build cannot see stays visible rather than passing as typed.
        assertEquals(java.util.Collections.singletonList("Missing"), result.unresolvedRefs);

        // And stubbed as a free-form object, because leaving the $ref dangling fails the whole
        // package: openapi-generator's spec validator rejects an unresolved $ref outright.
        JsonNode stub =
                result.document.path("components").path("schemas").get("Missing");
        assertNotNull(stub);
        assertEquals("object", stub.path("type").asText());
        assertTrue(stub.path("additionalProperties").asBoolean());
        assertEquals("Missing", stub.path("x-openmrs-unresolved-ref").asText());
    }

    @Test
    void producesTheSameDocumentEveryRun(@TempDir Path dir) throws IOException {
        assertEquals(assemble(dir, true).document.toString(),
                assemble(dir, true).document.toString());
    }
}
