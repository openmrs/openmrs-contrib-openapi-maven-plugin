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
import org.openmrs.module.webservices.rest.web.resource.api.Updatable;
import org.openmrs.module.webservices.rest.web.resource.api.Uploadable;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
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

        controllerClasses = scanner.findControllers();
    }

    public void generateOpenAPISpec(String outputDir, String outputFile) {

        // The spec describes one module, so it is titled and versioned by that module rather than
        // by openmrs-core. The core version still matters for interpreting the schemas (resources
        // are version-gated via supportedOpenmrsVersions), so it goes in the description.
        OpenAPI openAPI = new OpenAPI(SpecVersion.V31)
            .info(new Info()
                .title(specTitle())
                .version(moduleVersion != null && !moduleVersion.trim().isEmpty()
                        ? moduleVersion : "unknown")
                .description(specDescription()));

        ModelConverters converters = ModelConverters.getInstance(true);

        converters.addConverter(new OpenMRSResourceModelResolver(Json31.mapper()));

        Components components = new Components();

        // directory for individual schema files
        Path schemaDir = Paths.get(outputDir, "resources");
        try {
            Files.createDirectories(schemaDir);
        } catch (Exception e) {
            throw new RuntimeException("Unable to create schema output directory: " + schemaDir, e);
        }

        com.fasterxml.jackson.databind.ObjectMapper swaggerMapper = io.swagger.v3.core.util.Json.mapper();

        // Two owned handlers can resolve to the same resource name, in which case the second
        // overwrites the first file. Track them so the count reported to the user reconciles
        // with the number of files on disk.
        java.util.Map<String, String> writtenBy = new java.util.LinkedHashMap<String, String>();

        for (DelegatingResourceHandler<?> handler : handlers) {
            String resourceName = OpenMRSResourceModelResolver.getResourceName(handler);

            // Always resolve schemas — the ModelConverterContext needs every handler processed so
            // that cross-module $ref targets (e.g. Patient, Visit) can be named correctly.
            ResolvedSchema resolvedSchema = converters.resolveAsResolvedSchema(new OpenmrsResourceAnnotatedType(handler));

            // Only write files and openapi.json entries for resources defined in this module.
            // Handlers from dependency JARs (REST core, openmrs-api, etc.) are skipped.
            if (!isModuleOwnedHandler(handler)) {
                continue;
            }

            String previousOwner = writtenBy.put(resourceName, handler.getClass().getName());
            if (previousOwner != null) {
                System.out.println("WARN  resource name '" + resourceName + "' claimed by both "
                        + previousOwner + " and " + handler.getClass().getName()
                        + "; the later one wins and only one file is written");
            }
            log.info("generating " + resourceName);

            // Collect schemas for this resource
            Components resourceComponents = new Components();
            resourceComponents.addSchemas(resourceName, resolvedSchema.schema);
            if (resolvedSchema.referencedSchemas != null) {
                // Only include schemas belonging to this resource (e.g. VisitGet_default, VisitCreate).
                // OpenMRSResourceModelResolver may register orphaned schemas in the context as a side effect of
                // calling super.resolve() on complex non-OpenmrsObject types (e.g. CodedOrFreeText,
                // Allergen). Those schemas are redundant here because cross-resource references are
                // expressed as $ref pointers to the owning resource's own JSON file, not inline copies.
                resolvedSchema.referencedSchemas.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(resourceName))
                    .forEach(e -> resourceComponents.addSchemas(e.getKey(), e.getValue()));
            }

            // Build paths for this resource
            io.swagger.v3.oas.models.Paths resourcePaths = new io.swagger.v3.oas.models.Paths();
            addPathsForHandler(handler, resourceName, resourcePaths);

            // Write per-resource file: { "schemas": {...}, "paths": {...} }
            com.fasterxml.jackson.databind.node.ObjectNode root = swaggerMapper.createObjectNode();
            com.fasterxml.jackson.databind.JsonNode componentsJson = swaggerMapper.valueToTree(resourceComponents);
            if (componentsJson.has("schemas")) {
                root.set("schemas", componentsJson.get("schemas"));
            }
            if (!resourcePaths.isEmpty()) {
                root.set("paths", swaggerMapper.valueToTree(resourcePaths));
            }
            Path outFile = schemaDir.resolve(resourceName + ".json");
            try {
                Files.write(outFile, swaggerMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(root));
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Wrote schema file: " + outFile.toAbsolutePath());

            // Add all schemas for this resource directly into components/schemas so that
            // Swagger UI can resolve named $refs (QueueGet, QueueCreate, etc.) without
            // needing to follow external file refs.
            components.addSchemas(resourceName, resolvedSchema.schema);
            if (resolvedSchema.referencedSchemas != null) {
                resolvedSchema.referencedSchemas.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(resourceName))
                    .forEach(e -> components.addSchemas(e.getKey(), e.getValue()));
            }

            // Add resource paths to the main openapi.json so they appear in Swagger UI.
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
        if (controllerClasses != null && !controllerClasses.isEmpty()) {
            try {
                Path controllersDir = Paths.get(outputDir, "controllers");
                Files.createDirectories(controllersDir);
                io.swagger.v3.oas.models.Paths controllerPaths =
                    new ControllerDocumenter().document(controllerClasses, controllersDir, components,
                        ownedLocations != null ? String.join(";", ownedLocations) : "");
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

        openAPI.addTagsItem(new io.swagger.v3.oas.models.tags.Tag().name("Resources"));
        openAPI.addTagsItem(new io.swagger.v3.oas.models.tags.Tag().name("Controllers"));

        // write the final OpenAPI file
        Path openApiOut = Paths.get(outputDir, outputFile);
        try {
            Files.createDirectories(openApiOut.getParent());
            String json = io.swagger.v3.core.util.Json.pretty(openAPI);
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
            reportStubbedServices();
        } catch (Exception e) {
            throw new RuntimeException("Unable to write OpenAPI output: " + openApiOut, e);
        }
    }

    /**
     * Builds OpenAPI PathItem objects for a resource and adds them to the Paths map.
     * Mirrors the dispatch logic in MainResourceController: each ability interface
     * corresponds to specific HTTP routes.
     */
    private static void addPathsForHandler(DelegatingResourceHandler<?> handler, String resourceName, io.swagger.v3.oas.models.Paths paths) {
        String restPath = OpenMRSResourceModelResolver.getResourceRestPath(handler);
        String schemaRef = "#/components/schemas/";

        // ---- Collection path (no UUID): GET list/search, POST create/upload ----
        PathItem collectionItem = new PathItem();

        if (handler instanceof Listable || handler instanceof Searchable) {
            String summary = (handler instanceof Listable && handler instanceof Searchable)
                ? "List or search " + resourceName + " resources"
                : handler instanceof Listable ? "List all " + resourceName + " resources"
                                              : "Search " + resourceName + " resources";
            Schema<?> resultItems = new Schema<>().$ref(schemaRef + resourceName + "Get_ref");
            Schema<?> responseBody = new ObjectSchema()
                .addProperty("results", new ArraySchema().items(resultItems))
                .addProperty("links", new ArraySchema().items(new ObjectSchema()));
            collectionItem.get(new Operation()
                .summary(summary)
                .addParametersItem(vParam())
                .responses(new ApiResponses().addApiResponse("200",
                    new ApiResponse().description("Success").content(jsonContent(responseBody)))));
        }

        // Creatable and Uploadable both map to POST on the collection path.
        // Combine them into one operation with multiple accepted content types.
        boolean isCreatable = handler instanceof org.openmrs.module.webservices.rest.web.resource.api.Creatable;
        if (isCreatable || handler instanceof Uploadable) {
            String summary = isCreatable && handler instanceof Uploadable
                ? "Create a new " + resourceName + " or upload a file"
                : isCreatable ? "Create a new " + resourceName
                              : "Upload a file for " + resourceName;
            Content requestContent = new Content();
            if (isCreatable) {
                requestContent.addMediaType("application/json",
                    new MediaType().schema(new Schema<>().$ref(schemaRef + resourceName + "Create")));
            }
            if (handler instanceof Uploadable) {
                requestContent.addMediaType("multipart/form-data",
                    new MediaType().schema(new ObjectSchema()
                        .addProperty("file", new Schema<>().type("string").format("binary"))));
            }
            collectionItem.post(new Operation()
                .summary(summary)
                .requestBody(new RequestBody().required(true).content(requestContent))
                .responses(new ApiResponses().addApiResponse("201",
                    new ApiResponse().description("Created").content(
                        jsonContent(new Schema<>().$ref(schemaRef + resourceName + "Get_default"))))));
        }

        if (collectionItem.readOperationsMap() != null && !collectionItem.readOperationsMap().isEmpty()) {
            tagPathItem(collectionItem, "Resources");
            paths.addPathItem(restPath, collectionItem);
        }

        // ---- Instance path (with UUID): GET retrieve, POST update, DELETE void/purge ----
        PathItem instanceItem = new PathItem();
        Parameter uuidParam = new Parameter().name("uuid").in("path").required(true)
            .description("UUID of the " + resourceName)
            .schema(new Schema<>().type("string"));

        if (handler instanceof Retrievable) {
            instanceItem.get(new Operation()
                .summary("Retrieve a " + resourceName + " by UUID")
                .addParametersItem(uuidParam)
                .addParametersItem(vParam())
                .responses(new ApiResponses().addApiResponse("200",
                    new ApiResponse().description("Success").content(
                        jsonContent(new Schema<>().$ref(schemaRef + resourceName + "Get"))))));
        }

        if (handler instanceof Updatable) {
            // The update endpoint also handles undelete when the body is {deleted: false}
            instanceItem.post(new Operation()
                .summary("Update a " + resourceName + " (send {deleted: false} to undelete)")
                .addParametersItem(uuidParam)
                .requestBody(new RequestBody().required(true).content(
                    jsonContent(new Schema<>().$ref(schemaRef + resourceName + "Update"))))
                .responses(new ApiResponses().addApiResponse("200",
                    new ApiResponse().description("Updated").content(
                        jsonContent(new Schema<>().$ref(schemaRef + resourceName + "Get_default"))))));
        }

        if (handler instanceof Deletable || handler instanceof Purgeable) {
            Operation deleteOp = new Operation()
                .summary(handler instanceof Purgeable
                    ? "Void, retire, or permanently purge a " + resourceName
                    : "Void or retire a " + resourceName)
                .addParametersItem(uuidParam);
            if (handler instanceof Deletable) {
                deleteOp.addParametersItem(new Parameter().name("reason").in("query")
                    .description("Reason for voiding or retiring")
                    .schema(new Schema<>().type("string")));
            }
            if (handler instanceof Purgeable) {
                deleteOp.addParametersItem(new Parameter().name("purge").in("query")
                    .description("Set to true to permanently delete instead of voiding or retiring")
                    .schema(new Schema<>().type("boolean")));
            }
            deleteOp.responses(new ApiResponses().addApiResponse("204",
                new ApiResponse().description("No Content")));
            instanceItem.delete(deleteOp);
        }

        if (instanceItem.readOperationsMap() != null && !instanceItem.readOperationsMap().isEmpty()) {
            tagPathItem(instanceItem, "Resources");
            paths.addPathItem(restPath + "/{uuid}", instanceItem);
        }
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
            .schema(new Schema<>().type("string")._default("default"));
    }

    /**
     * Returns true if the handler's class was loaded from this module's own compiled output
     * (target/classes) or a sibling artifact of the same Maven project, false if it came from a
     * dependency JAR. Falls back to true when no owned locations were supplied.
     */
    private boolean isModuleOwnedHandler(DelegatingResourceHandler<?> handler) {
        return ModuleOwnership.isOwned(handler.getClass(), ownedLocations);
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
