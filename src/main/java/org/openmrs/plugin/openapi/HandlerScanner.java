package org.openmrs.plugin.openapi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openmrs.module.ModuleUtil;
import org.openmrs.module.webservices.rest.web.annotation.SubClassHandler;
import org.openmrs.module.webservices.rest.web.annotation.SubResource;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingSubclassHandler;
import org.openmrs.util.OpenmrsConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;

/**
 * Discovers REST resource handlers and Spring controllers by scanning the classpath, without
 * starting a Spring context or the OpenMRS {@code RestService}.
 * <p>
 * This follows {@code RestServiceImpl.initializeResources()}:
 * <ol>
 * <li>scan {@code classpath*:org/openmrs/&#42;&#42;/*.class} for concrete implementations of
 * {@code Resource} (the same pattern {@code OpenmrsClassScanner} uses)</li>
 * <li>read {@code @Resource} / {@code @SubResource} for name, supportedClass and order</li>
 * <li>drop anything whose {@code supportedOpenmrsVersions} does not match the running core</li>
 * <li>instantiate, keeping the lowest-order resource per name</li>
 * <li>append {@code DelegatingSubclassHandler}s, which the REST module obtains from Spring via
 * {@code Context.getRegisteredComponents(...)}</li>
 * </ol>
 * <p>
 * It deliberately returns one handler per REST <em>name</em>, which is a superset of what
 * {@code RestServiceImpl.getResourceHandlers()} returns — see the comment in
 * {@link #findResourceHandlers()}.
 */
public class HandlerScanner {

    private static final Logger log = LoggerFactory.getLogger(HandlerScanner.class);

    private static final String CLASS_PATTERN = "classpath*:org/openmrs/**/*.class";

    /** How many times we will stub a missing service and retry one instantiation. */
    private static final int MAX_SERVICE_STUB_RETRIES = 5;

    private final ClassLoader classLoader;

    /** This module's own build output and sibling artifacts; used only to report counts. */
    private final java.util.Set<String> ownedLocations;

    private final PathMatchingResourcePatternResolver resolver;

    private final MetadataReaderFactory metadataReaderFactory;

    public HandlerScanner(ClassLoader classLoader, java.util.Set<String> ownedLocations) {
        this.classLoader = classLoader;
        this.ownedLocations = ownedLocations;
        this.resolver = new PathMatchingResourcePatternResolver(classLoader);
        this.metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
    }

    /**
     * Returns the resource handlers this module and its dependencies expose, in the same order
     * and with the same de-duplication the REST module applies at runtime.
     */
    public List<DelegatingResourceHandler<?>> findResourceHandlers() throws IOException {
        assertOpenmrsVersionKnown();

        List<Class<?>> resourceClasses = scan(new AssignableTypeFilter(
                org.openmrs.module.webservices.rest.web.resource.api.Resource.class));
        System.out.println("Scanned classpath: " + resourceClasses.size() + " concrete Resource implementations");

        // Resources are de-duplicated by REST name, lowest order wins — the same rule
        // RestServiceImpl.isResourceToBeAdded() applies when filling resourceDefinitionsByNames.
        //
        // Deliberately NOT keyed by supportedClass. RestServiceImpl keeps a second map for that
        // (resourcesBySupportedClasses), and getResourceHandlers() iterates it — but a map keyed by
        // supported class holds only one entry per class, so when two resources describe the same
        // domain object one of them disappears. openmrs-module-queue does exactly that:
        // QueueEntryResource (v1/queue-entry) and QueueEntrySubResource (v1/queue/entry) both
        // declare supportedClass = QueueEntry.class, and mirroring getResourceHandlers() silently
        // dropped whichever was scanned second.
        //
        // Both are live endpoints — MainResourceController and MainSubResourceController each
        // resolve them through getResourceByName(), i.e. through resourceDefinitionsByNames. The
        // API surface is the set of names, so that is what gets documented.
        Map<String, ResourceDefinition> byName = new LinkedHashMap<String, ResourceDefinition>();

        for (Class<?> resourceClass : resourceClasses) {
            ResourceMetadata metadata = getResourceMetadata(resourceClass);
            if (metadata == null) {
                continue;
            }
            ResourceDefinition existing = byName.get(metadata.name);
            if (existing != null && existing.order < metadata.order) {
                continue;
            }
            if (existing != null && existing.order == metadata.order) {
                log.warn("Two resources named '{}' share order {}; keeping {}",
                        metadata.name, metadata.order, existing.instance.getClass().getName());
                continue;
            }
            Object instance = instantiate(resourceClass);
            if (instance == null) {
                continue;
            }
            byName.put(metadata.name, new ResourceDefinition(instance, metadata.order));
        }

        List<DelegatingResourceHandler<?>> handlers = new ArrayList<DelegatingResourceHandler<?>>();
        for (ResourceDefinition definition : byName.values()) {
            if (definition.instance instanceof DelegatingResourceHandler) {
                handlers.add((DelegatingResourceHandler<?>) definition.instance);
            }
        }
        for (DelegatingSubclassHandler<?, ?> subclassHandler : findSubclassHandlers()) {
            handlers.add((DelegatingResourceHandler<?>) subclassHandler);
        }

        reportCounts("resource handlers", toClasses(handlers), handlers.size());
        return handlers;
    }

