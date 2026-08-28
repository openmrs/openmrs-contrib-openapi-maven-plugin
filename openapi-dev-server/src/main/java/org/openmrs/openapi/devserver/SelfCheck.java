package org.openmrs.openapi.devserver;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Slices every entry and reports what came out — run with {@code --self-check}.
 * <p>
 * The number that matters is <b>dangling refs: 0</b>. A slice is only useful if it resolves on its
 * own, and a resource whose closure silently stops short renders with "Could not resolve
 * reference" errors in place of half its schema. That failure is invisible when spot-checking:
 * the bug this check exists for affected 9 of 149 slices, and the one that was noticed by eye was
 * not the worst of them.
 * <p>
 * The borrowed count is the corroborating figure. If cross-module resolution regresses to
 * own-module-only it goes to zero, and the dangling count goes up by the same schemas.
 */
final class SelfCheck {

    private SelfCheck() {
    }

    static boolean run(SpecCatalog catalog, SpecSlicer slicer) {
        int sliced = 0;
        int totalBytes = 0;
        int subResources = 0;
        int operations = 0;
        int slicesBorrowing = 0;
        int borrowedTotal = 0;
        Set<String> allFields = new HashSet<String>();
        Set<String> dangling = new TreeSet<String>();

        int entryCount = 0;
        for (String module : catalog.modules()) {
            for (ResourceEntry entry : catalog.entriesFor(module)) {
                entryCount++;
                operations += entry.operations.size();
                allFields.addAll(entry.fields);
                if (entry.parent != null) {
                    subResources++;
                }

                ObjectNode doc = slicer.build(module, entry.name);
                if (doc == null || doc.path("paths").size() == 0) {
                    continue;
                }
                sliced++;
                totalBytes += Json.compact(doc).length;

                Set<String> borrowed = new HashSet<String>();
                catalog.closure(doc.get("paths"), module, borrowed);
                if (!borrowed.isEmpty()) {
                    slicesBorrowing++;
                    borrowedTotal += borrowed.size();
                }

                // Every ref the finished document contains must be satisfied by the document
                // itself — that is the whole claim a slice makes.
                Set<String> refs = new HashSet<String>();
                Json.collectSchemaRefs(doc, refs);
                ObjectNode schemas = (ObjectNode) doc.path("components").path("schemas");
                for (String ref : refs) {
                    if (!schemas.has(ref)) {
                        dangling.add(module + "/" + entry.name + " -> " + ref);
                    }
                }
            }
        }

        System.out.println();
        System.out.println("=== self-check ===");
        System.out.println("  index:     " + catalog.modules().size() + " modules, " + entryCount
            + " resources/controllers (" + subResources + " sub-resources), " + operations
            + " operations, " + allFields.size() + " distinct field names — "
            + DocIndex.build(catalog).length / 1024 + " KB");
        System.out.println("  slices:    " + sliced + " documents, " + totalBytes / 1024
            + " KB total, " + (sliced == 0 ? 0 : totalBytes / sliced / 1024) + " KB average");
        System.out.println("  x-module:  " + slicesBorrowing + " slice(s) pulled " + borrowedTotal
            + " schema(s) from another module");
        System.out.println("  dangling:  " + dangling.size() + " unresolved $ref(s)");
        for (String problem : dangling) {
            System.out.println("               " + problem);
        }
        System.out.println();
        return dangling.isEmpty();
    }
}
