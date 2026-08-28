package org.openmrs.openapi.devserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cuts a self-contained OpenAPI document for one resource or controller: its own routes, plus the
 * transitive closure of every schema those routes can reach.
 * <p>
 * <b>Why slice at all.</b> A renderer's dominant cost is spec ingestion, not rendering. Swagger UI
 * converts the whole document to Immutable structures and resolves every {@code $ref} before you
 * can interact with it, at roughly 4&nbsp;ms per schema and regardless of how much is displayed —
 * a 768-schema module spec blocks the main thread for about three seconds even with everything
 * collapsed. One resource is around 48 schemas and renders in well under a second. Nothing in the
 * renderer's configuration turns that work off; a smaller document is the only lever.
 * <p>
 * <b>Why lazily.</b> The four modules under test slice to 149 documents; the 10–20 modules a real
 * OpenMRS install carries would be a few thousand, nearly all of which nobody opens in a session.
 * The closure is a few map lookups over schemas already parsed at startup, so computing it on
 * request costs less than the memory of holding them all. Each answer is memoised, so a resource
 * is sliced at most once per run.
 */
final class SpecSlicer {

    private final SpecCatalog catalog;
    private final String proxyPath;
    private final String upstreamUrl;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<String, byte[]>();

    SpecSlicer(SpecCatalog catalog, String proxyPath, String upstreamUrl) {
        this.catalog = catalog;
        this.proxyPath = proxyPath;
        this.upstreamUrl = upstreamUrl;
    }

    /** The slice for one entry, or null if the module or name is unknown. */
    byte[] slice(String module, String name) {
        String key = module + "/" + name;
        byte[] cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        ObjectNode doc = build(module, name);
        if (doc == null) {
            return null;
        }
        byte[] bytes = Json.compact(doc);
        cache.put(key, bytes);
        return bytes;
    }

    ObjectNode build(String module, String name) {
        ResourceEntry entry = catalog.entry(module, name);
        if (entry == null) {
            return null;
        }
        ObjectNode merged = catalog.mergedSpec(module);
        JsonNode mergedPaths = merged.path("paths");

        // The routes come from the per-resource file (which routes belong to this resource), but
        // the path objects come from the merged document, whose $refs are uniformly
        // "#/components/schemas/" and therefore resolve against the components block below.
        ObjectNode paths = Json.obj();
        for (ResourceEntry.Operation op : entry.operations) {
            if (!paths.has(op.path) && mergedPaths.has(op.path)) {
                paths.set(op.path, mergedPaths.get(op.path));
            }
        }

        JsonNode info = merged.path("info");
        ObjectNode doc = Json.obj();
        doc.put("openapi", merged.path("openapi").asText("3.1.0"));
        doc.set("info", Json.obj()
            .put("title", info.path("title").asText(module) + " — " + name)
            .put("version", info.path("version").asText("")));
        doc.set("paths", paths);
        doc.set("components", Json.obj().set("schemas", catalog.closure(paths, module)));
        return Json.makePlayable(doc, proxyPath, upstreamUrl);
    }
}
