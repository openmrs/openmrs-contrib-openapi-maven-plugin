package org.openmrs.plugin.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the single OpenAPI document that the TypeScript client is generated from, covering the
 * module's {@code @Controller} endpoints only.
 *
 * <h2>Why the per-controller files are the input</h2>
 *
 * {@code controllers/} is already exactly the partition wanted — one file per controller, holding
 * that controller's paths and the schemas they reach. Slicing {@code openapi.json} instead would
 * need a selector to tell controller paths from resource paths, since that document holds the
 * resource half too. (The API class an operation lands on comes from its first tag, which
 * {@link ControllerDocumenter} writes into the spec, not from the file name.)
 *
 * Unlike the per-resource files (which mix {@code #/schemas/}, {@code ./Other.json#/...} and
 * {@code #/components/schemas/} and are not valid documents), the controller files use
 * {@code #/components/schemas/} throughout and carry their own {@code components} block. They are
 * still not quite self-contained: a controller whose DTO references a REST <em>resource</em> gets a
 * {@code $ref} to a schema that lives only in {@code openapi.json} (8 such refs across
 * webservices.rest and emrapi). {@link #backfillFromMainSpec} closes that gap transitively.
 *
 * <h2>Determinism</h2>
 *
 * Everything is accumulated into sorted maps and the controller files are read in name order, so
 * two runs over the same input produce a byte-identical document — the same invariant the spec
 * generator holds, and one the generated TypeScript inherits.
 */
class TypeScriptSpecAssembler {

    private static final Pattern SCHEMA_REF = Pattern.compile("^#/components/schemas/(.+)$");

    /** Name of the path variable standing in for a {@code **} version wildcard in a mapping. */
    private static final String VERSION_VARIABLE = "version";

    private final ObjectMapper mapper = new ObjectMapper();

    /** What the assembly found, for the mojo to report and to act on. */
    static class Result {
        ObjectNode document;
        /** API tag per controller file, in the order the classes will be generated. */
        final List<String> apiTags = new ArrayList<>();
        /** API tag mapped to the controller file(s) whose operations carry it first. */
        final Map<String, Set<String>> tagOwners = new TreeMap<>();
        /** operationId mapped to the {@code <controller>.<operationId>} sites claiming it. */
        final Map<String, List<String>> operationIdCollisions = new TreeMap<>();
        /** Schema names pulled in from openapi.json to satisfy a controller's $ref. */
        final List<String> backfilledSchemas = new ArrayList<>();
        /** $ref targets no loaded document defines. */
        final List<String> unresolvedRefs = new ArrayList<>();
        /** Paths that collided once {version} was replaced with a concrete segment. */
        final List<String> inlinedVersionCollisions = new ArrayList<>();
        /** API tags belonging to REST resources rather than controllers. */
        final Set<String> resourceTags = new TreeSet<>();
        /** How many of {@link #operationCount} came from resources. */
        int resourceOperationCount;
        int controllerCount;
        int operationCount;
        int schemaCount;
    }

    /**
     * @param controllersDir      the generator's {@code META-INF/openapi/controllers} directory
     * @param mainSpec            the module's {@code openapi.json}, used only to backfill $refs
     * @param inlineVersionSegment replace the {@code {version}} path variable with its only legal
     *                            value, so the generated client does not take a version argument
     */
    Result assemble(Path controllersDir, Path mainSpec, String title, String description,
            String version, boolean inlineVersionSegment) throws IOException {

        Result result = new Result();

        Map<String, ObjectNode> paths = new TreeMap<>();
        Map<String, ObjectNode> schemas = new TreeMap<>();
        Set<String> tags = new LinkedHashSet<>();

        for (File file : controllerFiles(controllersDir)) {
            String base = file.getName().substring(0, file.getName().length() - ".json".length());
            result.controllerCount++;

            ObjectNode doc = (ObjectNode) mapper.readTree(file);
            mergeSchemas(doc, schemas);
            result.operationCount += mergePaths(doc, base, paths, tags, result);
        }

        result.resourceOperationCount = mergeResourcePaths(mainSpec, paths, tags, result);
        result.operationCount += result.resourceOperationCount;

        if (inlineVersionSegment) {
            paths = inlineVersionSegment(paths, result);
        }

        backfillFromMainSpec(paths, schemas, mainSpec, result);

        result.schemaCount = schemas.size();
        result.document = buildDocument(title, description, version, tags, paths, schemas);
        return result;
    }

    /**
     * Merges the REST <em>resource</em> paths into the document, alongside the controller ones.
     * <p>
     * Taken from {@code openapi.json} and selected by tag, rather than read from
     * {@code resources/*.json}: those files mix three {@code $ref} forms and carry no
     * {@code components} block, so they are not valid documents — the same reason the dev server's
     * slicer works from the merged document. Every operation carries exactly one tag, and a
     * resource's ends in {@code Resource} where a controller's ends in {@code Controller}, so the
     * tag is the selector. (`ControllerDocumenter.apiTagFor` and
     * `OpenMRSResourceModelResolver.getResourceApiTag` guarantee that split.)
     * <p>
     * Only the paths are merged. Their schemas arrive through {@link #backfillFromMainSpec}, which
     * already follows {@code $ref}s transitively out of the same document — so a resource's
     * {@code Get_default} / {@code Get_full} / {@code Get_ref} / {@code Get_custom} and everything
     * they reference come in without a second traversal.
     *
     * @return how many resource operations were merged
     */
    private int mergeResourcePaths(Path mainSpec, Map<String, ObjectNode> into, Set<String> tags,
            Result result) throws IOException {
        if (mainSpec == null || !Files.isRegularFile(mainSpec)) {
            return 0;
        }
        JsonNode main = mapper.readTree(mainSpec.toFile());
        int merged = 0;
        // A TreeMap of the paths first, so the merge order does not depend on the document's.
        Map<String, JsonNode> sorted = new TreeMap<>();
        main.path("paths").fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));

        for (Map.Entry<String, JsonNode> pathEntry : sorted.entrySet()) {
            ObjectNode kept = mapper.createObjectNode();
            for (java.util.Iterator<Map.Entry<String, JsonNode>> ops =
                    pathEntry.getValue().fields(); ops.hasNext(); ) {
                Map.Entry<String, JsonNode> op = ops.next();
                if (!op.getValue().isObject()) {
                    continue;
                }
                String tag = firstTag((ObjectNode) op.getValue());
                if (tag == null || !tag.endsWith("Resource")) {
                    continue;
                }
                kept.set(op.getKey(), op.getValue());
                tags.add(tag);
                result.resourceTags.add(tag);
                merged++;
            }
            if (kept.size() > 0) {
                // A controller and a resource cannot claim the same path — the REST servlet
                // dispatches one or the other — so there is nothing to reconcile here.
                into.put(pathEntry.getKey(), kept);
            }
        }
        return merged;
    }

    /** The controller files, in name order so the run is reproducible. */
    private static File[] controllerFiles(Path controllersDir) {
        File[] files = controllersDir.toFile().listFiles(
                (dir, name) -> name.endsWith(".json") && new File(dir, name).isFile());
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return files;
    }

    /** The tag that decides which API class the operation lands on, or null if it carries none. */
    private static String firstTag(ObjectNode operation) {
        JsonNode tags = operation.path("tags");
        return tags.isArray() && tags.size() > 0 ? tags.get(0).asText() : null;
    }

    private void mergeSchemas(ObjectNode doc, Map<String, ObjectNode> into) {
        JsonNode schemas = doc.path("components").path("schemas");
        schemas.fields().forEachRemaining(e -> {
            ObjectNode existing = into.get(e.getKey());
            // Verified across the four modules under test: no controller file disagrees with
            // another about a shared schema name — they come from the same bean introspection.
            // Keeping the first is a documented choice rather than an accident, and a genuine
            // disagreement would show up as a type mismatch at TypeScript compile time.
            if (existing == null) {
                into.put(e.getKey(), (ObjectNode) e.getValue());
            }
        });
    }

    /**
     * Copies one controller file's paths into the merged document, noting the API class each
     * operation belongs to and recording operationId claims.
     * <p>
     * Tags are passed through <b>untouched</b>. {@link ControllerDocumenter} already emits the
     * controller's own tag first, followed by any others, and the generator is run with
     * {@code KEEP_ONLY_FIRST_TAG_IN_OPERATION} so only that first one decides the class. Rewriting
     * the array here — as this once did — would throw away every other tag on the way to the
     * client, which is the whole thing that arrangement exists to avoid.
     *
     * @return the number of operations merged
     */
    private int mergePaths(ObjectNode doc, String controllerName,
            Map<String, ObjectNode> into, Set<String> tags, Result result) {
        int count = 0;
        JsonNode paths = doc.path("paths");
        for (Map.Entry<String, JsonNode> pathEntry : iterable(paths.fields())) {
            ObjectNode pathItem = (ObjectNode) pathEntry.getValue();
            for (Map.Entry<String, JsonNode> opEntry : iterable(pathItem.fields())) {
                if (!opEntry.getValue().isObject()) {
                    continue;
                }
                ObjectNode op = (ObjectNode) opEntry.getValue();

                String apiTag = firstTag(op);
                if (apiTag != null) {
                    tags.add(apiTag);
                    // Two controllers whose first tag agrees would merge into one API class, and
                    // their operationIds would then have to be unique within it.
                    result.tagOwners.computeIfAbsent(apiTag, k -> new java.util.TreeSet<>())
                            .add(controllerName);
                    if (!result.apiTags.contains(apiTag)) {
                        result.apiTags.add(apiTag);
                    }
                }

                String operationId = op.path("operationId").asText(null);
                if (operationId != null) {
                    result.operationIdCollisions
                            .computeIfAbsent(operationId, k -> new ArrayList<>())
                            .add(controllerName + "." + operationId);
                }
                count++;
            }
            ObjectNode existing = into.get(pathEntry.getKey());
            if (existing == null) {
                into.put(pathEntry.getKey(), pathItem);
            } else {
                // Two controllers mapping the same path: merge verb by verb rather than letting
                // one file's path item replace the other's wholesale.
                pathItem.fields().forEachRemaining(e -> existing.set(e.getKey(), e.getValue()));
            }
        }
        return count;
    }

    /**
     * Replaces the {@code {version}} path variable with the only value that reaches these
     * controllers, and drops the parameter that declared it.
     * <p>
     * The variable exists because a controller can map the version segment as Spring's {@code **},
     * and an OpenAPI path template variable must be declared or the document is invalid. But the
     * spec itself already says the value is {@code v1} and nothing else ({@code enum: ["v1"]}), so
     * carrying it through to the client only adds a mandatory {@code version: 'v1'} argument to
     * every call. The concrete value is read from the parameter rather than hardcoded, so this
     * follows the spec if the enum ever gains a second entry — in which case the parameter is
     * genuinely meaningful and is left alone.
     */
    private Map<String, ObjectNode> inlineVersionSegment(Map<String, ObjectNode> paths, Result result) {
        Map<String, ObjectNode> rewritten = new TreeMap<>();
        String token = "{" + VERSION_VARIABLE + "}";

        for (Map.Entry<String, ObjectNode> entry : paths.entrySet()) {
            String path = entry.getKey();
            ObjectNode pathItem = entry.getValue();

            if (!path.contains(token)) {
                rewritten.put(path, pathItem);
                continue;
            }

            String concrete = soleVersionValue(pathItem);
            if (concrete == null) {
                rewritten.put(path, pathItem);
                continue;
            }

            for (Map.Entry<String, JsonNode> opEntry : iterable(pathItem.fields())) {
                if (opEntry.getValue().isObject()) {
                    removeVersionParameter((ObjectNode) opEntry.getValue());
                }
            }

            String newPath = path.replace(token, concrete);
            if (rewritten.containsKey(newPath) || paths.containsKey(newPath)) {
                result.inlinedVersionCollisions.add(path + " -> " + newPath);
            }
            rewritten.put(newPath, pathItem);
        }
        return rewritten;
    }

    /**
     * The single legal value of the {@code version} path parameter, or null if the parameter is
     * absent, or offers a real choice, or the operations on this path disagree about it.
     */
    private String soleVersionValue(ObjectNode pathItem) {
        String agreed = null;
        for (Map.Entry<String, JsonNode> opEntry : iterable(pathItem.fields())) {
            if (!opEntry.getValue().isObject()) {
                continue;
            }
            JsonNode params = opEntry.getValue().path("parameters");
            for (JsonNode param : params) {
                if (!VERSION_VARIABLE.equals(param.path("name").asText())
                        || !"path".equals(param.path("in").asText())) {
                    continue;
                }
                JsonNode enumValues = param.path("schema").path("enum");
                if (!enumValues.isArray() || enumValues.size() != 1) {
                    return null;
                }
                String value = enumValues.get(0).asText();
                if (agreed != null && !agreed.equals(value)) {
                    return null;
                }
                agreed = value;
            }
        }
        return agreed;
    }

    private void removeVersionParameter(ObjectNode op) {
        JsonNode params = op.path("parameters");
        if (!params.isArray()) {
            return;
        }
        ArrayNode kept = mapper.createArrayNode();
        for (JsonNode param : params) {
            boolean isVersionPathParam = VERSION_VARIABLE.equals(param.path("name").asText())
                    && "path".equals(param.path("in").asText());
            if (!isVersionPathParam) {
                kept.add(param);
            }
        }
        if (kept.isEmpty()) {
            op.remove("parameters");
        } else {
            op.set("parameters", kept);
        }
    }

    /**
     * Pulls in every schema the controller documents reference but do not define, following refs
     * transitively.
     * <p>
     * A controller DTO that holds a REST resource — {@code VisitConfigurationController2_0}'s
     * {@code VisitTypeGet_ref}, {@code DiagnosisController}'s {@code ConceptGet_ref} and others —
     * gets a {@code $ref} into a schema the resource half of the spec owns. Most resolve inside the
     * module's own {@code openapi.json}; a <b>cross-module</b> one cannot, because it names a
     * resource a dependency documents and this goal reads one module's files.
     * <p>
     * Such a name is <b>stubbed as a free-form object and reported</b>, not invented and not left
     * dangling. Leaving it dangling is not an option: openapi-generator's spec validator rejects an
     * unresolved {@code $ref} outright and fails the build, so the whole package is lost over a
     * field or two. The stub keeps every other operation and DTO fully typed, degrades exactly the
     * fields whose shape this build genuinely cannot see, and carries
     * {@code x-openmrs-unresolved-ref} so the reason is greppable in the assembled document rather
     * than only in the build log.
     * <p>
     * Resolving these for real needs the cross-module catalog the dev server builds
     * ({@code SpecCatalog}), plus a schema-name-to-npm-package mapping and an {@code importMapping}
     * so the generated client imports the type from the owning module's package instead of
     * restating it. That couples the two packages' release cycles, so it is a deliberate follow-up
     * rather than something to slip in here.
     */
    private void backfillFromMainSpec(Map<String, ObjectNode> paths, Map<String, ObjectNode> schemas,
            Path mainSpec, Result result) throws IOException {

        Map<String, JsonNode> available = new LinkedHashMap<>();
        if (mainSpec != null && Files.isRegularFile(mainSpec)) {
            JsonNode main = mapper.readTree(mainSpec.toFile());
            main.path("components").path("schemas").fields()
                    .forEachRemaining(e -> available.put(e.getKey(), e.getValue()));
        }

        Set<String> unresolved = new TreeSet<>();
        // Each pass may pull in schemas that reference further schemas, so sweep until it settles.
        for (boolean changed = true; changed; ) {
            changed = false;
            Set<String> referenced = new TreeSet<>();
            for (ObjectNode pathItem : paths.values()) {
                collectSchemaRefs(pathItem, referenced);
            }
            for (ObjectNode schema : new ArrayList<>(schemas.values())) {
                collectSchemaRefs(schema, referenced);
            }
            for (String name : referenced) {
                if (schemas.containsKey(name) || unresolved.contains(name)) {
                    continue;
                }
                JsonNode fromMain = available.get(name);
                if (fromMain == null) {
                    unresolved.add(name);
                    continue;
                }
                schemas.put(name, (ObjectNode) fromMain.deepCopy());
                result.backfilledSchemas.add(name);
                changed = true;
            }
        }

        java.util.Collections.sort(result.backfilledSchemas);
        result.unresolvedRefs.addAll(unresolved);

        for (String name : unresolved) {
            schemas.put(name, unresolvedSchemaStub(name));
        }
    }

    /**
     * Stands in for a schema another module defines. A free-form object, so a consumer sees an
     * untyped value at exactly the fields whose shape is unavailable and a fully typed one
     * everywhere else.
     *
     * @see #backfillFromMainSpec
     */
    private ObjectNode unresolvedSchemaStub(String name) {
        ObjectNode stub = mapper.createObjectNode();
        stub.put("type", "object");
        stub.put("title", name);
        stub.put("additionalProperties", true);
        stub.put("description", name + " is defined by another OpenMRS module, so its shape is not"
                + " available to this module's build. Treated as a free-form object.");
        stub.put("x-openmrs-unresolved-ref", name);
        return stub;
    }

    private static void collectSchemaRefs(JsonNode node, Set<String> into) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                Matcher m = SCHEMA_REF.matcher(ref.asText());
                if (m.matches()) {
                    into.add(m.group(1));
                }
            }
            node.fields().forEachRemaining(e -> collectSchemaRefs(e.getValue(), into));
        } else if (node.isArray()) {
            node.forEach(child -> collectSchemaRefs(child, into));
        }
    }

    private ObjectNode buildDocument(String title, String description, String version,
            Set<String> tags, Map<String, ObjectNode> paths, Map<String, ObjectNode> schemas) {

        ObjectNode doc = mapper.createObjectNode();
        doc.put("openapi", "3.1.0");

        ObjectNode info = doc.putObject("info");
        info.put("title", title);
        if (description != null) {
            info.put("description", description);
        }
        info.put("version", version);

        ArrayNode tagArray = doc.putArray("tags");
        for (String tag : new TreeSet<>(tags)) {
            tagArray.addObject().put("name", tag);
        }

        ObjectNode pathsNode = doc.putObject("paths");
        paths.forEach(pathsNode::set);

        ObjectNode schemasNode = doc.putObject("components").putObject("schemas");
        schemas.forEach(schemasNode::set);

        return doc;
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> it) {
        List<T> copy = new ArrayList<>();
        it.forEachRemaining(copy::add);
        return copy;
    }
}
