package org.openmrs.plugin.openapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.openmrs.module.webservices.rest.web.resource.api.Deletable;
import org.openmrs.module.webservices.rest.web.resource.api.Listable;
import org.openmrs.module.webservices.rest.web.resource.api.Purgeable;
import org.openmrs.module.webservices.rest.web.resource.api.Retrievable;
import org.openmrs.module.webservices.rest.web.resource.api.Searchable;
import org.openmrs.module.webservices.rest.web.resource.api.SubResource;
import org.openmrs.module.webservices.rest.web.resource.api.Updatable;
import org.openmrs.module.webservices.rest.web.resource.api.Uploadable;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingSubclassHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * OpenAPI 3.1 specification generator for OpenMRS REST resources.
 * Uses OpenMRSResourceModelResolver for accurate property type discovery and Swagger-Core models.
 */
public class OpenApiSpecGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSpecGenerator.class);

    private java.util.Set<String> ownedLocations;

    private String moduleName;
    private String moduleVersion;

    private List<DelegatingResourceHandler<?>> handlers;
    private List<Class<?>> controllerClasses;

    /** Parent resource handler -> the subclass handlers bound to it. Usually empty. */
    private java.util.Map<DelegatingResourceHandler<?>, List<DelegatingSubclassHandler<?, ?>>> subclassHandlers =
            java.util.Collections.emptyMap();

    /**
     * Discovers the module's resource handlers and controllers by reflection.
     * <p>
     * No Spring context, no in-memory database and no authenticated session are involved: the
     * handlers' description methods are almost entirely self-contained, and {@link StubRuntime}
     * covers the few that reach for the platform. See plan-no-runtime.md for the measurements
     * behind this.
     */
    public void setup(String ownedLocationsSemicolon, String moduleName, String moduleVersion)
            throws Exception {
        this.moduleName = moduleName;
        this.moduleVersion = moduleVersion;
        this.ownedLocations = new java.util.HashSet<String>(
                java.util.Arrays.asList(ownedLocationsSemicolon.split(";")));
        log.info("Owned locations: {}", this.ownedLocations);
        log.info("=== Setting up OpenAPI Spec Generator (reflection only) ===");

        // Must happen before any resource class is loaded, because loading one initialises
        // RestConstants, whose static initialiser calls Context.getAdministrationService().
        StubRuntime.install();

        HandlerScanner scanner = new HandlerScanner(Thread.currentThread().getContextClassLoader(), ownedLocations);
        handlers = scanner.findResourceHandlers();
        Collections.sort(handlers, Comparator.comparing(h -> h.getClass().getSimpleName()));

        // Subclass handlers resolve sibling resources through Context.getService(RestService.class);
        // answer those from what we just discovered.
        StubRuntime.registerRestService(handlers);

        // Subclass handlers are documented as subtypes of the resource they extend, not as
        // resources in their own right — see OpenMRSResourceModelResolver.resolveSchemasWithSubtypes.
        subclassHandlers = scanner.findSubclassHandlersByResource(handlers);

        // Cross-resource $ref targets name the resource that supports a class, not the class, so
        // this has to be known before any schema is resolved.
        OpenMRSResourceModelResolver.registerResourceNames(handlers, subclassHandlers);

        controllerClasses = scanner.findControllers();
    }

    public void generateOpenAPISpec(String outputDir, String outputFile) {

        // The spec describes one module, so it is titled and versioned by that module rather than
        // by openmrs-core. The core version still matters for interpreting the schemas (resources
        // are version-gated via supportedOpenmrsVersions), so it goes in the description.
        // new OpenAPI(SpecVersion.V31) sets the serialisation behaviour but leaves the version
        // string at its "3.0.1" default, so the document has to declare 3.1.0 itself.
        OpenAPI openAPI = new OpenAPI(SpecVersion.V31)
            .openapi("3.1.0")
            .info(new Info()
                .title(specTitle())
                .version(moduleVersion != null && !moduleVersion.trim().isEmpty()
                        ? moduleVersion : "unknown")
                .description(specDescription()));

        ModelConverters converters = ModelConverters.getInstance(true);

        // Deliberately NOT .openapi31(true): ModelConverters(true) applies that to its own default
        // resolver, but turning it on here made things worse, not better (324 typeless schemas
        // instead of 43). Schemas.normalize() below repairs the type/types split for the whole
        // document instead, which does not depend on swagger's internal spec-version plumbing.
        converters.addConverter(new OpenMRSResourceModelResolver(Json31.mapper()));

        Components components = new Components();

        // directory for individual schema files
        Path schemaDir = Paths.get(outputDir, "resources");
        try {
            Files.createDirectories(schemaDir);
        } catch (Exception e) {
            throw new RuntimeException("Unable to create schema output directory: " + schemaDir, e);
        }

        // A run writes one file per documented resource but never removed the files of resources it
        // no longer documents, so a handler that stopped qualifying (a subclass handler failing the
        // supportedOpenmrsVersions check, a renamed resource) left its file behind — and because
        // this directory is the module's compiled-resources tree, `package` shipped the stale file
        // inside the omod JAR. It also made the reported count disagree with the files on disk.
        clearGeneratedJson(schemaDir);
        clearGeneratedJson(Paths.get(outputDir, "controllers"));

        // Json31, not Json: the spec is built as SpecVersion.V31 and the 3.0 writer silently drops
        // 3.1-only constructs (const, type arrays, examples) and stamps "openapi": "3.0.1".
        com.fasterxml.jackson.databind.ObjectMapper swaggerMapper = io.swagger.v3.core.util.Json31.mapper();

        // Two owned handlers can resolve to the same resource name, in which case the second
        // overwrites the first file. Track them so the count reported to the user reconciles
        // with the number of files on disk.
        java.util.Map<String, String> writtenBy = new java.util.LinkedHashMap<String, String>();
        // The tag is not the file name: resources/ is named after the handler class, while the
        // tag drops the version marker. See OpenMRSResourceModelResolver.getResourceApiTag.
        java.util.SortedSet<String> resourceTags = new java.util.TreeSet<String>();

        for (DelegatingResourceHandler<?> handler : handlers) {
            String resourceName = OpenMRSResourceModelResolver.getResourceName(handler);

            // Always resolve schemas — the ModelConverterContext needs every handler processed so
            // that cross-module $ref targets (e.g. Patient, Visit) can be named correctly.
            ResolvedSchema resolvedSchema = converters.resolveAsResolvedSchema(
                new OpenmrsResourceAnnotatedType(handler, subclassHandlers.get(handler), null));
            noteFreeFormSchemas(resolvedSchema);

            // Only write files and openapi.json entries for resources defined in this module.
            // Handlers from dependency JARs (REST core, openmrs-api, etc.) are skipped.
            if (!isModuleOwnedHandler(handler)) {
                continue;
            }

            String previousOwner = writtenBy.put(resourceName, handler.getClass().getName());
            if (previousOwner != null) {
                System.out.println("WARN  resource name '" + resourceName + "' claimed by both "
                        + previousOwner + " and " + handler.getClass().getName()
                        + "; both files are written, but they share schema names and the later"
                        + " one wins in openapi.json");
            }
            resourceTags.add(OpenMRSResourceModelResolver.getResourceApiTag(handler));
            log.info("generating " + resourceName);

            // Collect schemas for this resource
            java.util.Map<String, Schema> selected = reachableSchemas(resourceName, resolvedSchema);
            Components resourceComponents = new Components();
            for (java.util.Map.Entry<String, Schema> entry : selected.entrySet()) {
                resourceComponents.addSchemas(entry.getKey(), entry.getValue());
            }

            // Build paths for this resource
            io.swagger.v3.oas.models.Paths resourcePaths = new io.swagger.v3.oas.models.Paths();
            addPathsForHandler(handler, resourceName, resourcePaths, subtypeNames(handler));

            // Write per-resource file: { "schemas": {...}, "paths": {...} }
            Schemas.normalizeAll(resourceComponents.getSchemas());
            Schemas.titleAll(resourceComponents.getSchemas());
            com.fasterxml.jackson.databind.node.ObjectNode root = swaggerMapper.createObjectNode();
            com.fasterxml.jackson.databind.JsonNode componentsJson = swaggerMapper.valueToTree(resourceComponents);
            if (componentsJson.has("schemas")) {
                root.set("schemas", componentsJson.get("schemas"));
            }
            if (!resourcePaths.isEmpty()) {
                root.set("paths", swaggerMapper.valueToTree(resourcePaths));
            }
            // The handler's class name, not the resource name: the file says which Java class
            // produced these schemas. The schema names inside it stay resourceName-based — they
            // describe the domain object, not the class. See getResourceFileName.
            Path outFile = schemaDir.resolve(
                OpenMRSResourceModelResolver.getResourceFileName(handler) + ".json");
            try {
                Files.write(outFile, swaggerMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(root));
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Wrote schema file: " + outFile.toAbsolutePath());

            // Add all schemas for this resource directly into components/schemas so that
            // Renderers can resolve named $refs (QueueGet, QueueCreate, etc.) without
            // needing to follow external file refs.
            for (java.util.Map.Entry<String, Schema> entry : selected.entrySet()) {
                components.addSchemas(entry.getKey(), entry.getValue());
            }

            // Add resource paths to the main openapi.json so they appear in the rendered docs.
            // (Controller paths are added separately via ControllerDocumenter below.)
            if (!resourcePaths.isEmpty()) {
                if (openAPI.getPaths() == null) {
                    openAPI.paths(new io.swagger.v3.oas.models.Paths());
                }
                openAPI.getPaths().putAll(resourcePaths);
            }
        }

        openAPI.components(components);

        // Document concrete @Controller beans (all except MainResourceController / MainSubResourceController)
        java.util.SortedSet<String> controllerTags = new java.util.TreeSet<>();
        if (controllerClasses != null && !controllerClasses.isEmpty()) {
            try {
                Path controllersDir = Paths.get(outputDir, "controllers");
                Files.createDirectories(controllersDir);
                ControllerDocumenter controllerDocumenter = new ControllerDocumenter();
                io.swagger.v3.oas.models.Paths controllerPaths =
                    controllerDocumenter.document(controllerClasses, controllersDir, components,
                        ownedLocations != null ? String.join(";", ownedLocations) : "");
                controllerTags.addAll(controllerDocumenter.getDocumentedApiTags());
                if (!controllerPaths.isEmpty()) {
                    if (openAPI.getPaths() == null) {
                        openAPI.paths(controllerPaths);
                    } else {
                        openAPI.getPaths().putAll(controllerPaths);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to document controllers: {}", e.getMessage(), e);
            }
        }

        // One tag per resource and per controller — the thing a reader would look the operation up
        // under — rather than the two blanket "Resources" / "Controllers" tags this used to carry.
        //
        // Those were removed rather than kept alongside: a tag-grouping docs UI renders an operation
        // once per tag, so carrying both listed every endpoint twice, once under its own name and
        // once under the blanket tag. Nothing consumed them — the dev server tells a resource from a
        // controller by which directory the spec file is in, not by tag.
        //
        // Declared resources first, then controllers, each sorted: the same order the document used
        // to imply, and stable between runs.
        //
        // A LinkedHashSet because a resource and a controller can reduce to one name, and a tag must
        // be declared once. In webservices.rest two do: HL7MessageController1_8 maps GET/POST
        // /ws/rest/v1/hl7, the very routes the HL7Message resource serves, and
        // FormResourceController1_9 hangs /form/{uuid}/resource/{resourceUuid}/value off the
        // form/resource sub-resource. Both are genuinely one API surface, so sharing a tag is right
        // — but it is worth printing, because it also means their operations share a group and
        // would share a generated client class.
        java.util.Set<String> declaredTags = new java.util.LinkedHashSet<String>();
        declaredTags.addAll(resourceTags);
        java.util.SortedSet<String> sharedTags = new java.util.TreeSet<String>(resourceTags);
        sharedTags.retainAll(controllerTags);
        declaredTags.addAll(controllerTags);
        for (String tag : declaredTags) {
            openAPI.addTagsItem(new io.swagger.v3.oas.models.tags.Tag().name(tag));
        }
        for (String shared : sharedTags) {
            System.out.println("Note: '" + shared + "' names both a resource and a controller; "
                    + "their operations are grouped together.");
        }

        // The 3.1 writer emits Schema.types, not the scalar Schema.type, and the two fields are
        // set independently — reconcile them across the finished document before serialising.
        Schemas.normalizeAll(openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null);
        Schemas.normalizeAll(pathSchemas(openAPI));
        // Named schemas only — pathSchemas() is deliberately excluded, see Schemas.titleAll.
        Schemas.titleAll(openAPI.getComponents() != null ? openAPI.getComponents().getSchemas() : null);

        // write the final OpenAPI file
        Path openApiOut = Paths.get(outputDir, outputFile);
        try {
            Files.createDirectories(openApiOut.getParent());
            String json = io.swagger.v3.core.util.Json31.pretty(openAPI);
            // Rewrite all file-relative refs to standard component refs so that
            // openapi.json is self-describing with uniform "#/components/schemas/" pointers.
            //
            // Two forms to fix:
            //   "#/schemas/Foo"           (intra-file refs from OpenMRSResourceModelResolver)
            //   "./Location.json#/schemas/Foo"  (cross-module refs from OpenMRSResourceModelResolver)
            // Both become "#/components/schemas/Foo".
            json = json.replace("\"#/schemas/", "\"#/components/schemas/");
            json = json.replaceAll("\"\\./" + "[A-Za-z0-9_.\\-]+" + "\\.json#/schemas/",
                "\"#/components/schemas/");
            Files.write(openApiOut, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("Wrote OpenAPI file: " + openApiOut.toAbsolutePath());
            System.out.println("Documented " + writtenBy.size() + " resources from this module.");
            // Printed because a missing @Operation(operationId) is otherwise invisible: the name
            // silently falls back to the Java method name, and two modules built against different
            // REST versions would publish different method names for the same endpoint.
            System.out.println("Resource operations named from: " + ROUTE_NAMES.summary());
            recordExternalSchemaOwners(json);
            reportStubbedServices();
        } catch (Exception e) {
            throw new RuntimeException("Unable to write OpenAPI output: " + openApiOut, e);
        }
    }

    /**
     * Schema name -> canonical path of the JAR the module that defines it was loaded from.
     * @see #recordExternalSchemaOwners
     */
    private final java.util.Map<String, String> externalSchemaOwners = new java.util.TreeMap<>();

    /**
     * Which other module defines each schema this document references but does not define.
     * <p>
     * Read across the classloader boundary by {@code GenerateMojo} — hence plain {@code String}s in
     * a plain {@code Map}, both system-classloader types, which is the same reason {@code setup}
     * takes {@code String}s. The mojo turns each path into Maven coordinates, which is knowledge
     * only it has; this side knows only which JAR a class came from.
     *
     * @return schema name -> canonical JAR path, sorted, never null
     */
    public java.util.Map<String, String> getExternalSchemaOwners() {
        return externalSchemaOwners;
    }

    /**
     * Finds every {@code $ref} in the finished document whose target the document does not define,
     * and asks the resolver which JAR the owning resource came from.
     * <p>
     * Done here, on the serialised document, rather than at the sites that emit a {@code $ref}:
     * there are four of those, and "referenced but not defined" is the property that actually
     * matters. A ref this module does define is not cross-module however it was produced.
     */
    /**
     * Schema names that resolved to a free-form object — no properties and no composition.
     * <p>
     * Collected for <em>every</em> handler on the classpath, module-owned or not, which is why
     * {@code resolveAsResolvedSchema} is called before the ownership check.
     *
     * @see #recordExternalSchemaOwners
     */
    private final java.util.Set<String> freeFormSchemas = new java.util.TreeSet<>();

    private void noteFreeFormSchemas(ResolvedSchema resolved) {
        if (resolved == null || resolved.referencedSchemas == null) {
            return;
        }
        for (java.util.Map.Entry<String, Schema> entry : resolved.referencedSchemas.entrySet()) {
            Schema<?> schema = entry.getValue();
            if (schema == null) {
                continue;
            }
            boolean empty = (schema.getProperties() == null || schema.getProperties().isEmpty())
                && schema.getAnyOf() == null && schema.getOneOf() == null
                && schema.getAllOf() == null && schema.get$ref() == null;
            if (empty) {
                freeFormSchemas.add(entry.getKey());
            } else {
                freeFormSchemas.remove(entry.getKey());
            }
        }
    }

    private void recordExternalSchemaOwners(String json) {
        externalSchemaOwners.clear();
        java.util.Set<String> defined = new java.util.HashSet<>();
        io.swagger.v3.oas.models.Components components = null;
        try {
            components = io.swagger.v3.core.util.Json31.mapper()
                .readValue(json, io.swagger.v3.oas.models.OpenAPI.class).getComponents();
        } catch (Exception e) {
            // Falling back to the regex below would be guessing; without the parsed document there
            // is no reliable "defined" set, so report nothing rather than something wrong.
            System.out.println("Could not re-read the document to find cross-module schemas: "
                + e.getMessage());
            return;
        }
        if (components != null && components.getSchemas() != null) {
            defined.addAll(components.getSchemas().keySet());
        }

        java.util.regex.Matcher refs = java.util.regex.Pattern
            .compile("\"#/components/schemas/([A-Za-z0-9_.\\-]+)\"").matcher(json);
        java.util.Set<String> unresolved = new java.util.TreeSet<>();
        while (refs.find()) {
            if (!defined.contains(refs.group(1))) {
                unresolved.add(refs.group(1));
            }
        }

        int freeForm = 0;
        for (String name : unresolved) {
            // A schema that resolved to a free-form object is one openapi-generator will inline
            // rather than emit as a named model — in the owning module's package too, not just
            // here. Importing the name from that package could not work: it does not export it.
            // 82 of webservices.rest's 402 Get_* schemas are empty (the @RepHandler issue, see
            // plan-representation-typing.md), and queue and emrapi between them reference 16 of
            // them. Left un-owned, each keeps the assembler's free-form stub and is inlined as
            // `{ [key: string]: any }` — the same honest degradation the ?v= overloads apply, and
            // it becomes a real imported type on its own once those schemas are filled in.
            if (freeFormSchemas.contains(name)) {
                freeForm++;
                continue;
            }
            String location = OpenMRSResourceModelResolver.ownerLocationForSchema(name);
            if (location != null) {
                externalSchemaOwners.put(name, location);
            }
        }
        if (!externalSchemaOwners.isEmpty() || freeForm > 0) {
            System.out.println("Cross-module schemas referenced: " + externalSchemaOwners.size()
                + " of " + unresolved.size() + " unresolved $ref(s) traced to another module"
                + (freeForm > 0 ? "; " + freeForm + " left inline because the owning module"
                    + " documents them as free-form" : "") + ".");
        }
    }

    /**
     * Deletes the {@code *.json} files directly inside one of this generator's own output
     * directories, so a run leaves behind exactly what it documented. Scoped deliberately: only
     * files, only {@code .json}, only one level deep, and only in a directory this generator
     * created.
     */
    private static void clearGeneratedJson(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.collect(java.util.stream.Collectors.toList())) {
                if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".json")) {
                    Files.delete(entry);
                }
            }
        } catch (IOException e) {
            log.warn("Could not clear stale files in {}: {}", dir, e.getMessage());
        }
    }

    /**
     * The schemas a resource's file must define: its own combined schema plus everything reachable
     * from it through intra-file {@code #/schemas/...} refs.
     * <p>
     * This used to be a prefix test — keep the entries of {@code referencedSchemas} whose name
     * starts with the resource name — on the reasoning that cross-resource references are external
     * file refs and so nothing else needs inlining. That holds for {@code OpenmrsObject} properties,
     * which do get {@code ./Other.json#/schemas/...}, and fails for every other nested POJO:
     * swagger's {@code ModelResolver} registers those in the context and refs them as bare
     * {@code #/components/schemas/Foo}, which needs a local definition. Whether one survived came down to a
     * name coincidence — {@code Module.json} kept {@code ModuleActivator} and dropped
     * {@code Document}, {@code Extension} and {@code AdvicePoint}, leaving refs to nothing.
     * <p>
     * Reachability states the requirement directly, and needs no prefix test at all: every schema
     * the resource owns is reachable from its combined schema, so resources whose names are
     * prefixes of one another ({@code Form}/{@code FormResource},
     * {@code QueueEntry}/{@code QueueEntrySub}) cannot bleed into each other either. External
     * {@code ./Other.json#/...} refs are deliberately not followed — those are cross-module by
     * design.
     */
    private static java.util.Map<String, Schema> reachableSchemas(String resourceName,
            ResolvedSchema resolved) {
        java.util.Map<String, Schema> available = resolved.referencedSchemas != null
            ? resolved.referencedSchemas : java.util.Collections.<String, Schema> emptyMap();

        java.util.Map<String, Schema> selected = new java.util.LinkedHashMap<String, Schema>();
        java.util.Deque<String> pending = new java.util.ArrayDeque<String>();
        selected.put(resourceName, resolved.schema);
        collectLocalRefs(resolved.schema, pending);

        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (selected.containsKey(name)) {
                continue;
            }
            Schema referenced = available.get(name);
            if (referenced == null) {
                // Defined by another resource's run; it will be a cross-module ref there.
                continue;
            }
            selected.put(name, referenced);
            collectLocalRefs(referenced, pending);
        }
        return selected;
    }

    /** Queues the targets of every intra-file {@code #/schemas/<name>} ref in a schema. */
    private static void collectLocalRefs(Object node, java.util.Deque<String> pending) {
        if (node instanceof Schema) {
            Schema<?> schema = (Schema<?>) node;
            String ref = schema.get$ref();
            if (ref != null) {
                // Two intra-document forms coexist: this plugin writes "#/schemas/Foo", swagger's
                // own ModelResolver writes "#/components/schemas/Foo". The final rewrite collapses
                // them, so the difference is invisible in the output but matters here.
                if (ref.startsWith("#/components/schemas/")) {
                    pending.add(ref.substring("#/components/schemas/".length()));
                } else if (ref.startsWith("#/schemas/")) {
                    pending.add(ref.substring("#/schemas/".length()));
                }
            }
            if (schema.getProperties() != null) {
                for (Object value : schema.getProperties().values()) {
                    collectLocalRefs(value, pending);
                }
            }
            collectLocalRefs(schema.getItems(), pending);
            collectLocalRefs(schema.getNot(), pending);
            collectLocalRefs(schema.getAdditionalProperties(), pending);
            collectLocalRefs(schema.getAllOf(), pending);
            collectLocalRefs(schema.getAnyOf(), pending);
            collectLocalRefs(schema.getOneOf(), pending);
        } else if (node instanceof java.util.List) {
            for (Object item : (java.util.List<?>) node) {
                collectLocalRefs(item, pending);
            }
        }
    }

    /**
     * Builds OpenAPI PathItem objects for a resource and adds them to the Paths map.
     * Mirrors the dispatch logic in MainResourceController: each ability interface
     * corresponds to specific HTTP routes.
     */
    /**
     * The route templates {@code MainResourceController} and {@code MainSubResourceController}
     * declare, which is how {@link DispatchControllerNames} is keyed. These strings must match the
     * controllers' {@code @RequestMapping} values exactly — that is the point: if a mapping ever
     * changes, the lookup misses and the run fails rather than publishing a stale operation name.
     */
    private static final String RESOURCE_COLLECTION = "/{resource}";
    private static final String RESOURCE_INSTANCE = "/{resource}/{uuid}";
    private static final String RESOURCE_SEARCH = "/{resource}/search/{searchHandlerId}";
    private static final String SUBRESOURCE_COLLECTION = "/{resource}/{parentUuid}/{subResource}";
    private static final String SUBRESOURCE_INSTANCE =
        "/{resource}/{parentUuid}/{subResource}/{uuid}";

    /** Names resource operations after the controller methods that serve them. */
    private static final DispatchControllerNames ROUTE_NAMES = new DispatchControllerNames();

    /**
     * {@code PatientResource_retrieve} — globally unique, because tags are.
     * <p>
     * openapi-generator requires an operationId to be unique across the whole document, but a bare
     * {@code retrieve} would repeat once per resource. The tag prefix makes it unique by
     * construction, and the TypeScript generator is configured with {@code removeOperationIdPrefix}
     * so the published method is just {@code retrieve()} on a class already called
     * {@code PatientResource}. That is also why the delimiter is {@code _}: it is the prefix
     * separator openapi-generator splits on.
     */
    private static String operationId(String apiTag, String verb, String template, String prefer) {
        String name = ROUTE_NAMES.nameFor(verb, template, prefer);
        if (name == null) {
            throw new IllegalStateException("No dispatch-controller method maps " + verb + " "
                + template + ", so " + apiTag + " cannot be named. MainResourceController or"
                + " MainSubResourceController has changed its @RequestMapping.");
        }
        return apiTag + "_" + name;
    }

    private static void addPathsForHandler(DelegatingResourceHandler<?> handler, String resourceName,
            io.swagger.v3.oas.models.Paths paths, List<String> subtypes) {
        String apiTag = OpenMRSResourceModelResolver.getResourceApiTag(handler);
        if (handler instanceof SubResource) {
            addSubResourcePaths(handler, apiTag, resourceName, paths);
            return;
        }

        String restPath = OpenMRSResourceModelResolver.getResourceRestPath(handler);
        String schemaRef = "#/components/schemas/";

        // ---- Collection path (no UUID): GET list/search, POST create/upload ----
        PathItem collectionItem = new PathItem();

        if (handler instanceof Listable || handler instanceof Searchable) {
            String summary = (handler instanceof Listable && handler instanceof Searchable)
                ? "List or search " + resourceName + " resources"
                : handler instanceof Listable ? "List all " + resourceName + " resources"
                                              : "Search " + resourceName + " resources";
            Schema<?> resultItems = Schemas.ref(schemaRef + resourceName + "Get_ref");
            Schema<?> responseBody = Schemas.object()
                .addProperty("results", Schemas.array().items(resultItems))
                .addProperty("links", Schemas.array().items(Schemas.object()));
            Operation listOp = new Operation()
                .operationId(operationId(apiTag, "GET", RESOURCE_COLLECTION, null))
                .summary(summary)
                .addParametersItem(vParam())
                .responses(new ApiResponses().addApiResponse("200",
                    new ApiResponse().description("Success").content(jsonContent(responseBody))));
            if (!subtypes.isEmpty()) {
                listOp.addParametersItem(typeParam(subtypes));
            }
            collectionItem.get(listOp);
        }

        // Creatable and Uploadable both map to POST on the collection path.
        // Combine them into one operation with multiple accepted content types.
        //
        // Implementing Creatable is not enough: DelegatingCrudResource.create() calls
        // getCreatableProperties(), which BaseDelegatingResource implements as an unconditional
        // ResourceDoesNotSupportOperationException. A resource that neither overrides it nor
        // describes creatable properties therefore cannot be created at all — CareSetting throws
        // explicitly, ConceptReferenceRange and CustomDatatype simply inherit the default — and
        // the <Resource>Create schema those requests would reference is never generated.
        boolean isCreatable = handler instanceof org.openmrs.module.webservices.rest.web.resource.api.Creatable
            && OpenMRSResourceModelResolver.hasCreateSchema(handler);
        if (isCreatable || handler instanceof Uploadable) {
            String summary = isCreatable && handler instanceof Uploadable
                ? "Create a new " + resourceName + " or upload a file"
                : isCreatable ? "Create a new " + resourceName
                              : "Upload a file for " + resourceName;
            Content requestContent = new Content();
            if (isCreatable) {
                requestContent.addMediaType("application/json",
                    new MediaType().schema(Schemas.ref(schemaRef + resourceName + "Create")));
            }
            if (handler instanceof Uploadable) {
                // Titled, because the body is an inline schema and every Uploadable resource's is
                // structurally identical. openapi-generator's InlineModelResolver deduplicates
                // identical inline schemas onto one generated model and names it after whichever
                // path it met first, so obs and module both came out as WsRestV1ModulePostRequest —
                // which then collided with ModuleResource's own request-parameter interface of that
                // name, leaving ObsResource importing a type nothing declared. A title takes
                // precedence over the path-derived name in InlineModelResolver.resolveModelName(),
                // so this gives each resource its own ObsUpload / ModuleUpload.
                requestContent.addMediaType("multipart/form-data",
                    new MediaType().schema(Schemas.object()
                        .title(resourceName + "Upload")
                        .addProperty("file", Schemas.of("string").format("binary"))));
            }
            collectionItem.post(new Operation()
                .operationId(operationId(apiTag, "POST", RESOURCE_COLLECTION,
                    isCreatable ? null : "upload"))
                .summary(summary)
                .requestBody(new RequestBody().required(true).content(requestContent))
                .responses(new ApiResponses().addApiResponse("201",
                    new ApiResponse().description("Created").content(
                        jsonContent(Schemas.ref(schemaRef + resourceName + "Get_default"))))));
        }

        if (collectionItem.readOperationsMap() != null && !collectionItem.readOperationsMap().isEmpty()) {
            tagPathItem(collectionItem, apiTag);
            paths.addPathItem(restPath, collectionItem);
        }

        // ---- Instance path (with UUID): GET retrieve, POST update, DELETE void/purge ----
        PathItem instanceItem = new PathItem();
        Parameter uuidParam = new Parameter().name("uuid").in("path").required(true)
            .description("UUID of the " + resourceName)
            .schema(Schemas.of("string"));

        if (handler instanceof Retrievable) {
            instanceItem.get(new Operation()
                .operationId(operationId(apiTag, "GET", RESOURCE_INSTANCE, null))
                .summary("Retrieve a " + resourceName + " by UUID")
                .addParametersItem(uuidParam)
                .addParametersItem(vParam())
                .responses(new ApiResponses().addApiResponse("200",
                    new ApiResponse().description("Success").content(
                        jsonContent(Schemas.ref(schemaRef + resourceName + "Get"))))));
        }

        // Same for Updatable: getUpdatableProperties() delegates to getCreatableProperties() by
        // default, so it throws for the same resources. Order overrides it to throw outright —
        // orders are immutable in OpenMRS.
        if (handler instanceof Updatable && OpenMRSResourceModelResolver.hasUpdateSchema(handler)) {
            // The update endpoint also handles undelete when the body is {deleted: false}
            instanceItem.post(new Operation()
                .operationId(operationId(apiTag, "POST", RESOURCE_INSTANCE, null))
                .summary("Update a " + resourceName + " (send {deleted: false} to undelete)")
                .addParametersItem(uuidParam)
                .requestBody(new RequestBody().required(true).content(
                    jsonContent(Schemas.ref(schemaRef + resourceName + "Update"))))
                .responses(new ApiResponses().addApiResponse("200",
                    new ApiResponse().description("Updated").content(
                        jsonContent(Schemas.ref(schemaRef + resourceName + "Get_default"))))));
        }

        if (handler instanceof Deletable || handler instanceof Purgeable) {
            Operation deleteOp = new Operation()
                .operationId(operationId(apiTag, "DELETE", RESOURCE_INSTANCE, null))
                .summary(handler instanceof Purgeable
                    ? "Void, retire, or permanently purge a " + resourceName
                    : "Void or retire a " + resourceName)
                .addParametersItem(uuidParam);
            if (handler instanceof Deletable) {
                deleteOp.addParametersItem(new Parameter().name("reason").in("query")
                    .description("Reason for voiding or retiring")
                    .schema(Schemas.of("string")));
            }
            if (handler instanceof Purgeable) {
                deleteOp.addParametersItem(new Parameter().name("purge").in("query")
                    .description("Set to true to permanently delete instead of voiding or retiring")
                    .schema(Schemas.of("boolean")));
            }
            deleteOp.responses(new ApiResponses().addApiResponse("204",
                new ApiResponse().description("No Content")));
            instanceItem.delete(deleteOp);
        }

        if (instanceItem.readOperationsMap() != null && !instanceItem.readOperationsMap().isEmpty()) {
            tagPathItem(instanceItem, apiTag);
            paths.addPathItem(restPath + "/{uuid}", instanceItem);
        }

        addSearchHandlerPath(apiTag, resourceName, restPath, paths);
    }

    /**
     * {@code GET /{resource}/search/{searchHandlerId}}, served by
     * {@code MainResourceController.searchByHandler}.
     * <p>
     * This is <b>not</b> the {@code ?s=} search. Both reach the same {@code SearchHandler}s by the
     * same {@code SearchConfig.getId()}, but by different transports and with different failure
     * modes: {@code searchByHandler} matches the id as a <em>path variable</em> and throws
     * {@code ResourceDoesNotSupportOperationException} on a miss, while {@code ?s=} is resolved by
     * {@code RestServiceImpl.getSearchHandler()} from the query string and throws
     * {@code InvalidSearchException}. Documenting one does not document the other.
     * <p>
     * Emitted for <b>every</b> plain resource, because the controller maps it for every
     * {@code {resource}} value — which handler ids exist is runtime data this plugin does not have.
     * Enumerating them, and the parameters each declares through its {@code SearchQuery}, is the
     * larger piece of work the REST module's own TODO calls "Allow specifying search handler in
     * URL".
     * <p>
     * Sub-resources get nothing: {@code MainSubResourceController} has no {@code searchByHandler},
     * so sub-resource search is reachable only through {@code ?s=} on the collection route.
     * <p>
     * <b>GET only</b>, though the mapping also accepts POST. {@code TypedSearchHandler} reads a
     * JSON body straight off the request, but {@code searchByHandler} declares no
     * {@code @RequestBody}, so reflection sees no body to describe and a generated POST method
     * would have nothing to send. "Allow search using POST request" is a separate TODO item.
     */
    private static void addSearchHandlerPath(String apiTag, String resourceName, String restPath,
            io.swagger.v3.oas.models.Paths paths) {
        // Unlike the other routes, this one may genuinely not exist. searchByHandler was added to
        // MainResourceController after REST 2.42.0, which openmrs-module-emrapi still builds
        // against — so on that version the route is not mapped and documenting it would describe an
        // endpoint that 404s. Every other route has been in the controller for its whole history,
        // which is why operationId() treats a miss there as drift and throws.
        if (ROUTE_NAMES.nameFor("GET", RESOURCE_SEARCH, null) == null) {
            return;
        }
        Schema<?> responseBody = Schemas.object()
            .addProperty("results", Schemas.array()
                .items(Schemas.ref("#/components/schemas/" + resourceName + "Get_ref")))
            .addProperty("links", Schemas.array().items(Schemas.object()));
        PathItem searchItem = new PathItem().get(new Operation()
            .operationId(operationId(apiTag, "GET", RESOURCE_SEARCH, null))
            .summary("Search " + resourceName + " resources with a named search handler")
            .addParametersItem(new Parameter().name("searchHandlerId").in("path").required(true)
                .description("Id of the SearchHandler to run, as declared by its SearchConfig")
                .schema(Schemas.of("string")))
            // The controller builds its RequestContext with Representation.REF as the default
            // rather than DEFAULT, but ?v= still overrides, so this takes the same representations
            // as every other GET.
            .addParametersItem(vParam())
            .responses(new ApiResponses().addApiResponse("200",
                new ApiResponse().description("Success").content(jsonContent(responseBody)))));
        tagPathItem(searchItem, apiTag);
        paths.addPathItem(restPath + "/search/{searchHandlerId}", searchItem);
    }

    /**
     * Builds the paths for a {@code @SubResource}, which is dispatched by
     * {@code MainSubResourceController} rather than {@code MainResourceController}. Two things
     * differ from a plain resource:
     * <ul>
     * <li>every route is nested under the parent's UUID
     * ({@code /{resource}/{parentUuid}/{subResource}})</li>
     * <li>the operation set comes from the {@code SubResource} interface, not from the
     * Retrievable/Listable/Creatable/... ability interfaces — a {@code DelegatingSubResource}
     * implements none of those, so the ability checks used for plain resources all miss and the
     * sub-resource would otherwise be documented with no endpoints at all</li>
     * </ul>
     * {@code MainSubResourceController}'s route table is identical in REST 3.6.x and 4.0.x, so one
     * shape covers both.
     */
    private static void addSubResourcePaths(DelegatingResourceHandler<?> handler,
            String apiTag, String resourceName,
            io.swagger.v3.oas.models.Paths paths) {
        String restPath = OpenMRSResourceModelResolver.getResourceRestPath(handler);
        if (!restPath.contains("{parentUuid}")) {
            // getResourceRestPath() could not resolve the parent and fell back to a flat path (it
            // warns when it does). There is no parent UUID to nest under, and emitting anyway would
            // declare a parentUuid parameter with no matching template variable — an invalid spec.
            return;
        }
        String schemaRef = "#/components/schemas/";
        String parentName = OpenMRSResourceModelResolver.getParentResourceName(handler);
        String ofParent = parentName != null ? " of a " + parentName : " of its parent";

        // ---- Collection path (parent UUID only): GET getAll, POST create, PUT replace ----
        PathItem collectionItem = new PathItem();

        Schema<?> resultItems = Schemas.ref(schemaRef + resourceName + "Get_ref");
        Schema<?> responseBody = Schemas.object()
            .addProperty("results", Schemas.array().items(resultItems))
            .addProperty("links", Schemas.array().items(Schemas.object()));
        collectionItem.get(new Operation()
            .operationId(operationId(apiTag, "GET", SUBRESOURCE_COLLECTION, null))
            .summary("List the " + resourceName + " sub-resources" + ofParent)
            .addParametersItem(parentUuidParam(parentName))
            .addParametersItem(vParam())
            .responses(new ApiResponses().addApiResponse("200",
                new ApiResponse().description("Success").content(jsonContent(responseBody)))));

        // A handler that refuses to describe its creatable properties (CustomDatatypeHandler and
        // ObsReferenceRange both throw from save()) genuinely cannot be created, and has no
        // <Resource>Create schema to point a request body at.
        boolean creatable = OpenMRSResourceModelResolver.hasCreateSchema(handler);
        if (creatable) {
            collectionItem.post(new Operation()
                .operationId(operationId(apiTag, "POST", SUBRESOURCE_COLLECTION, null))
                .summary("Add a " + resourceName + " to a " + (parentName != null ? parentName : "parent"))
                .addParametersItem(parentUuidParam(parentName))
                .requestBody(new RequestBody().required(true).content(
                    jsonContent(Schemas.ref(schemaRef + resourceName + "Create"))))
                .responses(new ApiResponses().addApiResponse("201",
                    new ApiResponse().description("Created").content(
                        jsonContent(Schemas.ref(schemaRef + resourceName + "Get_default"))))));
        }

        // PUT is the one operation that is statically detectable: DelegatingSubResource.put()
        // throws ResourceDoesNotSupportOperationException unless a subclass overrides it, whereas
        // delete() and purge() are abstract in BaseDelegatingResource and so are always present
        // (frequently as bodies that just throw). The other operations therefore document the
        // superset, exactly as they already do for plain resources.
        if (overridesPut(handler) && creatable) {
            collectionItem.put(new Operation()
                .operationId(operationId(apiTag, "PUT", SUBRESOURCE_COLLECTION, null))
                .summary("Replace the " + resourceName + " sub-resources" + ofParent)
                .addParametersItem(parentUuidParam(parentName))
                .requestBody(new RequestBody().required(true).content(
                    jsonContent(Schemas.ref(schemaRef + resourceName + "Create"))))
                .responses(new ApiResponses().addApiResponse("204",
                    new ApiResponse().description("No Content"))));
        }

        // MainSubResourceController also maps DELETE on the collection path, but it calls
        // res.delete(parentUuid, null, ...) and DelegatingSubResource resolves that null uuid via
        // getByUniqueId(null), which cannot work — so it is deliberately not documented.

        tagPathItem(collectionItem, apiTag);
        paths.addPathItem(restPath, collectionItem);

        // ---- Instance path (parent UUID + own UUID): GET retrieve, POST update, DELETE ----
        PathItem instanceItem = new PathItem();
        Parameter uuidParam = new Parameter().name("uuid").in("path").required(true)
            .description("UUID of the " + resourceName)
            .schema(Schemas.of("string"));

        instanceItem.get(new Operation()
            .operationId(operationId(apiTag, "GET", SUBRESOURCE_INSTANCE, null))
            .summary("Retrieve a " + resourceName + " by UUID")
            .addParametersItem(parentUuidParam(parentName))
            .addParametersItem(uuidParam)
            .addParametersItem(vParam())
            .responses(new ApiResponses().addApiResponse("200",
                new ApiResponse().description("Success").content(
                    jsonContent(Schemas.ref(schemaRef + resourceName + "Get"))))));

        if (OpenMRSResourceModelResolver.hasUpdateSchema(handler)) {
            instanceItem.post(new Operation()
                .operationId(operationId(apiTag, "POST", SUBRESOURCE_INSTANCE, null))
                .summary("Update a " + resourceName)
                .addParametersItem(parentUuidParam(parentName))
                .addParametersItem(uuidParam)
                .requestBody(new RequestBody().required(true).content(
                    jsonContent(Schemas.ref(schemaRef + resourceName + "Update"))))
                .responses(new ApiResponses().addApiResponse("200",
                    new ApiResponse().description("Updated").content(
                        jsonContent(Schemas.ref(schemaRef + resourceName + "Get_default"))))));
        }

        instanceItem.delete(new Operation()
            .operationId(operationId(apiTag, "DELETE", SUBRESOURCE_INSTANCE, null))
            .summary("Void, retire, or permanently purge a " + resourceName)
            .addParametersItem(parentUuidParam(parentName))
            .addParametersItem(uuidParam)
            .addParametersItem(new Parameter().name("reason").in("query")
                .description("Reason for voiding or retiring")
                .schema(Schemas.of("string")))
            .addParametersItem(new Parameter().name("purge").in("query")
                .description("Set to true to permanently delete instead of voiding or retiring")
                .schema(Schemas.of("boolean")))
            .responses(new ApiResponses().addApiResponse("204",
                new ApiResponse().description("No Content"))));

        tagPathItem(instanceItem, apiTag);
        paths.addPathItem(restPath + "/{uuid}", instanceItem);
    }

    /**
     * Whether the handler overrides {@code SubResource.put()}, which
     * {@code DelegatingSubResource} implements as an unconditional
     * {@code ResourceDoesNotSupportOperationException}. Returns false when the REST module on the
     * classpath has no {@code put} at all.
     */
    private static boolean overridesPut(DelegatingResourceHandler<?> handler) {
        try {
            java.lang.reflect.Method put = handler.getClass().getMethod("put", String.class,
                org.openmrs.module.webservices.rest.SimpleObject.class,
                org.openmrs.module.webservices.rest.web.RequestContext.class);
            return !org.openmrs.module.webservices.rest.web.resource.impl.DelegatingSubResource.class
                .equals(put.getDeclaringClass());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** The {parentUuid} path variable that every MainSubResourceController route carries. */
    private static Parameter parentUuidParam(String parentName) {
        return new Parameter().name("parentUuid").in("path").required(true)
            .description("UUID of the parent " + (parentName != null ? parentName : "resource"))
            .schema(Schemas.of("string"));
    }

    /**
     * MainResourceController reads the subtype to list by from {@code ?t=}
     * ({@code RestConstants.REQUEST_PROPERTY_FOR_TYPE}), which routes to the handler's
     * {@code getAllByType()}. Only meaningful for a resource with a class hierarchy.
     */
    private static Parameter typeParam(List<String> subtypes) {
        Schema<String> schema = Schemas.<String>of("string");
        for (String subtype : subtypes) {
            schema.addEnumItemObject(subtype);
        }
        return new Parameter().name("t").in("query")
            .description("Restrict results to one subtype")
            .schema(schema);
    }

    /** The type names a resource's bound subclass handlers expose, or empty when it has none. */
    private List<String> subtypeNames(DelegatingResourceHandler<?> handler) {
        List<DelegatingSubclassHandler<?, ?>> bound = subclassHandlers.get(handler);
        if (bound == null || bound.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        // Subclass type names only. DelegatingCrudResource.getAll() looks the value up with
        // getSubclassHandler(type) and throws "No handler is specified for t=..." when it misses,
        // so ?t=<base type> is rejected even though the base type is a valid discriminator value in
        // responses — listing the base type is the default behaviour, expressed by omitting ?t=.
        List<String> names = new java.util.ArrayList<String>();
        for (DelegatingSubclassHandler<?, ?> subclassHandler : bound) {
            names.add(OpenMRSResourceModelResolver.getSubclassTypeName(subclassHandler));
        }
        return names;
    }

    /**
     * Every schema reachable from the document's paths — parameters, request bodies and responses —
     * keyed arbitrarily, since {@link Schemas#normalizeAll} only iterates the values.
     */
    private static java.util.Map<String, Schema> pathSchemas(OpenAPI openAPI) {
        java.util.Map<String, Schema> collected = new java.util.LinkedHashMap<String, Schema>();
        if (openAPI.getPaths() == null) {
            return collected;
        }
        int i = 0;
        for (PathItem item : openAPI.getPaths().values()) {
            for (Operation op : item.readOperations()) {
                if (op.getParameters() != null) {
                    for (Parameter parameter : op.getParameters()) {
                        i = collect(collected, parameter.getSchema(), i);
                    }
                }
                if (op.getRequestBody() != null) {
                    i = collectContent(collected, op.getRequestBody().getContent(), i);
                }
                if (op.getResponses() != null) {
                    for (ApiResponse response : op.getResponses().values()) {
                        i = collectContent(collected, response.getContent(), i);
                    }
                }
            }
        }
        return collected;
    }

    private static int collectContent(java.util.Map<String, Schema> collected, Content content, int i) {
        if (content != null) {
            for (MediaType mediaType : content.values()) {
                i = collect(collected, mediaType.getSchema(), i);
            }
        }
        return i;
    }

    private static int collect(java.util.Map<String, Schema> collected, Schema schema, int i) {
        if (schema != null) {
            collected.put(String.valueOf(i++), schema);
        }
        return i;
    }

    private static void tagPathItem(PathItem item, String tag) {
        for (Operation op : item.readOperations()) {
            op.addTagsItem(tag);
        }
    }

    private static Content jsonContent(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }

    /** The ?v= representation query parameter, common to all GET operations. */
    private static Parameter vParam() {
        return new Parameter().name("v").in("query")
            .description("The representation to return (ref, default, full, or custom)")
            .schema(Schemas.of("string")._default("default"));
    }

    /**
     * Returns true if the handler's class was loaded from this module's own compiled output
     * (target/classes) or a sibling artifact of the same Maven project, false if it came from a
     * dependency JAR. Falls back to true when no owned locations were supplied.
     * <p>
     * A module can also own only a <em>subtype</em> of a resource: registering a
     * {@code DelegatingSubclassHandler} against another module's resource (core's {@code v1/order},
     * say) changes that endpoint's shape wherever this module is installed, and since subclass
     * handlers are documented as part of their parent rather than as resources of their own, the
     * contribution would otherwise vanish from this module's spec entirely. So the parent is
     * documented too, and the run says why.
     */
    private boolean isModuleOwnedHandler(DelegatingResourceHandler<?> handler) {
        if (ModuleOwnership.isOwned(handler.getClass(), ownedLocations)) {
            return true;
        }
        List<DelegatingSubclassHandler<?, ?>> bound = subclassHandlers.get(handler);
        if (bound == null) {
            return false;
        }
        for (DelegatingSubclassHandler<?, ?> subclassHandler : bound) {
            if (ModuleOwnership.isOwned(subclassHandler.getClass(), ownedLocations)) {
                System.out.println("Documenting " + handler.getClass().getName()
                        + ", which belongs to another module, because this module adds the subtype '"
                        + OpenMRSResourceModelResolver.getSubclassTypeName(subclassHandler) + "' to it");
                return true;
            }
        }
        return false;
    }

    /**
     * Prints every service call that a stub answered. A stub returning a default can change a
     * schema without failing, so the calls are surfaced rather than left silent.
     */
    private static void reportStubbedServices() {
        java.util.Set<String> stubbed = StubRuntime.getStubbedServices();
        if (stubbed.isEmpty()) {
            System.out.println("No stubbed service calls were needed.");
            return;
        }
        System.out.println("Stubbed service calls (" + stubbed.size()
                + ") — these returned defaults and may affect the schemas above:");
        for (String call : stubbed) {
            System.out.println("  " + call);
        }
    }

    /** The module being documented, e.g. "Queue OMOD". */
    private String specTitle() {
        return (moduleName != null && !moduleName.trim().isEmpty())
                ? moduleName.trim() : "OpenMRS REST API";
    }

    /**
     * The core version belongs here because resources are version-gated via
     * supportedOpenmrsVersions, so the same module yields different schemas on different cores.
     */
    private String specDescription() {
        return "OpenAPI documentation for the REST resources and controllers of " + specTitle()
                + ". Generated against openmrs-core " + detectOpenmrsVersion() + ".";
    }

    /**
     * Reads the core version from openmrs-api's manifest rather than the (unavailable) database
     * global property. HandlerScanner already fails fast if this is blank.
     */
    private String detectOpenmrsVersion() {
        String version = org.openmrs.util.OpenmrsConstants.OPENMRS_VERSION_SHORT;
        return (version != null && !version.trim().isEmpty()) ? version : "unknown";
    }
}
