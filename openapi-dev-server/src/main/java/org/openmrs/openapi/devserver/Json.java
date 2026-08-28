package org.openmrs.openapi.devserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * Shared JSON helpers. No HTTP here — everything in this package below
 * {@link OpenApiDevServer} works on files and JSON trees only, so the same code can be lifted
 * into a Spring {@code @Controller} inside the REST module with the HTTP shell rewritten.
 */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** The only $ref form the merged openapi.json documents use. */
    static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    private Json() {
    }

    static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    static byte[] compact(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise JSON", e);
        }
    }

    static byte[] pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise JSON", e);
        }
    }

    /** Adds every "#/components/schemas/<name>" target found anywhere under {@code node}. */
    static void collectSchemaRefs(JsonNode node, Set<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && ref.asText().startsWith(SCHEMA_REF_PREFIX)) {
                out.add(ref.asText().substring(SCHEMA_REF_PREFIX.length()));
            }
            node.fields().forEachRemaining(entry -> collectSchemaRefs(entry.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectSchemaRefs(child, out));
        }
    }

    /**
     * Points a document at the dev server's reverse proxy and declares HTTP basic auth, which is
     * what makes the renderer's "try it" button work without tripping CORS.
     */
    static ObjectNode makePlayable(ObjectNode doc, String proxyPath, String upstreamUrl) {
        ArrayNode servers = arr();
        servers.add(obj().put("url", proxyPath).put("description", "proxied to " + upstreamUrl));
        doc.set("servers", servers);

        ObjectNode components = doc.has("components") && doc.get("components").isObject()
                ? (ObjectNode) doc.get("components") : obj();
        ObjectNode schemes = components.has("securitySchemes")
                && components.get("securitySchemes").isObject()
                ? (ObjectNode) components.get("securitySchemes") : obj();
        schemes.set("basicAuth", obj().put("type", "http").put("scheme", "basic"));
        components.set("securitySchemes", schemes);
        doc.set("components", components);

        ArrayNode security = arr();
        security.add(obj().set("basicAuth", arr()));
        doc.set("security", security);
        return doc;
    }
}
