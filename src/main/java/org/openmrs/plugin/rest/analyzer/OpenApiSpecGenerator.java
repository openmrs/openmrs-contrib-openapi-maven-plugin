package org.openmrs.plugin.rest.analyzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.dbunit.database.DatabaseConfig;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.dbunit.ext.h2.H2DataTypeFactory;
import org.dbunit.operation.DatabaseOperation;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Environment;
import org.hibernate.dialect.H2Dialect;

import org.openmrs.Attributable;
import org.openmrs.Auditable;
import org.openmrs.Changeable;
import org.openmrs.Creatable;
import org.openmrs.FormRecordable;
import org.openmrs.GlobalProperty;
import org.openmrs.Retireable;
import org.openmrs.Voidable;
import org.openmrs.api.context.Context;
import org.openmrs.customdatatype.Customizable;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.api.RestService;
import org.openmrs.module.webservices.rest.web.resource.api.Deletable;
import org.openmrs.module.webservices.rest.web.resource.api.Listable;
import org.openmrs.module.webservices.rest.web.resource.api.Purgeable;
import org.openmrs.module.webservices.rest.web.resource.api.Retrievable;
import org.openmrs.module.webservices.rest.web.resource.api.Searchable;
import org.openmrs.module.webservices.rest.web.resource.api.Updatable;
import org.openmrs.module.webservices.rest.web.resource.api.Uploadable;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.plugin.CustomModelResolver;
import org.openmrs.plugin.OpenmrsResourceAnnotatedType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.XmlWebApplicationContext;

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
 * Uses CustomModelResolver for accurate property type discovery and Swagger-Core models.
 */
