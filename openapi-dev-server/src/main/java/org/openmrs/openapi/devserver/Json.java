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
     * Points a document at the dev server's reverse proxy so the renderer's "try it" button reaches
     * the upstream without tripping CORS. No security scheme is declared: the proxy attaches the
     * {@code --auth} file's credentials to every request server-side, so the user never authenticates
     * in the UI.
     * <p>
     * When {@code upstreamUrl} is null there is no {@code --auth} file — "try it out" is disabled in
     * the UI — so the document is left untouched.
     */
    static ObjectNode makePlayable(ObjectNode doc, String proxyPath, String upstreamUrl) {
        if (upstreamUrl == null) {
            return doc;
        }
        ArrayNode servers = arr();
        servers.add(obj().put("url", proxyPath).put("description", "proxied to " + upstreamUrl));
        doc.set("servers", servers);
        return doc;
    }
}
