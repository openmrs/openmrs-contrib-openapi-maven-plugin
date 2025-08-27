package org.openmrs.plugin.rest.analyzer.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.api.context.Context;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.annotation.Resource;
import org.openmrs.module.webservices.rest.web.api.RestService;
import org.openmrs.module.webservices.rest.web.annotation.SubResource;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.representation.RefRepresentation;
import org.openmrs.module.webservices.rest.web.representation.DefaultRepresentation;
import org.openmrs.module.webservices.rest.web.representation.FullRepresentation;
import org.openmrs.web.test.jupiter.BaseModuleWebContextSensitiveTest;
import org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionService;
import org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionServiceImpl;
import org.openmrs.plugin.rest.analyzer.util.SchemaNameGenerator;

// OpenMRS domain model imports for delegate instances
import org.openmrs.Concept;
import org.openmrs.ConceptDatatype;
import org.openmrs.ConceptNumeric;
import org.openmrs.api.ConceptService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;

import java.io.File;
import java.util.*;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Enhanced OpenAPI 3.0 specification generator for OpenMRS REST resources.
 * Uses SchemaIntrospectionService for accurate property type discovery and Swagger-Core models.
 */
@DisplayName("OpenMRS OpenAPI Spec Generator Test")
public class OpenmrsOpenapiSpecGeneratorTest extends BaseModuleWebContextSensitiveTest {
    
    private static final Logger log = LoggerFactory.getLogger(OpenmrsOpenapiSpecGeneratorTest.class);
    
    private SchemaIntrospectionService schemaIntrospectionService;
    private Set<String> restDomainTypes = new HashSet<>();
    private List<DelegatingResourceHandler<?>> filteredHandlers = new ArrayList<>();
    private Set<String> discoveredSubResources = new HashSet<>(); // Dynamically discovered sub-resources
    
    /**
     * Creates a delegate instance for testing resource representation methods.
     * Uses the easiest approach: try to get an existing concept from test data,
     * fallback to creating a minimal test concept.
     */
    private Object createDelegateInstance(Class<?> delegateType) {
        try {
            if (delegateType.equals(Concept.class)) {
                return createConceptDelegate();
            }
            
            return delegateType.newInstance();
            
        } catch (Exception e) {
            log.warn("Could not create delegate instance for {}: {}", delegateType.getSimpleName(), e.getMessage());
            return null;
        }
    }
    
    /**
     * Creates a Concept delegate using minimal approach to satisfy isNumeric() check.
     * Only needs datatype.getName() to return "Numeric" for numeric concepts.
     */
    private Concept createConceptDelegate() {
        try {
            ConceptService conceptService = Context.getConceptService();
            
            ConceptDatatype numericDatatype = conceptService.getConceptDatatypeByName("Numeric");
            
            if (numericDatatype != null) {
                Concept numericConcept = new Concept();
                numericConcept.setUuid("test-numeric-concept-" + System.currentTimeMillis());
                numericConcept.setDatatype(numericDatatype);
                
                log.debug("Created minimal numeric concept with datatype: {}", numericDatatype.getName());
                return numericConcept;
            }
            
            ConceptDatatype textDatatype = conceptService.getConceptDatatypeByName("Text");
            if (textDatatype != null) {
                Concept concept = new Concept();
                concept.setUuid("test-concept-" + System.currentTimeMillis());
                concept.setDatatype(textDatatype);
                
                log.debug("Created minimal non-numeric concept with datatype: {}", textDatatype.getName());
                return concept;
            }
            
            Concept concept = new Concept();
            concept.setUuid("fallback-concept-" + System.currentTimeMillis());
            log.debug("Created fallback concept without datatype");
            return concept;
            
        } catch (Exception e) {
            log.warn("Error creating concept delegate: {}", e.getMessage());
            Concept concept = new Concept();
            concept.setUuid("error-fallback-concept");
            return concept;
        }
    }
    
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
        
        schemaIntrospectionService = new SchemaIntrospectionServiceImpl();
        
        List<String> scanPackages = scanPackagesStr.isEmpty() ? 
            new ArrayList<>() : 
            Arrays.asList(scanPackagesStr.split(","));
            
        buildRestDomainTypeSet(restService, scanPackages, targetModuleArtifactId);
        
        discoverSubResources(scanPackages);
        