public class OpenApiSpecGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSpecGenerator.class);

    private org.springframework.web.context.support.XmlWebApplicationContext ctx;

    public void setup(String moduleClassesDir, String omdCommonJarPath) throws Exception {
        log.info("=== Setting up OpenAPI Spec Generator ===");

        final String h2Url = "jdbc:h2:mem:openmrs;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;IGNORECASE=TRUE";

        // Step 2: Configure Hibernate runtime properties
        Properties props = new Properties();
        props.setProperty(Environment.DIALECT, H2Dialect.class.getName());
        props.setProperty(Environment.URL, h2Url);
        props.setProperty(Environment.DRIVER, "org.h2.Driver");
        props.setProperty(Environment.USER, "sa");
        props.setProperty(Environment.PASS, "");
        props.setProperty(Environment.HBM2DDL_AUTO, "create-drop");
        Context.setRuntimeProperties(props);

        // Step 3: Start Spring WebApplicationContext with mock servlet context.
        // Use file: URL for webModuleApplicationContext.xml to load only the target module's own copy,
        // avoiding double-loading when both omod-common and omod are on the classpath.
        ctx = new XmlWebApplicationContext();
        javax.servlet.FilterRegistration.Dynamic noopFilter = new javax.servlet.FilterRegistration.Dynamic() {
            public void addMappingForServletNames(java.util.EnumSet<javax.servlet.DispatcherType> d, boolean b, String... names) {}
            public java.util.Collection<String> getServletNameMappings() { return java.util.Collections.emptyList(); }
            public void addMappingForUrlPatterns(java.util.EnumSet<javax.servlet.DispatcherType> d, boolean b, String... patterns) {}
            public java.util.Collection<String> getUrlPatternMappings() { return java.util.Collections.emptyList(); }
            public String getName() { return ""; }
            public String getClassName() { return ""; }
            public boolean setInitParameter(String name, String value) { return false; }
            public String getInitParameter(String name) { return null; }
            public java.util.Set<String> setInitParameters(java.util.Map<String, String> initParameters) { return java.util.Collections.emptySet(); }
            public java.util.Map<String, String> getInitParameters() { return java.util.Collections.emptyMap(); }
            public void setAsyncSupported(boolean isAsyncSupported) {}
        };
        ctx.setServletContext(new MockServletContext() {
            @Override
            public javax.servlet.FilterRegistration.Dynamic addFilter(String filterName, String className) { return noopFilter; }
            @Override
            public javax.servlet.FilterRegistration.Dynamic addFilter(String filterName, javax.servlet.Filter filter) { return noopFilter; }
            @Override
            public javax.servlet.FilterRegistration.Dynamic addFilter(String filterName, Class<? extends javax.servlet.Filter> filterClass) { return noopFilter; }
            @Override
            public void addListener(String className) {}
            @Override
            public <T extends java.util.EventListener> void addListener(T t) {}
            @Override
            public void addListener(Class<? extends java.util.EventListener> listenerClass) {}
            @Override
            public javax.servlet.ServletRegistration getServletRegistration(String servletName) { return null; }
            @Override
            public javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, String className) { return null; }
            @Override
            public javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, javax.servlet.Servlet servlet) { return null; }
            @Override
            public javax.servlet.ServletRegistration.Dynamic addServlet(String servletName, Class<? extends javax.servlet.Servlet> servletClass) { return null; }
            @Override
            public java.util.Map<String, ? extends javax.servlet.ServletRegistration> getServletRegistrations() { return java.util.Collections.emptyMap(); }
        });
        List<String> configLocations = new java.util.ArrayList<>(java.util.Arrays.asList(
            "classpath:applicationContext-service.xml",
            "classpath*:TestingApplicationContext.xml",
            "classpath*:moduleApplicationContext.xml",
            "classpath*:openmrs-servlet.xml"
        ));
        // Always load webModuleApplicationContext.xml from webservices.rest-omd-common — it
        // defines restService, restHelperService, and the component scan for REST controllers.
        // Using an explicit jar: URL avoids classpath scan ambiguity across two classloaders.
        // Spring 5's allowBeanDefinitionOverriding defaults to true, so if the target module
        // also provides webModuleApplicationContext.xml the last definition wins harmlessly.
        if (omdCommonJarPath != null && !omdCommonJarPath.isEmpty()) {
            String omdCommonCtxUrl = "jar:file:" + omdCommonJarPath + "!/webModuleApplicationContext.xml";
            configLocations.add(omdCommonCtxUrl);
            log.info("Loading REST beans from omd-common: {}", omdCommonCtxUrl);
        } else {
            log.warn("omdCommonJarPath is empty — restService bean may not be available");
        }
        if (moduleClassesDir != null && !moduleClassesDir.isEmpty()) {
            java.io.File webModuleCtxFile = new java.io.File(moduleClassesDir, "webModuleApplicationContext.xml");
            if (webModuleCtxFile.exists()) {
                configLocations.add("file:" + webModuleCtxFile.getAbsolutePath());
                log.info("Loading webModuleApplicationContext.xml from: {}", webModuleCtxFile.getAbsolutePath());
            }
        }
        ctx.setConfigLocations(configLocations.toArray(new String[0]));
        ctx.refresh();

        // Step 5: Open session, load initial test dataset (contains admin user), authenticate
        Context.openSession();
        SessionFactory sessionFactory = (SessionFactory) ctx.getBean("sessionFactory");
        Connection conn = sessionFactory.getCurrentSession().doReturningWork(c -> c);
        loadDataSet(conn, "org/openmrs/include/initialInMemoryTestDataSet.xml");
        conn.commit();
        Context.authenticate("admin", "test");

        Context.getAdministrationService().saveGlobalProperty(
            new GlobalProperty(RestConstants.SWAGGER_QUIET_DOCS_GLOBAL_PROPERTY_NAME, "true"));
        Context.flushSession();
    }

    public void getResourceMetadata() {
        System.out.println("Starting restService...");
        RestService restService = Context.getService(RestService.class);
        restService.initialize();
        List<DelegatingResourceHandler<?>> handlers = restService.getResourceHandlers();
        Collections.sort(handlers, Comparator.comparing(h -> h.getClass().getSimpleName()));
        System.out.println("Starting restService... done");

        for (DelegatingResourceHandler<?> handler : handlers) {
            List<Class<?>> allResourceAbilities = Arrays.asList(Retrievable.class, Searchable.class, Listable.class, Creatable.class, Updatable.class, Uploadable.class, Deletable.class, Purgeable.class);
            List<Class<?>> allDelegateAbilities = Arrays.asList(FormRecordable.class, Retireable.class, Voidable.class, Changeable.class, Auditable.class, Customizable.class, org.openmrs.Creatable.class, Attributable.class);
            String resourceName = CustomModelResolver.getResourceName(handler);
            List<String> resourceAbilities = allResourceAbilities.stream().filter(ability -> ability.isAssignableFrom(handler.getClass())).map(Class::getSimpleName).collect(Collectors.toList());

            System.out.println(resourceName + ":: " + resourceAbilities);
        }
    }

    public void generateOpenAPISpec(String outputDir, String outputFile) {

        OpenAPI openAPI = new OpenAPI(SpecVersion.V31)
            .info(new Info()
                .title("OpenMRS REST API")
                .version(detectOpenmrsVersion())
                .description("OpenAPI documentation for Person class"));

        ModelConverters converters = ModelConverters.getInstance(true);

        System.out.println("Starting restService...");
        RestService restService = Context.getService(RestService.class);
        restService.initialize();
        List<DelegatingResourceHandler<?>> handlers = restService.getResourceHandlers();
        Collections.sort(handlers, Comparator.comparing(h -> h.getClass().getSimpleName()));
        System.out.println("Starting restService... done");

        converters.addConverter(new CustomModelResolver(Json31.mapper()));

        Components components = new Components();

        // directory for individual schema files
        Path schemaDir = Paths.get(outputDir, "resources");
        try {
            Files.createDirectories(schemaDir);
        } catch (Exception e) {
            throw new RuntimeException("Unable to create schema output directory: " + schemaDir, e);
        }

        com.fasterxml.jackson.databind.ObjectMapper swaggerMapper = io.swagger.v3.core.util.Json.mapper();

        for (DelegatingResourceHandler<?> handler : handlers) {
            String resourceName = CustomModelResolver.getResourceName(handler);
            log.info("generating " + resourceName);
            ResolvedSchema resolvedSchema = converters.resolveAsResolvedSchema(new OpenmrsResourceAnnotatedType(handler.getClass(), handler));

            // Collect schemas for this resource
            Components resourceComponents = new Components();
            resourceComponents.addSchemas(resourceName, resolvedSchema.schema);
            if (resolvedSchema.referencedSchemas != null) {
                // Only include schemas belonging to this resource (e.g. VisitGet_default, VisitCreate).
                // CustomModelResolver may register orphaned schemas in the context as a side effect of
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

            // In main openapi.json components keep only $ref placeholders to the external file
            Schema<Object> refSchema = new Schema<>();
            refSchema.$ref("./resources/" + resourceName + ".json#/schemas/" + resourceName);
            components.addSchemas(resourceName, refSchema);
        }

        openAPI.components(components);

        // Document concrete @Controller beans (all except MainResourceController / MainSubResourceController)
        if (ctx != null) {
            try {
                Path controllersDir = Paths.get(outputDir, "controllers");
                Files.createDirectories(controllersDir);
                io.swagger.v3.oas.models.Paths controllerPaths =
                    new ControllerDocumenter().document(ctx, controllersDir, components);
                if (!controllerPaths.isEmpty()) {
                    openAPI.paths(controllerPaths);
                }
            } catch (Exception e) {
                log.warn("Failed to document controllers: {}", e.getMessage(), e);
            }
        }

        // write the final OpenAPI file with refs to generated schema files
        Path openApiOut = Paths.get(outputDir, outputFile);
        try {
            Files.createDirectories(openApiOut.getParent());
            String json = io.swagger.v3.core.util.Json.pretty(openAPI);
            Files.write(openApiOut, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("Wrote OpenAPI file: " + openApiOut.toAbsolutePath());
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
        String restPath = CustomModelResolver.getResourceRestPath(handler);
        // $ref prefix pointing from openapi.json into the per-resource schema file
        String schemaRef = "./resources/" + resourceName + ".json#/schemas/";

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
            paths.addPathItem(restPath + "/{uuid}", instanceItem);
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

    private static void loadDataSet(Connection conn, String path) throws Exception {
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (is == null) {
            throw new RuntimeException("Dataset resource not found on classpath: " + path);
        }
        IDatabaseConnection dbConn = new DatabaseConnection(conn, "PUBLIC");
        DatabaseConfig config = dbConn.getConfig();
        config.setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new H2DataTypeFactory());
        IDataSet dataset = new FlatXmlDataSetBuilder().setColumnSensing(true).build(is);
        DatabaseOperation.REFRESH.execute(dbConn, dataset);
    }

    private String detectOpenmrsVersion() {
        try {
            String version = Context.getAdministrationService().getGlobalProperty("openmrs.version");
            if (version != null && !version.isEmpty()) {
                return version;
            }
        } catch (IllegalArgumentException | SecurityException ignored) {}

        return "unknown";
    }
}