    /**
     * Finds {@code @Controller}-annotated classes. The REST module gets these from Spring via
     * {@code getBeansWithAnnotation(Controller.class)}; because {@code ControllerDocumenter} only
     * ever reads {@code bean.getClass()}, scanning for the annotation is equivalent.
     * Meta-annotations are considered so {@code @RestController} is included.
     */
    public List<Class<?>> findControllers() throws IOException {
        List<Class<?>> controllers = scan(new AnnotationTypeFilter(
                org.springframework.stereotype.Controller.class, true, true));
        reportCounts("@Controller classes", controllers, controllers.size());
        return controllers;
    }

    /**
     * Subclass handlers are Spring components at runtime
     * ({@code Context.getRegisteredComponents(DelegatingSubclassHandler.class)}). Scanning for
     * concrete implementations carrying a version-compatible {@code @SubClassHandler} is the
     * reflection equivalent, and applies the same version filter as
     * {@code BaseDelegatingResource.init()}.
     */
    private List<DelegatingSubclassHandler<?, ?>> findSubclassHandlers() throws IOException {
        List<DelegatingSubclassHandler<?, ?>> handlers = new ArrayList<DelegatingSubclassHandler<?, ?>>();
        for (Class<?> cls : scan(new AssignableTypeFilter(DelegatingSubclassHandler.class))) {
            // Deliberately no version filter: RestServiceImpl.getResourceHandlers() appends every
            // registered DelegatingSubclassHandler unfiltered (the version check in
            // BaseDelegatingResource.init() applies when binding handlers to a resource, not here).
            if (cls.getAnnotation(SubClassHandler.class) == null) {
                continue;
            }
            Object instance = instantiate(cls);
            if (instance instanceof DelegatingSubclassHandler) {
                handlers.add((DelegatingSubclassHandler<?, ?>) instance);
            }
        }
        return handlers;
    }

    /**
     * Reports how many discovered classes belong to this module. Only module-owned classes are
     * written to disk; the rest are dependencies, kept because the schema resolver needs them to
     * name cross-module {@code $ref} targets.
     */
    private void reportCounts(String label, List<Class<?>> classes, int total) {
        if (ownedLocations == null || ownedLocations.isEmpty()) {
            System.out.println("Discovered " + total + " " + label);
            return;
        }
        int owned = ModuleOwnership.countOwned(classes, ownedLocations);
        if (owned == total) {
            System.out.println("Discovered " + owned + " " + label + " in this module");
            return;
        }
        System.out.println("Discovered " + owned + " " + label + " in this module ("
                + total + " on the classpath; the rest belong to dependencies and are resolved "
                + "only so cross-module $refs can be named)");
    }

    private static List<Class<?>> toClasses(List<DelegatingResourceHandler<?>> handlers) {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (DelegatingResourceHandler<?> handler : handlers) {
            classes.add(handler.getClass());
        }
        return classes;
    }