        log.info("=== Setup Complete for {} ===", targetModuleArtifactId);
    }
    
    /**
     * Builds a set of domain types by discovering all delegate types from REST resource handlers.
     * This ensures our OpenAPI spec only references types that are actually exposed via REST.
     * Also stores the filtered handlers for use in spec generation.
     * 
     * @param restService The OpenMRS REST service
     * @param scanPackages Optional list of packages to filter resources (empty means scan all)
     * @param targetModule The name of the target module being analyzed
     */
    private void buildRestDomainTypeSet(RestService restService, List<String> scanPackages, String targetModule) {
        log.info("Building domain type set from REST resource handlers for module: {}", targetModule);
        
        Collection<DelegatingResourceHandler<?>> handlers = restService.getResourceHandlers();
        int discoveredTypes = 0;
        int filteredHandlerCount = 0;
        
        for (DelegatingResourceHandler<?> handler : handlers) {
            try {
                boolean includeHandler = false;
                
                if (!scanPackages.isEmpty()) {
                    String handlerPackage = handler.getClass().getPackage().getName();
                    boolean matchesPackage = scanPackages.stream()
                        .anyMatch(pkg -> handlerPackage.startsWith(pkg.trim()));
                    
                    if (!matchesPackage) {
                        log.debug("Skipping handler outside scan packages: {} (package: {})", 
                                handler.getClass().getSimpleName(), handlerPackage);
                        continue;
                    } else {
                        includeHandler = true;
                        log.debug("Including handler: {} (package: {})", 
                                handler.getClass().getSimpleName(), handlerPackage);
                    }
                } else {
                    includeHandler = true;
                }
                
                if (includeHandler) {
                    filteredHandlers.add(handler);
                    filteredHandlerCount++;
                }
                
                if (!(handler instanceof org.openmrs.module.webservices.rest.web.resource.api.Resource)) {
                    log.debug("Skipping handler that doesn't implement Resource interface: {}", 
                            handler.getClass().getSimpleName());
                    continue;
                }
                
                Class<?> delegateType = schemaIntrospectionService.getDelegateType(
                    (org.openmrs.module.webservices.rest.web.resource.api.Resource) handler);
                
                if (delegateType != null) {
                    String typeName = delegateType.getSimpleName();
                    restDomainTypes.add(typeName);
                    discoveredTypes++;
                    log.debug("Discovered domain type: {} from handler: {}", 
                             typeName, handler.getClass().getSimpleName());
                } else {
                    log.warn("Could not determine delegate type for handler: {}", 
                            handler.getClass().getSimpleName());
                }
            } catch (IllegalArgumentException | SecurityException e) {
                log.warn("Error discovering delegate type for handler {}: {}", 
                        handler.getClass().getSimpleName(), e.getMessage());
            }
        }
        
        log.info("Discovered {} domain types from {} filtered handlers (total available: {})", 
                discoveredTypes, filteredHandlerCount, handlers.size());
        log.debug("Domain types: {}", restDomainTypes);
    }

    /**
     * Discovers sub-resources by scanning for @SubResource annotations.
     * This builds a set of class names that are sub-resources and should be handled
     * with simplified schemas instead of full $ref links.
     */
    private void discoverSubResources(List<String> scanPackages) {
        log.info("=== Discovering Sub-Resources ===");
        
        for (DelegatingResourceHandler<?> handler : filteredHandlers) {
            Class<?> handlerClass = handler.getClass();
            
            SubResource subResourceAnnotation = handlerClass.getAnnotation(SubResource.class);
            if (subResourceAnnotation != null) {
                Class<?> supportedClass = subResourceAnnotation.supportedClass();
                String subResourceType = supportedClass.getSimpleName();
                
                discoveredSubResources.add(subResourceType);
                log.debug("Found sub-resource: {} (supported class: {}, handler: {})", 
                    subResourceType, supportedClass.getName(), handlerClass.getSimpleName());
            }
        }
        
        log.info("Discovered {} sub-resource types: {}", discoveredSubResources.size(), discoveredSubResources);
    }

    @Test
    @DisplayName("Test REST domain type discovery")
    public void testRestDomainTypesDiscovery() {
        assertFalse(restDomainTypes.isEmpty(), "Should discover at least some REST domain types");
        
        log.info("Discovered {} REST domain types: {}", restDomainTypes.size(), restDomainTypes);
        
        for (String type : restDomainTypes) {
            assertNotNull(type, "Domain type should not be null");
            assertFalse(type.trim().isEmpty(), "Domain type should not be empty");
        }
    }

    @Test
    @DisplayName("Generate OpenAPI 3.0 spec for all resources using introspection service")
    public void generateOpenApiSpecForAllResources() throws Exception {
        log.info("=== Starting OpenAPI Spec Generation ===");
        
        assertTrue(filteredHandlers.size() > 0, "Should have at least one filtered resource handler");
        assertTrue(restDomainTypes.size() > 0, "Should have discovered at least one domain type");
        
        log.info("Found {} filtered REST resource handlers and {} domain types", filteredHandlers.size(), restDomainTypes.size());
        
        OpenAPI openAPI = createBaseOpenApiStructure();
        Components components = new Components();
        Paths paths = new Paths();
        
        int processedHandlers = 0;
        int successfulHandlers = 0;
        
        for (DelegatingResourceHandler<?> handler : filteredHandlers) {
            processedHandlers++;
            if (processResourceHandler(handler, components, paths)) {
                successfulHandlers++;
            }
        }
        
        assertTrue(successfulHandlers > 0, "Should have successfully processed at least one resource handler");
        assertTrue(components.getSchemas() != null && components.getSchemas().size() > 0, 
                  "Should have generated at least one schema");
        assertTrue(paths.size() > 0, "Should have generated at least one path");
        
        openAPI.setComponents(components);
        openAPI.setPaths(paths);
        
        writeOpenApiToFile(openAPI);
        
        String outputFileName = System.getProperty("analysisOutputFile", "openapi-spec-output.json");
        log.info("OpenAPI 3.0 spec generated successfully: {} (processed {}/{} handlers)", 
                outputFileName, successfulHandlers, processedHandlers);
    }
    
    private boolean processResourceHandler(DelegatingResourceHandler<?> handler, Components components, Paths paths) {
        try {
            Resource annotation = handler.getClass().getAnnotation(Resource.class);
            if (annotation == null) {
                log.debug("Skipping handler without @Resource annotation: {}", handler.getClass().getSimpleName());
                return false;
            }
            
            String resourceName = annotation.name();
            String resourcePath = "/ws/rest/" + RestConstants.VERSION_1 + "/" + resourceName + "/{uuid}";
            
            log.info("Processing resource: {} ({})", resourceName, handler.getClass().getSimpleName());
            
            if (!(handler instanceof org.openmrs.module.webservices.rest.web.resource.api.Resource)) {
                log.debug("Skipping handler that doesn't implement Resource interface: {}", 
                        handler.getClass().getSimpleName());
                return false;
            }
            
            Class<?> delegateType = schemaIntrospectionService.getDelegateType((org.openmrs.module.webservices.rest.web.resource.api.Resource) handler);
            if (delegateType == null) {
                log.warn("Could not determine delegate type for {}", handler.getClass().getSimpleName());
                return false;
            }
            
            String resourceType = SchemaNameGenerator.extractBaseResourceName(delegateType);
            
            Map<String, String> introspectedProperties = schemaIntrospectionService.discoverResourceProperties((org.openmrs.module.webservices.rest.web.resource.api.Resource) handler);
            log.debug("Discovered {} introspected properties for {}", introspectedProperties.size(), resourceType);
            
            Map<String, String> allRepresentationProperties = new LinkedHashMap<>(introspectedProperties);
            
            Map<String, Schema<?>> representationSchemas = new LinkedHashMap<>();
            List<String> representations = Arrays.asList("default", "full", "ref");
            
            boolean isConceptResource = "Concept".equals(resourceType);
            
            for (String repName : representations) {
                Representation representation = getRepresentationByName(repName);
                if (representation == null) continue;
                
                if (isConceptResource) {
                    Schema<?> combinedSchema = generateConceptSchemaWithNumericSupport(
                        handler, representation, allRepresentationProperties, components, resourceType, repName);
                    if (combinedSchema != null) {
                        String schemaName = SchemaNameGenerator.schemaName(resourceType, repName);
                        components.addSchemas(schemaName, combinedSchema);
                        representationSchemas.put(repName, combinedSchema);
                        log.debug("Created combined concept schema for {} representation: {}", repName, schemaName);
                    }
                } else {
                    Schema<?> schema = generateRepresentationSchema(handler, representation, allRepresentationProperties, components);
                    if (schema != null) {
                        String schemaName = SchemaNameGenerator.schemaName(resourceType, repName);
                        components.addSchemas(schemaName, schema);
                        representationSchemas.put(repName, schema);
                        log.debug("Created schema for {} representation: {}", repName, schemaName);
                    }
                }
            }
            
            log.debug("Total properties (introspected + representations) for {}: {}", resourceType, allRepresentationProperties.size());
            
            Schema<?> customSchema = generateCustomRepresentationSchema(resourceType, allRepresentationProperties, components);
            if (customSchema != null) {
                String customSchemaName = SchemaNameGenerator.schemaName(resourceType, "custom");
                components.addSchemas(customSchemaName, customSchema);
                representationSchemas.put("custom", customSchema);
                log.debug("Created schema for custom representation: {}", customSchemaName);
            }
            
            if (representationSchemas.isEmpty()) {
                log.warn("No valid representations found for {}", resourceName);
                return false;
            }
            
            PathItem pathItem = createPathItem(resourceType, representationSchemas);
            paths.addPathItem(resourcePath, pathItem);
            
            log.info("Created path for {} with {} representations", resourceName, representationSchemas.size());
            return true;
            
        } catch (IllegalArgumentException | SecurityException | IllegalStateException e) {
            log.error("Error processing resource handler {}: {}", handler.getClass().getSimpleName(), e.getMessage(), e);
            return false;
        }
    }
    
    private Schema<?> generateRepresentationSchema(DelegatingResourceHandler<?> handler, 
                                                   Representation representation, 
                                                   Map<String, String> allProperties, 
                                                   Components components) {
        try {
            DelegatingResourceDescription description = null;
            
            description = handler.getRepresentationDescription(representation);
            
            if (description == null || description.getProperties() == null) {
                description = getRepresentationDescriptionWithDelegate(handler, representation);
            }
            
            if (description == null || description.getProperties() == null) {
                if (representation instanceof RefRepresentation) {
                    description = createFallbackRefDescription(handler);
                    log.info("Using REF fallback for {}", handler.getClass().getSimpleName());
                } else if (representation instanceof DefaultRepresentation) {
                    description = createFallbackDefaultDescription(handler);
                    log.info("Using DEFAULT fallback for {}", handler.getClass().getSimpleName());
                } else {
                    log.debug("No fallback available for representation: {}", representation);
                    return null;
                }
            }
            
            if (description == null || description.getProperties() == null) {
                log.debug("No description or properties available even with fallback for representation: {}", representation);
                return null;
            }
            
            ObjectSchema schema = new ObjectSchema();
            
            @SuppressWarnings("rawtypes")
            Map<String, Schema> schemaProperties = new HashMap<>();
            for (Map.Entry<String, DelegatingResourceDescription.Property> entry : description.getProperties().entrySet()) {
                String propertyName = entry.getKey();
                DelegatingResourceDescription.Property property = entry.getValue();
                
                String accurateType = schemaIntrospectionService.determineAccuratePropertyType(
                    propertyName, property, handler, allProperties);
                
                allProperties.put(propertyName, accurateType);
                
                log.debug("Property '{}' resolved to accurate type: {} (from {} representation)", 
                         propertyName, accurateType, representation.getClass().getSimpleName());
                
                String nestedRepresentation = "default";
                if (property.getRep() != null) {
                    String repClassName = property.getRep().getClass().getSimpleName().toLowerCase();
                    if (repClassName.contains("default")) {
                        nestedRepresentation = "default";
                    } else if (repClassName.contains("full")) {
                        nestedRepresentation = "full";
                    } else if (repClassName.contains("ref")) {
                        nestedRepresentation = "ref";
                    } else {
                        nestedRepresentation = "default";
                    }
                }
                
                Schema<?> propertySchema = mapToSwaggerSchema(accurateType, components, nestedRepresentation, false);
                schemaProperties.put(propertyName, propertySchema);
            }
            schema.setProperties(schemaProperties);
            
            return schema;
            
        } catch (IllegalArgumentException | SecurityException | IllegalStateException e) {
            log.warn("Could not generate schema for representation {}: {}", representation, e.getMessage());
            return null;
        }
    }
    
    /**
     * Enhanced method to get representation description using delegate-based approach.
     * This method tries to call methods like fullRepresentationDescription(delegate) 
     * that require a delegate parameter for more accurate property discovery.
     */
    private DelegatingResourceDescription getRepresentationDescriptionWithDelegate(
            DelegatingResourceHandler<?> handler, Representation representation) {
        try {
            Class<?> delegateType = schemaIntrospectionService.getDelegateType(
                (org.openmrs.module.webservices.rest.web.resource.api.Resource) handler);
            
            if (delegateType == null) {
                log.debug("Could not determine delegate type for {}", handler.getClass().getSimpleName());
                return null;
            }
            
            Object delegate = createDelegateInstance(delegateType);
            if (delegate == null) {
                log.debug("Could not create delegate instance for {}", delegateType.getSimpleName());
                return null;
            }
            
            String methodName = getRepresentationMethodName(representation);
            if (methodName != null) {
                try {
                    java.lang.reflect.Method method = handler.getClass().getDeclaredMethod(methodName, delegateType);
                    method.setAccessible(true);
                    Object result = method.invoke(handler, delegate);
                    
                    if (result instanceof DelegatingResourceDescription) {
                        log.debug("Successfully got representation description using delegate method: {} for {}", 
                                 methodName, handler.getClass().getSimpleName());
                        return (DelegatingResourceDescription) result;
                    }
                } catch (NoSuchMethodException e) {
                    log.debug("Method {} not found for {}, will use standard approach", 
                             methodName, handler.getClass().getSimpleName());
                } catch (Exception e) {
                    log.debug("Error calling {} for {}: {}", methodName, 
                             handler.getClass().getSimpleName(), e.getMessage());
                }
            }
            
            return null;
            
        } catch (Exception e) {
            log.debug("Error in delegate-based representation description: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Generates a combined schema for Concept resources that supports both regular and numeric concepts.
     * Creates separate schemas for ConceptFull/ConceptNumericFull and combines them using oneOf pattern.
     */
    private Schema<?> generateConceptSchemaWithNumericSupport(DelegatingResourceHandler<?> handler, 
                                                             Representation representation, 
                                                             Map<String, String> allProperties, 
                                                             Components components,
                                                             String resourceType,
                                                             String repName) {
        try {
            Schema<?> regularSchema = generateRepresentationSchema(handler, representation, allProperties, components);
            if (regularSchema == null) {
                return null;
            }
            
            if (representation instanceof FullRepresentation) {
                Schema<?> numericSchema = generateNumericConceptSchema(handler, representation, allProperties, components);
                if (numericSchema != null) {
                    String nonNumericSchemaName = SchemaNameGenerator.schemaName(resourceType, repName + "NonNumeric");
                    String numericSchemaName = SchemaNameGenerator.schemaName(resourceType, repName + "Numeric");
                    
                    components.addSchemas(nonNumericSchemaName, regularSchema);
                    components.addSchemas(numericSchemaName, numericSchema);
                    
                    ObjectSchema combinedSchema = new ObjectSchema();
                    @SuppressWarnings("rawtypes")
                    List<Schema> oneOfSchemas = Arrays.asList(
                        new Schema<>().$ref("#/components/schemas/" + nonNumericSchemaName),
                        new Schema<>().$ref("#/components/schemas/" + numericSchemaName)
                    );
                    
                    combinedSchema.setOneOf(oneOfSchemas);
                    combinedSchema.setDescription("Concept representation that can be either a regular concept or a numeric concept with additional properties");
                    
                    log.info("Created oneOf concept schema combining non-numeric ({}) and numeric ({}) variants", 
                            nonNumericSchemaName, numericSchemaName);
                    
                    return combinedSchema;
                }
            }
            
            return regularSchema;
            
        } catch (Exception e) {
            log.warn("Error generating concept schema with numeric support: {}", e.getMessage());
            return generateRepresentationSchema(handler, representation, allProperties, components);
        }
    }
    
    /**
     * Generates a schema specifically for numeric concepts by using a numeric concept delegate.
     * This ensures that numeric-specific properties like hiNormal, hiAbsolute, etc. are included.
     */
    private Schema<?> generateNumericConceptSchema(DelegatingResourceHandler<?> handler, 
                                                  Representation representation, 
                                                  Map<String, String> allProperties, 
                                                  Components components) {
        try {
            Concept numericConcept = createNumericConceptDelegate();
            if (numericConcept == null) {
                log.debug("Could not create numeric concept delegate");
                return null;
            }
            
            DelegatingResourceDescription description = null;
            
            if (representation instanceof FullRepresentation) {
                Method method = findRepresentationMethod(handler.getClass(), "fullRepresentationDescription", 
                                                       new Class<?>[]{Concept.class});
                if (method != null) {
                    method.setAccessible(true);
                    description = (DelegatingResourceDescription) method.invoke(handler, numericConcept);
                    log.debug("Successfully called fullRepresentationDescription with numeric concept delegate");
                }
            }
            
            if (description == null || description.getProperties() == null) {
                log.debug("Could not get numeric concept representation description");
                return null;
            }
            
            ObjectSchema schema = new ObjectSchema();
            
            @SuppressWarnings("rawtypes")
            Map<String, Schema> schemaProperties = new HashMap<>();
            for (Map.Entry<String, DelegatingResourceDescription.Property> entry : description.getProperties().entrySet()) {
                String propertyName = entry.getKey();
                DelegatingResourceDescription.Property property = entry.getValue();
                
                String accurateType = schemaIntrospectionService.determineAccuratePropertyType(
                    propertyName, property, handler, allProperties);
                
                allProperties.put(propertyName, accurateType);
                
                log.debug("Numeric concept property '{}' resolved to type: {}", propertyName, accurateType);
                
                String nestedRepresentation = "default";
                if (property.getRep() != null) {
                    String repClassName = property.getRep().getClass().getSimpleName().toLowerCase();
                    if (repClassName.contains("default")) {
                        nestedRepresentation = "default";
                    } else if (repClassName.contains("full")) {
                        nestedRepresentation = "full";
                    } else if (repClassName.contains("ref")) {
                        nestedRepresentation = "ref";
                    }
                }
                
                Schema<?> propertySchema = mapToSwaggerSchema(accurateType, components, nestedRepresentation, false);
                schemaProperties.put(propertyName, propertySchema);
            }
            schema.setProperties(schemaProperties);
            
            log.info("Generated numeric concept schema with {} properties", schemaProperties.size());
            return schema;
            
        } catch (Exception e) {
            log.warn("Error generating numeric concept schema: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Creates a numeric concept delegate for testing numeric concept properties.
     * Simplified approach focusing on minimal requirements.
     */
    private Concept createNumericConceptDelegate() {
        try {
            ConceptService conceptService = Context.getConceptService();
            
            ConceptDatatype numericDatatype = conceptService.getConceptDatatypeByName("Numeric");
            if (numericDatatype != null) {
                ConceptNumeric numericConcept = new ConceptNumeric();
                numericConcept.setUuid(UUID.randomUUID().toString());
                numericConcept.setDatatype(numericDatatype);
                
                numericConcept.setHiAbsolute(100.0);
                numericConcept.setLowAbsolute(0.0);
                numericConcept.setUnits("test");
                
                log.debug("Created minimal numeric concept delegate");
                return numericConcept;
            }
            
            log.debug("Could not create numeric concept - Numeric datatype not found");
            return null;
            
        } catch (Exception e) {
            log.warn("Error creating numeric concept delegate: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Finds a method by name and parameter types using reflection.
     */
    private Method findRepresentationMethod(Class<?> clazz, String methodName, Class<?>[] parameterTypes) {
        try {
            return clazz.getDeclaredMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return findRepresentationMethod(superClass, methodName, parameterTypes);
            }
            return null;
        }
    }
    
    /**
     * Maps representation types to the corresponding method names that take delegate parameters
     */
    private String getRepresentationMethodName(Representation representation) {
        if (representation instanceof FullRepresentation) {
            return "fullRepresentationDescription";
        } else if (representation instanceof DefaultRepresentation) {
            return "defaultRepresentationDescription";
        } else if (representation instanceof RefRepresentation) {
            return "refRepresentationDescription";
        }
        return null;
    }
    
    /**
     * Generates a schema for the custom representation, including all possible properties.
     */
    private Schema<?> generateCustomRepresentationSchema(String resourceType, Map<String, String> allProperties, Components components) {
        if (allProperties == null || allProperties.isEmpty()) {
            log.warn("No properties found for custom representation of {}", resourceType);
            return null;
        }
        ObjectSchema schema = new ObjectSchema();
        schema.setDescription("Custom representation - specify any subset of these properties in the ?v=custom:(...) query parameter");
        @SuppressWarnings("rawtypes")
        Map<String, Schema> schemaProperties = new HashMap<>();
        for (Map.Entry<String, String> entry : allProperties.entrySet()) {
            String propertyName = entry.getKey();
            String javaType = entry.getValue();
            if (javaType == null) {
                log.warn("Property '{}' not found in introspection results, using fallback", propertyName);
                javaType = "String";
            }
            Schema<?> propertySchema = mapToSwaggerSchema(javaType, components, "default", false);
            schemaProperties.put(propertyName, propertySchema);
        }
        schema.setProperties(schemaProperties);
        return schema;
    }
    
    /**
     * Type mapping registries for robust type detection.
     * Phase 1 Enhancement: Replace fragile .contains() logic with exact type matching.
     */
    private static final Set<String> STRING_TYPES = new HashSet<>(Arrays.asList(
        "string", "java.lang.string", "charsequence", "java.lang.charsequence"
    ));
    
    private static final Set<String> INTEGER_TYPES = new HashSet<>(Arrays.asList(
        "int", "integer", "java.lang.integer", "short", "java.lang.short", "byte", "java.lang.byte"
    ));
    
    private static final Set<String> LONG_TYPES = new HashSet<>(Arrays.asList(
        "long", "java.lang.long"
    ));
    
    private static final Set<String> NUMBER_TYPES = new HashSet<>(Arrays.asList(
        "double", "java.lang.double", "float", "java.lang.float", "number", 
        "bigdecimal", "java.math.bigdecimal", "biginteger", "java.math.biginteger"
    ));
    
    private static final Set<String> BOOLEAN_TYPES = new HashSet<>(Arrays.asList(
        "boolean", "java.lang.boolean"
    ));
    
    private static final Set<String> DATE_TIME_TYPES = new HashSet<>(Arrays.asList(
        "date", "java.util.date", "datetime", "timestamp", "time", "localdate", 
        "localdatetime", "localtime", "zoneddatetime", "instant", "calendar",
        "java.time.localdate", "java.time.localdatetime", "java.time.localtime",
        "java.time.zoneddatetime", "java.time.instant", "java.util.calendar"
    ));
    
    /**
     * Phase 1 Enhancement: Type mapping registry for configurable schema creation.
     * Maps type categories to schema factory functions for extensibility.
     */
    private static final Map<String, String> TYPE_CATEGORY_MAPPING = new HashMap<>();
    
    static {
        // Initialize type category mappings for better error reporting and debugging
        for (String type : STRING_TYPES) {
            TYPE_CATEGORY_MAPPING.put(type, "STRING");
        }
        for (String type : INTEGER_TYPES) {
            TYPE_CATEGORY_MAPPING.put(type, "INTEGER");
        }
        for (String type : LONG_TYPES) {
            TYPE_CATEGORY_MAPPING.put(type, "LONG");
        }
        for (String type : NUMBER_TYPES) {
            TYPE_CATEGORY_MAPPING.put(type, "NUMBER");
        }
        for (String type : BOOLEAN_TYPES) {
            TYPE_CATEGORY_MAPPING.put(type, "BOOLEAN");
        }
        for (String type : DATE_TIME_TYPES) {
            TYPE_CATEGORY_MAPPING.put(type, "DATE_TIME");
        }
    }

    // Phase 1 Enhancement: Made package-private for testing
    Schema<?> mapToSwaggerSchema(String javaType, Components components, String representationHint, boolean isArrayItem) {
        if (javaType == null) return new StringSchema();
        
        if (isCollectionType(javaType)) {
            String itemType = extractGenericType(javaType);
            Schema<?> itemSchema = mapToSwaggerSchema(itemType, components, representationHint, true); // Mark as array item
            return new ArraySchema().items(itemSchema);
        }
        
        String cleanType = SchemaNameGenerator.cleanTypeString(javaType);
        String normalizedType = normalizeTypeName(cleanType);
        
        // Phase 1 Enhancement: Robust type detection using predefined registries
        if (STRING_TYPES.contains(normalizedType)) {
            log.debug("Mapped type '{}' -> StringSchema (category: STRING)", javaType);
            return new StringSchema();
        } else if (INTEGER_TYPES.contains(normalizedType)) {
            log.debug("Mapped type '{}' -> IntegerSchema (category: INTEGER)", javaType);
            return new IntegerSchema();
        } else if (LONG_TYPES.contains(normalizedType)) {
            log.debug("Mapped type '{}' -> IntegerSchema[int64] (category: LONG)", javaType);
            return new IntegerSchema().format("int64");
        } else if (NUMBER_TYPES.contains(normalizedType)) {
            log.debug("Mapped type '{}' -> NumberSchema (category: NUMBER)", javaType);
            return new NumberSchema();
        } else if (BOOLEAN_TYPES.contains(normalizedType)) {
            log.debug("Mapped type '{}' -> BooleanSchema (category: BOOLEAN)", javaType);
            return new BooleanSchema();
        } else if (DATE_TIME_TYPES.contains(normalizedType)) {
            log.debug("Mapped type '{}' -> StringSchema[date-time] (category: DATE_TIME)", javaType);
            return new StringSchema().format("date-time");
        } else if (isKnownNestedType(cleanType)) {
            log.debug("Mapped type '{}' -> NestedType (sub-resource)", javaType);
            if (isArrayItem) {
                return createNestedTypeDescription(cleanType, representationHint, isArrayItem);
            } else {
                StringSchema schema = new StringSchema();
                schema.setDescription("Sub-resource: " + cleanType);
                return schema;
            }
        } else if (isOpenMRSDomainType(cleanType)) {
            String refName = SchemaNameGenerator.schemaNameFromPropertyType(javaType, representationHint);
            log.debug("Mapped type '{}' -> $ref[{}] (OpenMRS domain type)", javaType, refName);
            return new Schema<>().$ref("#/components/schemas/" + refName);
        } else if (normalizedType.startsWith("object (from")) {
            log.debug("Mapped type '{}' -> ObjectSchema (runtime-determined)", javaType);
            return new ObjectSchema().description("Type determined from " + javaType);
        } else {
            // Enhanced fallback with better error handling and type category reporting
            String typeCategory = TYPE_CATEGORY_MAPPING.get(normalizedType);
            if (typeCategory != null) {
                log.warn("Type '{}' recognized as {} but fell through to fallback - possible mapping issue", javaType, typeCategory);
            } else {
                log.debug("Unknown type '{}' (normalized: '{}') mapped to ObjectSchema fallback", javaType, normalizedType);
            }
            return createFallbackSchema(javaType, normalizedType);
        }
    }
    
    /**
     * Phase 1 Enhancement: Normalize type names for consistent matching.
     * Handles various type name formats and edge cases.
     */
    private String normalizeTypeName(String typeName) {
        if (typeName == null) return "";
        
        String normalized = typeName.toLowerCase().trim();
        
        // Handle array notation: String[] -> string
        if (normalized.endsWith("[]")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        
        // Handle generic parameters: List<String> is handled elsewhere, but clean simple cases
        int genericStart = normalized.indexOf('<');
        if (genericStart > 0) {
            normalized = normalized.substring(0, genericStart);
        }
        
        // Remove common prefixes for cleaner matching
        if (normalized.startsWith("java.lang.")) {
            String withoutPrefix = normalized.substring("java.lang.".length());
            // Only use shortened form for common types to avoid conflicts
            Set<String> commonTypes = new HashSet<>(Arrays.asList(
                "string", "integer", "long", "double", "float", "boolean", "short", "byte"
            ));
            if (commonTypes.contains(withoutPrefix)) {
                normalized = withoutPrefix;
            }
        }
        
        return normalized;
    }
    
    /**
     * Phase 1 Enhancement: Creates a fallback schema for unknown types with enhanced diagnostics.
     * Provides better error reporting and potential recovery strategies.
     */
    private Schema<?> createFallbackSchema(String originalType, String normalizedType) {
        ObjectSchema schema = new ObjectSchema();
        
        // Provide detailed description for debugging
        StringBuilder description = new StringBuilder("Complex type: ").append(originalType);
        
        if (!originalType.equals(normalizedType)) {
            description.append(" (normalized: ").append(normalizedType).append(")");
        }
        
        // Add hints for common issues
        if (normalizedType.contains("list") || normalizedType.contains("set") || normalizedType.contains("collection")) {
            description.append(" - Note: This appears to be a collection type but wasn't detected by isCollectionType()");
        } else if (normalizedType.contains(".")) {
            description.append(" - Note: This appears to be a fully qualified class name");
        } else if (normalizedType.length() > 50) {
            description.append(" - Note: This type name is unusually long, possibly indicating a complex generic type");
        }
        
        schema.setDescription(description.toString());
        
        // Add example to help with debugging
        schema.setExample("Value of type " + originalType);
        
        return schema;
    }
    
    private PathItem createPathItem(String resourceType, Map<String, Schema<?>> representationSchemas) {
        PathItem pathItem = new PathItem();
        
        Operation getOperation = new Operation();
        getOperation.setSummary("Get a " + resourceType + " by UUID");
        getOperation.setDescription("Retrieve a " + resourceType + " resource in the requested representation");
        
        List<Parameter> parameters = new ArrayList<>();
        
        Parameter uuidParam = new Parameter();
        uuidParam.setName("uuid");
        uuidParam.setIn("path");
        uuidParam.setRequired(true);
        uuidParam.setDescription("The UUID of the " + resourceType);
        uuidParam.setSchema(new StringSchema());
        parameters.add(uuidParam);
        
        Parameter vParam = new Parameter();
        vParam.setName("v");
        vParam.setIn("query");
        vParam.setRequired(false);
        vParam.setDescription("The representation to return. Allowed values: 'default', 'full', 'ref', or a custom representation string. For custom, use e.g. custom:(uuid,display,name,person:(uuid,display))");
        
        StringSchema vSchema = new StringSchema();
        vSchema.addEnumItem("default");
        vSchema.addEnumItem("full");
        vSchema.addEnumItem("ref");
        vParam.setSchema(vSchema);
        
        Map<String, io.swagger.v3.oas.models.examples.Example> vExamples = new LinkedHashMap<>();
        io.swagger.v3.oas.models.examples.Example exCustomBasic = new io.swagger.v3.oas.models.examples.Example();
        exCustomBasic.setSummary("Custom (basic)");
        exCustomBasic.setValue("custom:(uuid,display,name)");
        vExamples.put("customBasic", exCustomBasic);
        io.swagger.v3.oas.models.examples.Example exCustomNested = new io.swagger.v3.oas.models.examples.Example();
        exCustomNested.setSummary("Custom (nested)");
        exCustomNested.setValue("custom:(uuid,display,person:(uuid,display))");
        vExamples.put("customNested", exCustomNested);
        vParam.setExamples(vExamples);
        parameters.add(vParam);
        
        getOperation.setParameters(parameters);
        
        ApiResponses responses = new ApiResponses();
        
        ApiResponse response200 = new ApiResponse();
        response200.setDescription("Successful response");
        
        Content content = new Content();
        MediaType mediaType = new MediaType();
        
        ObjectSchema responseSchema = new ObjectSchema();
        @SuppressWarnings("rawtypes")
        List<Schema> oneOfSchemas = new ArrayList<>();
        for (Map.Entry<String, Schema<?>> entry : representationSchemas.entrySet()) {
            String repName = entry.getKey();
            String schemaName = SchemaNameGenerator.schemaName(resourceType, repName);
            Schema<?> refSchema = new Schema<>().$ref("#/components/schemas/" + schemaName);
            oneOfSchemas.add(refSchema);
        }
        responseSchema.setOneOf(oneOfSchemas);

        mediaType.setSchema(responseSchema);
        content.addMediaType("application/json", mediaType);
        response200.setContent(content);
        
        responses.addApiResponse("200", response200);
        
        ApiResponse response404 = new ApiResponse();
        response404.setDescription("Resource with given UUID doesn't exist");
        responses.addApiResponse("404", response404);
        
        ApiResponse response401 = new ApiResponse();
        response401.setDescription("User not logged in");
        responses.addApiResponse("401", response401);
        
        ApiResponse response400 = new ApiResponse();
        response400.setDescription("Bad request - invalid parameters");
        responses.addApiResponse("400", response400);
        
        getOperation.setResponses(responses);
        
        pathItem.setGet(getOperation);
        return pathItem;
    }
    
    private Representation getRepresentationByName(String rep) {
        switch (rep.toLowerCase()) {
            case "default": return Representation.DEFAULT;
            case "full": return Representation.FULL;
            case "ref": return Representation.REF;
            default: return null;
        }
    }
    
    private boolean isCollectionType(String javaType) {
        return javaType.startsWith("List<") || javaType.startsWith("Set<") || javaType.startsWith("Collection<");
    }
    
    /**
     * Extracts the generic type from a parameterized type string.
     * Handles complex generic types like Map<String, List<Patient>>.
     */
    private String extractGenericType(String parameterizedType) {
        if (parameterizedType == null || !parameterizedType.contains("<")) {
            return "String";
        }
        
        try {
            int start = parameterizedType.indexOf("<") + 1;
            int end = parameterizedType.lastIndexOf(">");
            
            if (start <= 0 || end <= start) {
                return "String";
            }
            
            String genericType = parameterizedType.substring(start, end);
            
            int bracketCount = 0;
            int commaIndex = -1;
            
            for (int i = 0; i < genericType.length(); i++) {
                char c = genericType.charAt(i);
                if (c == '<') {
                    bracketCount++;
                } else if (c == '>') {
                    bracketCount--;
                } else if (c == ',' && bracketCount == 0) {
                    commaIndex = i;
                    break;
                }
            }
            
            if (commaIndex > 0) {
                genericType = genericType.substring(0, commaIndex).trim();
            }
            
            return genericType;
        } catch (StringIndexOutOfBoundsException | IllegalArgumentException e) {
            log.warn("Error extracting generic type from '{}': {}", parameterizedType, e.getMessage());
            return "String";
        }
    }
    
    /**
     * Determines if a Java type should be treated as an OpenMRS domain type for $ref generation.
     * Enhanced to handle cleaned type strings and better domain type detection.
     */
    private boolean isOpenMRSDomainType(String javaType) {
        if (javaType == null) {
            return false;
        }
        
        String cleanType = SchemaNameGenerator.cleanTypeString(javaType);
        
        boolean isRestDomainType = restDomainTypes.contains(cleanType);
        
        if (isRestDomainType) {
            log.debug("Treating '{}' as domain type (discovered via REST)", cleanType);
            return true;
        }
        
        Set<String> knownDomainTypes = new HashSet<>(Arrays.asList(
            "Person", "Patient", "User", "Provider", "Encounter", "Visit", "Obs", "Order",
            "Concept", "Drug", "Location", "Program", "Role", "Privilege", "Form", "Field"
        ));
        
        if (knownDomainTypes.contains(cleanType)) {
            log.debug("Treating '{}' as known OpenMRS domain type", cleanType);
            return true;
        }
        
        log.debug("Treating '{}' as generic object (not a recognized domain type)", cleanType);
        return false;
    }
    
    private OpenAPI createBaseOpenApiStructure() {
        OpenAPI openAPI = new OpenAPI();
        
        Info info = new Info();
        info.setTitle("OpenMRS REST API");
        info.setVersion(detectOpenmrsVersion());
        info.setDescription("Generated OpenAPI 3.0 specification for OpenMRS REST resources using introspection service");
        
        openAPI.setInfo(info);
        return openAPI;
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
    
    /**
     * Checks if a type is a discovered sub-resource that should be handled with simplified schemas.
     */
    private boolean isKnownNestedType(String typeName) {
        if (typeName == null) return false;
        
        return discoveredSubResources.contains(typeName);
    }
    
    /**
     * Creates a descriptive string schema for sub-resource types in arrays only.
     * This method should ONLY be called for array items, not standalone properties.
     */
    private Schema<?> createNestedTypeDescription(String typeName, String representationHint, boolean isArrayItem) {
        if (!isArrayItem) {
            throw new IllegalArgumentException("createNestedTypeDescription should only be called for array items");
        }
        
        StringSchema schema = new StringSchema();
        schema.setDescription("Sub-resource: " + typeName);
        schema.setExample(typeName + " data as string");
        
        return schema;
    }
    
    private void writeOpenApiToFile(OpenAPI openAPI) throws Exception {
        String outputDirPath = System.getProperty("analysisOutputDir", "target");
        String outputFileName = System.getProperty("analysisOutputFile", "openapi-spec-output.json");
        
        log.info("Output configuration - Dir: {}, File: {}", outputDirPath, outputFileName);
        
        File outputDir = new File(outputDirPath);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        File outputFile = new File(outputDir, outputFileName);
        
        String json = io.swagger.v3.core.util.Json.pretty(openAPI);
        
        try (java.io.FileWriter writer = new java.io.FileWriter(outputFile)) {
            writer.write(json);
        }
        
        log.info("OpenAPI spec written to: {}", outputFile.getAbsolutePath());
        assertTrue(outputFile.exists(), "Output file should be created");
        assertTrue(outputFile.length() > 0, "Output file should not be empty");
    }
    
    /**
     * Creates a fallback REF representation description when getRepresentationDescription() returns null.
     * Clones the logic from DataDelegatingCrudResource.asRef() and MetadataDelegatingCrudResource.convertToRef().
     */
    private DelegatingResourceDescription createFallbackRefDescription(DelegatingResourceHandler<?> handler) {
        DelegatingResourceDescription description = new DelegatingResourceDescription();
        description.addProperty("uuid");
        description.addProperty("display");
        
        Class<?> delegateType = schemaIntrospectionService.getDelegateType(
            (org.openmrs.module.webservices.rest.web.resource.api.Resource) handler);
        
        if (delegateType != null) {
            try {
                delegateType.getMethod("isVoided");
                description.addProperty("voided");
                log.debug("Added 'voided' property for OpenmrsData type: {}", delegateType.getSimpleName());
            } catch (NoSuchMethodException e) {
                try {
                    delegateType.getMethod("isRetired");
                    description.addProperty("retired");
                    log.debug("Added 'retired' property for OpenmrsMetadata type: {}", delegateType.getSimpleName());
                } catch (NoSuchMethodException e2) {
                    log.debug("No voided/retired property for type: {}", delegateType.getSimpleName());
                }
            }
        }
        
        description.addSelfLink();
        return description;
    }
    
    /**
     * Creates a fallback DEFAULT representation description when getRepresentationDescription() returns null.
     * Clones the logic from DataDelegatingCrudResource.asDefaultRep() and MetadataDelegatingCrudResource.asDefaultRep().
     */
    private DelegatingResourceDescription createFallbackDefaultDescription(DelegatingResourceHandler<?> handler) {
        DelegatingResourceDescription description = new DelegatingResourceDescription();
        description.addProperty("uuid");
        description.addProperty("display");
        
        Class<?> delegateType = schemaIntrospectionService.getDelegateType(
            (org.openmrs.module.webservices.rest.web.resource.api.Resource) handler);
        
        if (delegateType != null) {
            try {
                delegateType.getMethod("isVoided");
                description.addProperty("voided");
                log.debug("Added 'voided' property for OpenmrsData DEFAULT: {}", delegateType.getSimpleName());
            } catch (NoSuchMethodException e) {
                try {
                    delegateType.getMethod("isRetired");
                    description.addProperty("name");
                    description.addProperty("description");
                    description.addProperty("retired");
                    log.debug("Added metadata properties for OpenmrsMetadata DEFAULT: {}", delegateType.getSimpleName());
                } catch (NoSuchMethodException e2) {
                    log.debug("Using generic DEFAULT properties for type: {}", delegateType.getSimpleName());
                }
            }
        }
        
        description.addSelfLink();
        description.addLink("full", ".?v=" + RestConstants.REPRESENTATION_FULL);
        return description;
    }
} 