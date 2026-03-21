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
import org.openmrs.test.TestUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.XmlWebApplicationContext;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;

/**
 * OpenAPI 3.1 specification generator for OpenMRS REST resources.
 * Uses CustomModelResolver for accurate property type discovery and Swagger-Core models.
 */
public class OpenApiSpecGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiSpecGenerator.class);

    public void setup() throws Exception {
        log.info("=== Setting up OpenAPI Spec Generator ===");

        String targetModuleGroupId = System.getProperty("target.module.groupId", "unknown");
        String targetModuleArtifactId = System.getProperty("target.module.artifactId", "unknown");
        String targetModuleVersion = System.getProperty("target.module.version", "unknown");
        String scanPackagesStr = System.getProperty("target.module.packages", "");

        log.info("Target module: {}:{}:{}", targetModuleGroupId, targetModuleArtifactId, targetModuleVersion);
        log.info("Scan packages: {}", scanPackagesStr);

        // Step 1: Ensure useInMemoryDatabase is set so TestUtil returns H2 config
        System.setProperty("useInMemoryDatabase", "true");

        // NON_KEYWORDS=VALUE,KEY,NAME,TYPE: un-reserve H2 2.x keywords used as column names in OpenMRS HBM mappings
        final String h2Url = "jdbc:h2:mem:openmrs;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000;IGNORECASE=TRUE;NON_KEYWORDS=VALUE,KEY,NAME,TYPE";

        // Step 2: Configure Hibernate runtime properties
        Properties props = TestUtil.getRuntimeProperties("openmrs");
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
        XmlWebApplicationContext ctx = new XmlWebApplicationContext();
        jakarta.servlet.FilterRegistration.Dynamic noopFilter = new jakarta.servlet.FilterRegistration.Dynamic() {
            public void addMappingForServletNames(java.util.EnumSet<jakarta.servlet.DispatcherType> d, boolean b, String... names) {}
            public java.util.Collection<String> getServletNameMappings() { return java.util.Collections.emptyList(); }
            public void addMappingForUrlPatterns(java.util.EnumSet<jakarta.servlet.DispatcherType> d, boolean b, String... patterns) {}
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
            public jakarta.servlet.FilterRegistration.Dynamic addFilter(String filterName, String className) { return noopFilter; }
            @Override
            public jakarta.servlet.FilterRegistration.Dynamic addFilter(String filterName, jakarta.servlet.Filter filter) { return noopFilter; }
            @Override
            public jakarta.servlet.FilterRegistration.Dynamic addFilter(String filterName, Class<? extends jakarta.servlet.Filter> filterClass) { return noopFilter; }
            @Override
            public void addListener(String className) {}
            @Override
            public <T extends java.util.EventListener> void addListener(T t) {}
            @Override
            public void addListener(Class<? extends java.util.EventListener> listenerClass) {}
        });
        List<String> configLocations = new java.util.ArrayList<>(java.util.Arrays.asList(
            "classpath:applicationContext-service.xml",
            "classpath*:TestingApplicationContext.xml",
            "classpath*:moduleApplicationContext.xml",
            "classpath*:openmrs-servlet.xml"
        ));
        String moduleClassesDir = System.getProperty("target.module.classesDir", "");
        if (!moduleClassesDir.isEmpty()) {
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
        // Create shedlock table (not in Hibernate mappings, needed at runtime)
        conn.prepareStatement(
            "CREATE TABLE IF NOT EXISTS shedlock(" +
            "name VARCHAR(64) NOT NULL, " +
            "lock_until TIMESTAMP NOT NULL, " +
            "locked_at TIMESTAMP NOT NULL, " +
            "locked_by VARCHAR(255) NOT NULL, " +
            "PRIMARY KEY (name))").execute();
        conn.prepareStatement("ALTER TABLE person ALTER COLUMN creator SET NULL").execute();
        conn.prepareStatement("ALTER TABLE concept ALTER COLUMN concept_id INT AUTO_INCREMENT").execute();
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

    public void generateOpenAPISpec() {

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

        String outputDir = System.getProperty("analysisOutputDir", "target/openapi-spec");
        String outputFile = System.getProperty("analysisOutputFile", "openapi-spec-output.json");

        // directory for individual schema files
        Path schemaDir = Paths.get(outputDir, "generated-schemas");
        try {
            Files.createDirectories(schemaDir);
        } catch (Exception e) {
            throw new RuntimeException("Unable to create schema output directory: " + schemaDir, e);
        }

        for (DelegatingResourceHandler<?> handler : handlers) {
            String resourceName = CustomModelResolver.getResourceName(handler);
            log.info("generating " + resourceName);
            ResolvedSchema resolvedSchema = converters.resolveAsResolvedSchema(new OpenmrsResourceAnnotatedType(handler.getClass(), handler));

            // write main schema to its own file
            Components resourceComponents = new Components();
            resourceComponents.addSchemas(resourceName, resolvedSchema.schema);

            if (resolvedSchema.referencedSchemas != null) {
                resolvedSchema.referencedSchemas.forEach(resourceComponents::addSchemas);
            }

            String singleJson = io.swagger.v3.core.util.Json.pretty(resourceComponents);
            Path outFile = schemaDir.resolve(resourceName + ".json");
            try {
                Files.write(outFile, singleJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Wrote schema file: " + outFile.toAbsolutePath());

            // In main components keep only $ref placeholders to the external file
            Schema<Object> refSchema = new Schema<>();
            String refPath = "./generated-schemas/" + resourceName + ".json#/components/schemas/" + resourceName;
            refSchema.$ref(refPath);
            components.addSchemas(resourceName, refSchema);
        }

        openAPI.components(components);

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

        String sysProp = System.getProperty("openmrs.version");
        if (sysProp != null && !sysProp.isEmpty()) {
            return sysProp;
        }
        return "2.4.x";
    }
}
