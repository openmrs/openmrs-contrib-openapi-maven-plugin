package org.openmrs.openapi.devserver;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The navigation index the UI loads once: every resource, sub-resource and controller across
 * every loaded module, with its routes, summaries and property names.
 * <p>
 * Keys are one letter because this is fetched on every page load and the whole point of the
 * document is to be small — {@code m}odule, {@code k}ind, {@code n}ame, {@code o}perations
 * ({@code p}ath, {@code v}erb, {@code s}ummary), {@code f}ields, and {@code sub} for a
 * sub-resource's parent. Across the four modules under test that is ~100&nbsp;KB describing
 * 1.6&nbsp;MB of specs, and it parses in about a millisecond.
 * <p>
 * The plugin already knows all of this at build time. Emitting it per module as
 * {@code META-INF/openapi/index.json} and merging the per-module copies at startup is the
 * shape this is prototyping; until then it is derived here from the generated files.
 */
final class DocIndex {

    private DocIndex() {
    }

    static byte[] build(SpecCatalog catalog) {
        ArrayNode index = Json.arr();
        for (String module : catalog.modules()) {
            for (ResourceEntry entry : catalog.entriesFor(module)) {
                ObjectNode node = Json.obj();
                node.put("m", entry.module);
                node.put("k", entry.kind);
                node.put("n", entry.name);

                ArrayNode operations = Json.arr();
                for (ResourceEntry.Operation op : entry.operations) {
                    operations.add(Json.obj().put("p", op.path).put("v", op.verb)
                        .put("s", op.summary));
                }
                node.set("o", operations);

                ArrayNode fields = Json.arr();
                for (String field : entry.fields) {
                    fields.add(field);
                }
                node.set("f", fields);

                if (entry.parent != null) {
                    node.put("sub", entry.parent);
                }
                index.add(node);
            }
        }
        return Json.compact(index);
    }
}
