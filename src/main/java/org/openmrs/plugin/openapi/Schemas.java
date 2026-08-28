package org.openmrs.plugin.openapi;

import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;

/**
 * Factories for the schemas this plugin hand-builds, so they serialise correctly under the
 * OpenAPI 3.1 writer.
 * <p>
 * {@code Schema.setType(String)} only assigns the legacy scalar {@code type} field, while
 * {@code Json31} serialises the {@code types} set instead — so a schema built with
 * {@code new Schema<>().type("string")} came out as {@code {}} once the writer was switched to
 * 3.1, losing the type on 575 schemas. Schemas produced by swagger's own {@code ModelResolver} are
 * spec-version aware and were unaffected; only the ones built here needed a factory.
 * <p>
 * Both fields are set: {@code types} is what the 3.1 writer emits, and {@code type} keeps the model
 * readable by anything still looking at the 3.0 field.
 */
final class Schemas {

    private Schemas() {
    }

    /** A schema of the given JSON type, e.g. {@code string}, {@code boolean}, {@code integer}. */
    static <T> Schema<T> of(String type) {
        return withType(new Schema<T>(), type);
    }

    static ObjectSchema object() {
        return withType(new ObjectSchema(), "object");
    }

    static ArraySchema array() {
        return withType(new ArraySchema(), "array");
    }

    /** A bare {@code $ref}; carries no type of its own. */
    static Schema<Object> ref(String ref) {
        Schema<Object> schema = new Schema<Object>();
        schema.specVersion(SpecVersion.V31);
        schema.set$ref(ref);
        return schema;
    }

    /**
     * Reconciles the scalar {@code type} field with the {@code types} set across a whole schema
     * graph, so a document serialises the same under either writer.
     * <p>
     * Needed because the two fields are independent: {@code setType} does not touch {@code types},
     * the 3.1 writer emits only {@code types}, and the 3.0 writer emits only {@code type}. The
     * plugin's own schemas go through the factories above, but swagger's {@code ModelResolver}
     * produces some (43 in {@code webservices.rest}) that carry only the scalar field.
     */
    static void normalize(Schema<?> schema, java.util.Set<Schema<?>> seen) {
        if (schema == null || !seen.add(schema)) {
            return;
        }
        boolean hasTypes = schema.getTypes() != null && !schema.getTypes().isEmpty();
        if (schema.getType() != null && !hasTypes) {
            schema.addType(schema.getType());
        } else if (hasTypes && schema.getType() == null && schema.getTypes().size() == 1) {
            schema.setType(schema.getTypes().iterator().next());
        }

        if (schema.getProperties() != null) {
            for (Object property : schema.getProperties().values()) {
                normalize((Schema<?>) property, seen);
            }
        }
        normalize(schema.getItems(), seen);
        normalize(schema.getNot(), seen);
        if (schema.getAdditionalProperties() instanceof Schema) {
            normalize((Schema<?>) schema.getAdditionalProperties(), seen);
        }
        normalizeAll(schema.getAllOf(), seen);
        normalizeAll(schema.getAnyOf(), seen);
        normalizeAll(schema.getOneOf(), seen);
    }

    private static void normalizeAll(java.util.List<Schema> schemas, java.util.Set<Schema<?>> seen) {
        if (schemas == null) {
            return;
        }
        for (Schema<?> schema : schemas) {
            normalize(schema, seen);
        }
    }

    /** Normalises every schema reachable from a set of named schemas. */
    static void normalizeAll(java.util.Map<String, Schema> schemas) {
        if (schemas == null) {
            return;
        }
        java.util.Set<Schema<?>> seen = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<Schema<?>, Boolean>());
        for (Schema<?> schema : schemas.values()) {
            normalize(schema, seen);
        }
    }

    /**
     * Gives every named schema a {@code title} equal to its own key.
     * <p>
     * Renderers of an OpenAPI 3.1 document have no other way to name the branches of a union.
     * Swagger UI's JSON Schema 2020-12 renderer labels each {@code anyOf} branch
     * {@code `#${index} ${getTitle(schema)}`}, and its {@code getTitle} reads {@code title}, then
     * {@code $anchor}, then {@code $id} — none of which the generated schemas carried, so
     * {@code AlertGet} rendered as {@code #0 #1 #2 #3} rather than naming
     * {@code AlertGet_default} and friends. Verified in a browser against Swagger UI 5.32.14:
     * adding {@code title} is the whole fix, no renderer patch involved.
     * <p>
     * {@code title} rather than {@code $anchor} or {@code $id} because it is plain JSON Schema that
     * every renderer and most code generators already read, and it carries no resolution semantics
     * — {@code $id} would change how sibling {@code $ref}s resolve.
     * <p>
     * The title is always the schema's own key, so a generator that names models from {@code title}
     * produces exactly the names it would have produced from the keys. An explicit title survives:
     * only a null one is filled in, which leaves {@code @Schema(title = ...)} on a resource method
     * in control.
     * <p>
     * Named schemas only. Inline schemas inside {@code paths} have no key to take a title from, and
     * titling them would put a label on shapes that are not types.
     */
    static void titleAll(java.util.Map<String, ? extends Schema> schemas) {
        if (schemas == null) {
            return;
        }
        for (java.util.Map.Entry<String, ? extends Schema> entry : schemas.entrySet()) {
            Schema<?> schema = entry.getValue();
            if (schema != null && schema.getTitle() == null) {
                schema.setTitle(entry.getKey());
            }
        }
    }

    private static <S extends Schema<?>> S withType(S schema, String type) {
        schema.specVersion(SpecVersion.V31);
        schema.setType(type);
        schema.addType(type);
        return schema;
    }
}
