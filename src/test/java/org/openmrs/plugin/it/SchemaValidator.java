package org.openmrs.plugin.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.Set;

/**
 * Validates a JSON response body against a JSON Schema node.
 *
 * Uses JSON Schema 2020-12, where "format" is an annotation only (not a
 * validator), avoiding false failures from OpenMRS returning date-times in
 * ISO 8601 (+0000) rather than RFC 3339 (+00:00).
 *
 * Null-valued fields are stripped from the response before validation.
 * The generated schemas express optional fields as "type: string" rather than
 * "type: [string, null]", so a null value (e.g. stopDatetime on an open visit)
 * is treated the same as the field being absent.
 */
public class SchemaValidator {

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public static Set<ValidationMessage> validate(JsonNode schema, JsonNode data) {
        JsonSchema jsonSchema = FACTORY.getSchema(schema);
        return jsonSchema.validate(stripNulls(data));
    }

    /**
     * Recursively removes null-valued fields from objects and null elements
     * from arrays, so optional nullable fields don't cause type failures.
     *
     * TODO: remove this once the schema generator expresses nullable fields as
     *   "type": ["string", "null"] instead of "type": "string". At that point
     *   the schema itself accurately describes what the API returns and null
     *   values will validate correctly without stripping.
     */
    private static JsonNode stripNulls(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) {
                    result.set(entry.getKey(), stripNulls(entry.getValue()));
                }
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> {
                if (!item.isNull()) {
                    result.add(stripNulls(item));
                }
            });
            return result;
        }
        return node;
    }
}