    /** Runs one classpath sweep and returns the concrete classes matching the given filter. */
    private List<Class<?>> scan(TypeFilter filter) throws IOException {
        List<Class<?>> matches = new ArrayList<Class<?>>();
        Resource[] resources = resolver.getResources(CLASS_PATTERN);
        for (Resource resource : resources) {
            try {
                MetadataReader reader = metadataReaderFactory.getMetadataReader(resource);
                if (!filter.match(reader, metadataReaderFactory)
                        || !reader.getClassMetadata().isConcrete()) {
                    continue;
                }
                String className = reader.getClassMetadata().getClassName();
                try {
                    // Load through the generator's classloader, not Class.forName's caller
                    // loader — the isolated classloader is what holds the module's classes.
                    matches.add(classLoader.loadClass(className));
                } catch (Throwable t) {
                    log.debug("Could not load {}: {}", className, t.toString());
                }
            } catch (Throwable t) {
                log.debug("Could not read {}: {}", resource, t.toString());
            }
        }
        return matches;
    }

    /**
     * @return the resource's name/supportedClass/order, or null when it carries no usable
     *         annotation or targets a different OpenMRS version
     */
    private ResourceMetadata getResourceMetadata(Class<?> resourceClass) {
        org.openmrs.module.webservices.rest.web.annotation.Resource annotation = resourceClass
                .getAnnotation(org.openmrs.module.webservices.rest.web.annotation.Resource.class);
        if (annotation == null) {
            SubResource subResource = resourceClass.getAnnotation(SubResource.class);
            if (subResource == null || !versionMatches(subResource.supportedOpenmrsVersions())) {
                return null;
            }
            org.openmrs.module.webservices.rest.web.annotation.Resource parent = subResource.parent()
                    .getAnnotation(org.openmrs.module.webservices.rest.web.annotation.Resource.class);
            if (parent == null) {
                return null;
            }
            return new ResourceMetadata(parent.name() + "/" + subResource.path(),
                    subResource.supportedClass(), subResource.order());
        }
        if (!versionMatches(annotation.supportedOpenmrsVersions())) {
            return null;
        }
        return new ResourceMetadata(annotation.name(), annotation.supportedClass(), annotation.order());
    }

    private boolean versionMatches(String[] versions) {
        if (versions == null || versions.length == 0) {
            return false;
        }
        for (String version : versions) {
            if (ModuleUtil.matchRequiredVersions(OpenmrsConstants.OPENMRS_VERSION_SHORT, version)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Instantiates a resource, registering a no-op stub and retrying when its constructor reaches
     * for a service that no longer exists (e.g. {@code ConditionResource2_2} initialises a field
     * with {@code Context.getConditionService()}). Retries are bounded because each one can only
     * be justified by a new missing service.
     */
    private Object instantiate(Class<?> cls) {
        for (int attempt = 0; attempt < MAX_SERVICE_STUB_RETRIES; attempt++) {
            try {
                return cls.newInstance();
            } catch (Throwable t) {
                Class<?> missing = StubRuntime.findMissingServiceClass(t);
                if (missing == null || !StubRuntime.registerNoOpService(missing)) {
                    System.out.println("WARN  could not instantiate " + cls.getName() + ": " + t);
                    return null;
                }
                System.out.println("  stubbed " + missing.getName() + " so " + cls.getSimpleName() + " could be instantiated");
            }
        }
        System.out.println("WARN  could not instantiate " + cls.getName() + " after " + MAX_SERVICE_STUB_RETRIES + " service stubs");
        return null;
    }

    /**
     * {@code supportedOpenmrsVersions} is matched against {@code OPENMRS_VERSION_SHORT}, which is
     * read from openmrs-api's manifest. If it is blank every match fails and the scan silently
     * returns nothing, so fail loudly instead.
     */
    private void assertOpenmrsVersionKnown() {
        String version = OpenmrsConstants.OPENMRS_VERSION_SHORT;
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalStateException(
                    "OpenmrsConstants.OPENMRS_VERSION_SHORT is blank — every resource's "
                            + "supportedOpenmrsVersions check would fail and no resources would be "
                            + "discovered. Ensure openmrs-api is on the classpath as a JAR.");
        }
        System.out.println("Detected openmrs-core version: " + version);
    }

    private static final class ResourceMetadata {

        private final String name;

        private final Class<?> supportedClass;

        private final int order;

        ResourceMetadata(String name, Class<?> supportedClass, int order) {
            this.name = name;
            this.supportedClass = supportedClass;
            this.order = order;
        }
    }

    private static final class ResourceDefinition {

        private final Object instance;

        private final int order;

        ResourceDefinition(Object instance, int order) {
            this.instance = instance;
            this.order = order;
        }
    }
}
