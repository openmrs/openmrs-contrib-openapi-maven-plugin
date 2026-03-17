package org.openmrs.plugin.rest.analyzer.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.openmrs.web.test.jupiter.BaseModuleWebContextSensitiveTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;

/**
 * Enhanced OpenAPI 3.0 specification generator for OpenMRS REST resources.
 * Uses SchemaIntrospectionService for accurate property type discovery and Swagger-Core models.
 */
@DisplayName("OpenMRS OpenAPI Spec Generator Test")
public class CustomModelResolverSpecGenerator extends BaseModuleWebContextSensitiveTest {
    
    private static final Logger log = LoggerFactory.getLogger(CustomModelResolverSpecGenerator.class);
    
    @BeforeEach
    public void setup() throws Exception {
        log.info("=== Setting up OpenAPI Spec Generator Test ===");
        
        String targetModuleGroupId = System.getProperty("target.module.groupId", "unknown");
        String targetModuleArtifactId = System.getProperty("target.module.artifactId", "unknown");
        String targetModuleVersion = System.getProperty("target.module.version", "unknown");
        String scanPackagesStr = System.getProperty("target.module.packages", "");
        
        log.info("Target module: {}:{}:{}", targetModuleGroupId, targetModuleArtifactId, targetModuleVersion);
        log.info("Scan packages: {}", scanPackagesStr);
        
        RestService restService = Context.getService(RestService.class);
        assertNotNull(restService, "RestService should be available");
        restService.initialize();
        
        Context.getAdministrationService().saveGlobalProperty(
            new GlobalProperty(RestConstants.SWAGGER_QUIET_DOCS_GLOBAL_PROPERTY_NAME, "true"));
        Context.flushSession();
    }

    @Test
    public void getResourceMetadata() {
        
        // FILE: Searchable and Listable should have getAvailableRepresentations() function
        // FILE: Listable resources should not throw ResourceDoesNotSupportOperationException when calling getAll()
        System.out.println("Starting restService...");
        RestService restService = Context.getService(RestService.class);
        restService.initialize();
        List<DelegatingResourceHandler<?>> handlers = restService.getResourceHandlers();
        // sort the handlers alphabetically
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
    
    @Test
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
        // sort the handlers alphabetically
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
            // Create a schema $ref: "generated-schemas/Name.json#/components/schemas/Name"
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
