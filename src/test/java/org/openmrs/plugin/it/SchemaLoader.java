package org.openmrs.plugin.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Loads generated OpenAPI schema files and extracts per-representation schemas.
 *
 * Each schema file has the structure:
 *   {
 *     "schemas": {
 *       "<ResourceName>": { "anyOf": [ {"$ref": "#/schemas/ResourceGet"}, {"$ref": "#/schemas/ResourceCreate"}, ... ] },
 *       "ResourceGet": { "anyOf": [ {"$ref": "#/schemas/ResourceGet_default"}, {"$ref": "#/schemas/ResourceGet_full"}, ... ] },
 *       "ResourceGet_default": { "x-openmrs-representation": "default", "properties": { ... } },
 *       ...
 *     }
 *   }
 *
 * Schemas for individual representations are identified by the custom
 * "x-openmrs-representation" extension field (e.g. "default", "full").
 *
 * Cross-file $ref nodes (e.g. "./Patient.json#/schemas/PatientGet_ref") are resolved
 * by loading the referenced file and inlining the schema. Circular references are
 * broken by replacing already-visited refs with {} (accepts any value).
 */
public class SchemaLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param schemasDir  path to the resources directory
     * @param fileName    e.g. "Patient.json"
     * @return map of representation name ("default", "full", ...) to its schema node
     */
    public static Map<String, JsonNode> loadRepresentationSchemas(String schemasDir, String fileName)
            throws IOException {
        JsonNode root = MAPPER.readTree(Paths.get(schemasDir, fileName).toFile());
        String resourceName = fileName.replace(".json", "");
        JsonNode schemas = root.get("schemas");
        JsonNode anyOf = schemas.get(resourceName).get("anyOf");

        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode anyOfItem : anyOf) {
            // Top-level anyOf items are $refs to ResourceGet, ResourceCreate, ResourceUpdate.
            // Follow one level of $ref to find ResourceGet, then iterate its anyOf for ResourceGet_* schemas.
            JsonNode refNode = anyOfItem.get("$ref");
            if (refNode == null) continue;
            String ref = refNode.asText();
            String schemaName = ref.substring(ref.lastIndexOf('/') + 1);
            JsonNode schema = schemas.get(schemaName);
            if (schema == null) continue;

            JsonNode innerAnyOf = schema.get("anyOf");
            if (innerAnyOf == null) continue;

            for (JsonNode innerItem : innerAnyOf) {
                JsonNode innerRef = innerItem.get("$ref");
                if (innerRef == null) continue;
                String innerSchemaName = innerRef.asText();
                innerSchemaName = innerSchemaName.substring(innerSchemaName.lastIndexOf('/') + 1);
                JsonNode innerSchema = schemas.get(innerSchemaName);
                if (innerSchema == null) continue;
                JsonNode repNode = innerSchema.get("x-openmrs-representation");
                if (repNode != null) {
                    result.put(repNode.asText(), resolveRefs(innerSchema, schemasDir, new HashSet<>()));
                }
            }
        }
        return result;
    }

    /**
     * Recursively resolves $ref nodes by loading the referenced file and inlining the schema.
     * Only cross-file refs (containing "#") are resolved; internal refs are left as-is.
     * Already-visited refs are replaced with {} to break cycles.
     */
    static JsonNode resolveRefs(JsonNode node, String schemasDir, Set<String> visited) {
        if (node.isObject() && node.has("$ref")) {
            String ref = node.get("$ref").asText();
            int hashIdx = ref.indexOf('#');
            if (hashIdx < 0) {
                // Not a cross-file ref we can resolve
                return JsonNodeFactory.instance.objectNode();
            }
            if (visited.contains(ref)) {
                // Break cycle
                return JsonNodeFactory.instance.objectNode();
            }
            String filePart = ref.substring(0, hashIdx);
            String pointerPart = ref.substring(hashIdx + 1);
            String refFileName = filePart.startsWith("./") ? filePart.substring(2) : filePart;
            try {
                JsonNode refRoot = MAPPER.readTree(Paths.get(schemasDir, refFileName).toFile());
                JsonNode target = navigatePointer(refRoot, pointerPart);
                if (target == null) {
                    return JsonNodeFactory.instance.objectNode();
                }
                Set<String> newVisited = new HashSet<>(visited);
                newVisited.add(ref);
                return resolveRefs(target, schemasDir, newVisited);
            } catch (IOException e) {
                return JsonNodeFactory.instance.objectNode();
            }
        }
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry ->
                result.set(entry.getKey(), resolveRefs(entry.getValue(), schemasDir, visited)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> result.add(resolveRefs(item, schemasDir, visited)));
            return result;
        }
        return node;
    }

    /**
     * Loads paths for a specific resource from the main openapi.json file.
     *
     * Parses the fully-valid openapi.json (one directory above schemasDir) using
     * swagger-parser and returns only the paths whose key contains the resource name
     * (case-insensitive). $refs inside path items are not resolved — we only need
     * the path strings and their HTTP operations.
     *
     * Example: loadResourcePaths("/…/resources", "location") returns
     *   { "/ws/rest/v1/location": PathItem, "/ws/rest/v1/location/{uuid}": PathItem }
     *
     * @param schemasDir path to the resources directory
     * @param resourceName case-insensitive resource name to filter by (e.g. "location")
     */
    public static Map<String, PathItem> loadResourcePaths(String schemasDir, String resourceName)
            throws IOException {
        String openApiFile = Paths.get(schemasDir).getParent().resolve("openapi.json").toString();
        ParseOptions opts = new ParseOptions();
        opts.setResolve(false);
        SwaggerParseResult result = new OpenAPIParser().readLocation(openApiFile, null, opts);
        OpenAPI openAPI = result.getOpenAPI();
        if (openAPI == null || openAPI.getPaths() == null) {
            throw new IOException("Could not parse openapi.json at " + openApiFile
                    + ": " + result.getMessages());
        }
        String lower = resourceName.toLowerCase();
        Map<String, PathItem> matched = new LinkedHashMap<>();
        openAPI.getPaths().forEach((path, item) -> {
            if (path.toLowerCase().contains("/" + lower)) {
                matched.put(path, item);
            }
        });
        return matched;
    }

    /**
     * Returns the collection path (no path parameters) from a resource path map,
     * e.g. "/ws/rest/v1/location".
     */
    public static String collectionPath(Map<String, PathItem> paths) {
        return paths.keySet().stream()
                .filter(p -> !p.contains("{"))
                .findFirst().orElse(null);
    }

    /**
     * Returns the instance path (contains {uuid}) from a resource path map,
     * e.g. "/ws/rest/v1/location/{uuid}".
     */
    public static String instancePath(Map<String, PathItem> paths) {
        return paths.keySet().stream()
                .filter(p -> p.contains("{uuid}"))
                .findFirst().orElse(null);
    }

    /**
     * Navigates a JSON Pointer (e.g. "/schemas/PatientGet_ref") within a JsonNode tree.
     * Returns null if any segment is not found.
     */
    private static JsonNode navigatePointer(JsonNode root, String pointer) {
        JsonNode current = root;
        for (String part : pointer.split("/")) {
            if (part.isEmpty()) continue;
            if (current == null || !current.has(part)) return null;
            current = current.get(part);
        }
        return current;
    }
}
