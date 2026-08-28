package org.openmrs.openapi.devserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every loaded module's generated OpenAPI output, held in memory.
 * <p>
 * Built once at startup: parsing N {@code openapi.json} files is cheap, and the navigation index
 * needs all of them anyway. Slicing is not done here — see {@link SpecSlicer}, which is lazy.
 * <p>
 * <b>Why one catalog.</b> A dependent module's spec legitimately {@code $ref}s the REST module's
 * schemas without carrying them: {@code queue}'s {@code openapi.json} has 15 such refs and
 * {@code emrapi}'s has 10 ({@code ConceptGet_ref}, {@code PatientGet_ref}, {@code RoleGet_ref},
 * …), all defined in {@code webservices.rest}. So no single module's output is enough to render
 * one of its own resources, and both the whole-module view and the per-resource slice have to
 * resolve names the same way. {@link #lookup} is that one rule.
 */
final class SpecCatalog {

    /** Where generate.sh leaves output, newest layout first. */
    private static final String[] OUTPUT_DIRS = {
        "omod/target/classes/META-INF/openapi", "target/classes/META-INF/openapi",
        "omod/target/openapi", "target/openapi"
    };

    private static final List<String> VERBS =
        Arrays.asList("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /** module name -> its generated output directory */
    private final Map<String, File> directories = new LinkedHashMap<String, File>();
    /** module name -> its openapi.json, as generated */
    private final Map<String, ObjectNode> merged = new LinkedHashMap<String, ObjectNode>();
    /** module name -> components/schemas of the above */
    private final Map<String, ObjectNode> schemas = new LinkedHashMap<String, ObjectNode>();
    /** module name -> its resources and controllers, in the order they will be indexed */
    private final Map<String, List<ResourceEntry>> entries =
        new LinkedHashMap<String, List<ResourceEntry>>();

    private final List<String> warnings = new ArrayList<String>();

    /**
     * Loads each given module root. A path with no generated output is reported and skipped
     * rather than being fatal, so one un-generated module does not stop the server.
     */
    static SpecCatalog load(List<String> modulePaths) throws IOException {
        SpecCatalog catalog = new SpecCatalog();
        for (String path : modulePaths) {
            File moduleDir = new File(path).getCanonicalFile();
            File openApiDir = null;
            for (String candidate : OUTPUT_DIRS) {
                File dir = new File(moduleDir, candidate);
                if (new File(dir, "openapi.json").isFile()) {
                    openApiDir = dir;
                    break;
                }
            }
            if (openApiDir == null) {
                catalog.warnings.add("no generated openapi.json for " + moduleDir.getName()
                    + " — run ./generate.sh " + path);
                continue;
            }
            catalog.add(shortName(moduleDir.getName()), openApiDir);
        }
        catalog.resolveParents();
        return catalog;
    }

    /** "openmrs-module-queue" is how the directory is named; "queue" is what anyone calls it. */
    private static String shortName(String directoryName) {
        return directoryName.startsWith("openmrs-module-")
            ? directoryName.substring("openmrs-module-".length()) : directoryName;
    }

    private void add(String module, File openApiDir) throws IOException {
        ObjectNode doc = (ObjectNode) Json.MAPPER.readTree(new File(openApiDir, "openapi.json"));
        directories.put(module, openApiDir);
        this.merged.put(module, doc);
        JsonNode componentSchemas = doc.path("components").path("schemas");
        this.schemas.put(module, componentSchemas.isObject()
            ? (ObjectNode) componentSchemas : Json.obj());
        this.entries.put(module, readEntries(module, openApiDir));
    }

    /**
     * Reads the per-resource and per-controller files for their <i>manifest</i> only — which route
     * keys and which property names belong to this resource.
     * <p>
     * The path objects and schemas themselves are taken from the merged {@code openapi.json}
     * instead (see {@link SpecSlicer}), because the per-resource files are not self-contained
     * documents: they mix {@code #/schemas/} for their own schemas, {@code ./Other.json#/schemas/}
     * across files, and {@code #/components/schemas/} in their {@code paths} half — with no
     * {@code components} block to satisfy that last form. Serving them directly would dangle every
     * path ref in all 97 files.
     */
    private List<ResourceEntry> readEntries(String module, File openApiDir) throws IOException {
        List<ResourceEntry> found = new ArrayList<ResourceEntry>();
        String[][] kinds = { { "resource", "resources" }, { "controller", "controllers" } };
        for (String[] kind : kinds) {
            File folder = new File(openApiDir, kind[1]);
            File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) {
                continue;
            }
            Arrays.sort(files);
            for (File file : files) {
                String name = file.getName().substring(0, file.getName().length() - ".json".length());
                JsonNode doc = Json.MAPPER.readTree(file);

                List<ResourceEntry.Operation> operations = new ArrayList<ResourceEntry.Operation>();
                JsonNode paths = doc.path("paths");
                if (paths.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> routes = paths.fields();
                    while (routes.hasNext()) {
                        Map.Entry<String, JsonNode> route = routes.next();
                        Iterator<Map.Entry<String, JsonNode>> ops = route.getValue().fields();
                        while (ops.hasNext()) {
                            Map.Entry<String, JsonNode> op = ops.next();
                            if (VERBS.contains(op.getKey())) {
                                operations.add(new ResourceEntry.Operation(route.getKey(),
                                    op.getKey(), op.getValue().path("summary").asText("")));
                            }
                        }
                    }
                }

                ResourceEntry entry = new ResourceEntry(module, kind[0], name, operations);

                JsonNode own = doc.has("schemas") ? doc.get("schemas")
                    : doc.path("components").path("schemas");
                if (own.isObject()) {
                    // TreeMap so the field list is stable regardless of document order.
                    Set<String> sorted = new java.util.TreeSet<String>();
                    own.forEach(schema -> {
                        JsonNode properties = schema.path("properties");
                        if (properties.isObject()) {
                            properties.fieldNames().forEachRemaining(sorted::add);
                        }
                    });
                    entry.fields.addAll(sorted);
                }

                entry.parent = parentSegment(operations);
                found.add(entry);
            }
        }
        return found;
    }

    /**
     * MainSubResourceController serves every sub-resource as
     * {@code /{resource}/{parentUuid}/{subResource}}, so the segment before {@code {parentUuid}}
     * names the parent resource and its absence means this is an ordinary resource.
     */
    private static String parentSegment(List<ResourceEntry.Operation> operations) {
        for (ResourceEntry.Operation op : operations) {
            String[] segments = op.path.split("/");
            for (int i = 1; i < segments.length; i++) {
                if ("{parentUuid}".equals(segments[i]) && !segments[i - 1].isEmpty()) {
                    return segments[i - 1];
                }
            }
        }
        return null;
    }

    /**
     * Rewrites each sub-resource's raw parent segment ("queue") to the parent entry's name
     * ("Queue").
     * <p>
     * Lower-casing is not the rule and neither is capitalising: {@code FormResourceResource1_9} is
     * the sub-resource {@code form/resource}, written as {@code FormResource} and hanging off
     * {@code Form}. So match on routes — the parent is the entry that serves that segment as its
     * own collection route. The owning module wins; other modules are a fallback, because a module
     * may hang a sub-resource off a resource that core owns.
     */
    private void resolveParents() {
        Map<String, Map<String, String>> byRoute = new HashMap<String, Map<String, String>>();
        for (Map.Entry<String, List<ResourceEntry>> module : entries.entrySet()) {
            Map<String, String> routes = new HashMap<String, String>();
            for (ResourceEntry entry : module.getValue()) {
                for (ResourceEntry.Operation op : entry.operations) {
                    String segment = collectionSegment(op.path);
                    if (segment != null) {
                        routes.putIfAbsent(segment, entry.name);
                    }
                }
            }
            byRoute.put(module.getKey(), routes);
        }

        int unresolved = 0;
        for (Map.Entry<String, List<ResourceEntry>> module : entries.entrySet()) {
            for (ResourceEntry entry : module.getValue()) {
                if (entry.parent == null) {
                    continue;
                }
                String owner = module.getKey();
                String resolved = byRoute.get(owner).get(entry.parent);
                if (resolved == null) {
                    for (String other : new TreeMap<String, Map<String, String>>(byRoute).keySet()) {
                        if (!other.equals(owner) && byRoute.get(other).containsKey(entry.parent)) {
                            resolved = byRoute.get(other).get(entry.parent);
                            break;
                        }
                    }
                }
                if (resolved != null && !resolved.equals(entry.name)) {
                    entry.parent = resolved;
                } else {
                    // Keep the raw segment rather than dropping the flag — it is still a
                    // sub-resource, and the tree can show the segment as the parent name.
                    unresolved++;
                }
            }
        }
        if (unresolved > 0) {
            warnings.add(unresolved + " sub-resource(s) kept a raw parent segment "
                + "(no resource serves that route)");
        }
    }

    /**
     * The trailing segment of a resource's own collection route, e.g. "queue" from
     * "/ws/rest/v1/queue" — or null if this is not one. Version-agnostic: anything under
     * /ws/rest/&lt;version&gt; with exactly one further, non-templated segment counts, so a v2
     * namespace would not need a code change.
     */
    private static String collectionSegment(String path) {
        String[] segments = path.split("/");
        // ["", "ws", "rest", "v1", "queue"]
        if (segments.length != 5 || !"ws".equals(segments[1]) || !"rest".equals(segments[2])) {
            return null;
        }
        return segments[4].indexOf('{') < 0 ? segments[4] : null;
    }

    /**
     * Finds a schema by name, preferring the module that is asking.
     * <p>
     * Fallback order is the modules' names sorted, not load order, so the answer does not depend
     * on the order the modules happened to be passed on the command line. Returns null when no
     * loaded module defines it — leaving the ref dangling rather than inventing a definition,
     * which keeps a genuinely missing schema visible instead of silently wrong.
     */
    JsonNode lookup(String schemaName, String owner) {
        ObjectNode own = schemas.get(owner);
        if (own != null && own.has(schemaName)) {
            return own.get(schemaName);
        }
        for (String other : new TreeMap<String, ObjectNode>(schemas).keySet()) {
            if (!other.equals(owner) && schemas.get(other).has(schemaName)) {
                return schemas.get(other).get(schemaName);
            }
        }
        return null;
    }

    /** The transitive schema closure of {@code root}, resolved across every loaded module. */
    ObjectNode closure(JsonNode root, String owner) {
        return closure(root, owner, null);
    }

    /**
     * As {@link #closure(JsonNode, String)}, additionally recording into {@code borrowedOut} the
     * names that had to come from a module other than {@code owner}. That count is the check that
     * cross-module resolution is actually happening rather than quietly resolving to nothing.
     */
    ObjectNode closure(JsonNode root, String owner, Set<String> borrowedOut) {
        ObjectNode ownSchemas = schemas.get(owner);
        Set<String> frontier = new HashSet<String>();
        Json.collectSchemaRefs(root, frontier);
        Map<String, JsonNode> resolved = new TreeMap<String, JsonNode>();
        while (!frontier.isEmpty()) {
            Iterator<String> it = frontier.iterator();
            String name = it.next();
            it.remove();
            if (resolved.containsKey(name)) {
                continue;
            }
            JsonNode schema = lookup(name, owner);
            if (schema == null) {
                continue;
            }
            resolved.put(name, schema);
            if (borrowedOut != null && (ownSchemas == null || !ownSchemas.has(name))) {
                borrowedOut.add(name);
            }
            Json.collectSchemaRefs(schema, frontier);
        }
        ObjectNode out = Json.obj();
        for (Map.Entry<String, JsonNode> entry : resolved.entrySet()) {
            out.set(entry.getKey(), entry.getValue());
        }
        return out;
    }

    Set<String> modules() {
        return Collections.unmodifiableSet(merged.keySet());
    }

    List<ResourceEntry> entriesFor(String module) {
        List<ResourceEntry> found = entries.get(module);
        return found == null ? Collections.<ResourceEntry>emptyList() : found;
    }

    ResourceEntry entry(String module, String name) {
        for (ResourceEntry entry : entriesFor(module)) {
            if (entry.name.equals(name)) {
                return entry;
            }
        }
        return null;
    }

    ObjectNode mergedSpec(String module) {
        return merged.get(module);
    }

    File directory(String module) {
        return directories.get(module);
    }

    List<String> warnings() {
        return warnings;
    }

    /**
     * A whole module's spec with every schema it references pulled in, using the same
     * {@link #lookup} rule as the slices. Kept for the "show me the whole module" view and for
     * anything downstream that wants the full document.
     */
    ObjectNode enrichedSpec(String module) {
        ObjectNode doc = merged.get(module).deepCopy();
        ObjectNode components = doc.has("components") && doc.get("components").isObject()
            ? (ObjectNode) doc.get("components") : Json.obj();
        ObjectNode own = components.has("schemas") && components.get("schemas").isObject()
            ? (ObjectNode) components.get("schemas") : Json.obj();
        ObjectNode complete = closure(doc, module);
        // The module's own definitions win over anything the closure borrowed under the same name.
        complete.setAll(own);
        components.set("schemas", complete);
        doc.set("components", components);
        return doc;
    }

    /** All modules in one document, for the "All" view. First definition of a name wins. */
    ObjectNode mergedAcrossModules() {
        ObjectNode paths = Json.obj();
        ObjectNode allSchemas = Json.obj();
        ArrayNode tags = Json.arr();
        Set<String> tagNames = new HashSet<String>();

        for (String module : merged.keySet()) {
            ObjectNode spec = enrichedSpec(module);
            spec.path("tags").forEach(tag -> {
                if (tagNames.add(tag.path("name").asText())) {
                    tags.add(tag);
                }
            });
            spec.path("paths").fields().forEachRemaining(entry -> {
                if (!paths.has(entry.getKey())) {
                    paths.set(entry.getKey(), entry.getValue());
                }
            });
            spec.path("components").path("schemas").fields().forEachRemaining(entry -> {
                if (!allSchemas.has(entry.getKey())) {
                    allSchemas.set(entry.getKey(), entry.getValue());
                }
            });
        }

        ObjectNode doc = Json.obj();
        doc.put("openapi", "3.1.0");
        doc.set("info", Json.obj()
            .put("title", "OpenMRS REST API — all modules")
            .put("version", "combined"));
        if (tags.size() > 0) {
            doc.set("tags", tags);
        }
        doc.set("paths", paths);
        doc.set("components", Json.obj().set("schemas", allSchemas));
        return doc;
    }
}
