package org.openmrs.plugin.openapi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.openmrs.module.ModuleUtil;
import org.openmrs.module.webservices.rest.util.ReflectionUtil;
import org.openmrs.module.webservices.rest.web.annotation.SubClassHandler;
import org.openmrs.module.webservices.rest.web.annotation.SubResource;
import org.openmrs.module.webservices.rest.web.resource.api.SearchConfig;
import org.openmrs.module.webservices.rest.web.resource.api.SearchHandler;
import org.openmrs.module.webservices.rest.web.resource.api.SearchParameter;
import org.openmrs.module.webservices.rest.web.resource.api.SearchQuery;
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
        reportCounts("resource handlers", toClasses(handlers), handlers.size());
        return handlers;
    }

    /**
     * Binds each discovered subclass handler to the resource it extends, mirroring
     * {@code BaseDelegatingResource.init()}: the handler's {@code Superclass} type parameter names
     * a domain class, and the handler attaches to the resource whose {@code supportedClass} is that
     * class.
     * <p>
     * Subclass handlers are not resources — they have no REST name and no routes. They add a type
     * to their parent (e.g. {@code drugorder} on {@code v1/order}), so they are returned keyed by
     * parent rather than mixed into the resource list.
     *
     * @param resources the resources returned by {@link #findResourceHandlers()}
     * @return parent resource handler -> its subclass handlers, sorted by type name
     */
    public Map<DelegatingResourceHandler<?>, List<DelegatingSubclassHandler<?, ?>>> findSubclassHandlersByResource(
            List<DelegatingResourceHandler<?>> resources) throws IOException {
        Map<Class<?>, DelegatingResourceHandler<?>> bySupportedClass =
                new LinkedHashMap<Class<?>, DelegatingResourceHandler<?>>();
        for (DelegatingResourceHandler<?> resource : resources) {
            org.openmrs.module.webservices.rest.web.annotation.Resource annotation = resource.getClass()
                    .getAnnotation(org.openmrs.module.webservices.rest.web.annotation.Resource.class);
            if (annotation != null && !bySupportedClass.containsKey(annotation.supportedClass())) {
                bySupportedClass.put(annotation.supportedClass(), resource);
            }
        }

        Map<DelegatingResourceHandler<?>, List<DelegatingSubclassHandler<?, ?>>> bound =
                new LinkedHashMap<DelegatingResourceHandler<?>, List<DelegatingSubclassHandler<?, ?>>>();
        int unbound = 0;
        for (DelegatingSubclassHandler<?, ?> handler : findSubclassHandlers()) {
            Class<?> superclass = ReflectionUtil.getParameterizedTypeFromInterface(handler.getClass(),
                    DelegatingSubclassHandler.class, 0);
            DelegatingResourceHandler<?> parent = superclass == null ? null : bySupportedClass.get(superclass);
            if (parent == null) {
                // init() would throw for a null superclass and simply never attach the handler when
                // no resource supports it; either way it contributes nothing a client can reach.
                System.out.println("WARN  subclass handler " + handler.getClass().getName()
                        + " has no resource for superclass " + superclass + "; not documented");
                unbound++;
                continue;
            }
            List<DelegatingSubclassHandler<?, ?>> forParent = bound.get(parent);
            if (forParent == null) {
                forParent = new ArrayList<DelegatingSubclassHandler<?, ?>>();
                bound.put(parent, forParent);
            }
            forParent.add(handler);
        }

        int total = 0;
        for (List<DelegatingSubclassHandler<?, ?>> forParent : bound.values()) {
            java.util.Collections.sort(forParent, java.util.Comparator.comparing(h -> typeNameOf(h)));
            total += forParent.size();
        }
        System.out.println("Bound " + total + " subclass handler(s) to " + bound.size() + " resource(s)"
                + (unbound > 0 ? " (" + unbound + " unbound)" : ""));
        return bound;
    }

    /** {@code getTypeName()} can throw for a handler that never expected to run outside a request. */
    private static String typeNameOf(DelegatingSubclassHandler<?, ?> handler) {
        try {
            String name = handler.getTypeName();
            return name != null ? name : handler.getClass().getSimpleName();
        } catch (RuntimeException e) {
            return handler.getClass().getSimpleName();
        }
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
     * Discovers the {@code SearchHandler}s this module and its dependencies register, version-filtered
     * and de-duplicated the same way {@code RestServiceImpl} does at runtime.
     * <p>
     * Unlike a resource, a search handler carries its {@code supportedOpenmrsVersions} on the
     * {@code SearchConfig} its instance builds, not on an annotation — so filtering can only happen
     * <em>after</em> instantiation. {@code RestServiceImpl.addSearchHandler} does exactly this:
     * instantiate (from Spring), read {@code getSearchConfig().getSupportedOpenmrsVersions()}, keep
     * the ones matching the running core, and reject a second handler claiming the same
     * {@code (supportedResource, id)}.
     * <p>
     * The body is guarded per handler, and once overall: a search-handler API method missing on an
     * older REST yields fewer params (or an empty list), never an aborted run — mirroring the
     * route-existence guard on the search route itself.
     */
    public List<SearchHandlerInfo> findSearchHandlers() {
        List<SearchHandlerInfo> found = new ArrayList<SearchHandlerInfo>();
        try {
            List<Class<?>> classes = scan(new AssignableTypeFilter(SearchHandler.class));
            // scan() order is undefined, so sort by class name before the (resource, id) dedupe —
            // otherwise which handler wins a collision would vary between runs. See CLAUDE.md
            // "Output determinism".
            Collections.sort(classes, Comparator.comparing(Class::getName));

            Map<String, SearchHandlerInfo> byKey = new LinkedHashMap<String, SearchHandlerInfo>();
            int versionFiltered = 0;
            for (Class<?> cls : classes) {
                try {
                    Object instance = instantiate(cls);
                    if (!(instance instanceof SearchHandler)) {
                        continue;
                    }
                    SearchConfig config = ((SearchHandler) instance).getSearchConfig();
                    if (config == null) {
                        continue;
                    }
                    Set<String> versions = config.getSupportedOpenmrsVersions();
                    if (!versionMatches(versions.toArray(new String[versions.size()]))) {
                        versionFiltered++;
                        continue;
                    }
                    SearchHandlerInfo info = SearchHandlerInfo.from(cls, config);
                    // (supportedResource, id) is unique at runtime — RestServiceImpl throws on a
                    // collision. Keep the first after the name sort, so the winner is deterministic.
                    byKey.putIfAbsent(info.supportedResource + "|" + info.id, info);
                } catch (Throwable t) {
                    System.out.println("WARN  could not read SearchHandler " + cls.getName() + ": " + t);
                }
            }
            found.addAll(byKey.values());
            reportCounts("search handlers", infoClasses(found), found.size());
            if (versionFiltered > 0) {
                System.out.println("  (" + versionFiltered + " search handler(s) skipped: "
                        + "supportedOpenmrsVersions did not match core "
                        + OpenmrsConstants.OPENMRS_VERSION_SHORT + ")");
            }
        } catch (Throwable t) {
            System.out.println("WARN  search handler discovery skipped: " + t);
        }
        return found;
    }

    private static List<Class<?>> infoClasses(List<SearchHandlerInfo> infos) {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (SearchHandlerInfo info : infos) {
            classes.add(info.handlerClass);
        }
        return classes;
    }

    /**
     * Subclass handlers are Spring components at runtime
     * ({@code Context.getRegisteredComponents(DelegatingSubclassHandler.class)}). Scanning for
     * concrete implementations carrying a {@code @SubClassHandler} is the reflection equivalent,
     * and the {@code supportedOpenmrsVersions} filter mirrors {@code BaseDelegatingResource.init()}
     * — the step that actually binds a handler to its resource.
     */
    private List<DelegatingSubclassHandler<?, ?>> findSubclassHandlers() throws IOException {
        List<DelegatingSubclassHandler<?, ?>> handlers = new ArrayList<DelegatingSubclassHandler<?, ?>>();
        for (Class<?> cls : scan(new AssignableTypeFilter(DelegatingSubclassHandler.class))) {
            SubClassHandler annotation = cls.getAnnotation(SubClassHandler.class);
            if (annotation == null) {
                continue;
            }
            // Version-filtered, matching BaseDelegatingResource.init(), which is what decides
            // whether a handler is ever bound to its resource — and therefore whether clients can
            // see its type at all. RestServiceImpl.getResourceHandlers() appends every registered
            // handler unfiltered, but that list is not the API surface.
            //
            // It matters because the version variants of one handler all report the same
            // getTypeName(). DrugOrderSubclassHandler1_8, _1_10 and _1_12 are all "drugorder";
            // unfiltered, the spec published three competing shapes for one type value, two of
            // which never load.
            if (!versionMatches(annotation.supportedOpenmrsVersions())) {
                log.debug("Skipping subclass handler {}: supportedOpenmrsVersions {} does not match core {}",
                        cls.getName(), java.util.Arrays.toString(annotation.supportedOpenmrsVersions()),
                        OpenmrsConstants.OPENMRS_VERSION_SHORT);
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

    /**
     * A discovered {@code SearchHandler}, reduced to what the generator documents: its class (for
     * ownership and file naming), the {@code SearchConfig}'s id and {@code supportedResource}, and
     * the parameter names it accepts.
     * <p>
     * A config may hold several {@code SearchQuery}s — alternative ways to search — so a parameter is
     * reported as <b>required</b> only when it is required in <em>every</em> query (i.e. genuinely
     * always required). Everything else is optional, which is honest for the disjunctive case: a
     * {@code Concept} search by {@code source} OR by {@code name} OR by {@code references} makes none
     * of the three unconditionally required. Names and descriptions are sorted so the output is
     * deterministic regardless of the {@code Set} iteration order.
     */
    public static final class SearchHandlerInfo {

        public final Class<?> handlerClass;

        public final String id;

        public final String supportedResource;

        /** Params required in every {@code SearchQuery} of the config, sorted. */
        public final List<String> requiredParams;

        /** Every other distinct param across the config's queries, sorted. */
        public final List<String> optionalParams;

        /** One human-readable description per {@code SearchQuery}, sorted. */
        public final List<String> queryDescriptions;

        SearchHandlerInfo(Class<?> handlerClass, String id, String supportedResource,
                List<String> requiredParams, List<String> optionalParams,
                List<String> queryDescriptions) {
            this.handlerClass = handlerClass;
            this.id = id;
            this.supportedResource = supportedResource;
            this.requiredParams = requiredParams;
            this.optionalParams = optionalParams;
            this.queryDescriptions = queryDescriptions;
        }

        static SearchHandlerInfo from(Class<?> cls, SearchConfig config) {
            String id = config.getId();
            String supportedResource = config.getSupportedResource();
            Set<String> allParams = new TreeSet<String>();
            Set<String> alwaysRequired = null;
            Set<String> descriptions = new TreeSet<String>();
            try {
                for (SearchQuery query : config.getSearchQueries()) {
                    Set<String> required = paramNames(query.getRequiredParameters());
                    Set<String> optional = paramNames(query.getOptionalParameters());
                    allParams.addAll(required);
                    allParams.addAll(optional);
                    String description = query.getDescription();
                    if (description != null && !description.trim().isEmpty()) {
                        descriptions.add(description.trim());
                    }
                    if (alwaysRequired == null) {
                        alwaysRequired = new TreeSet<String>(required);
                    } else {
                        alwaysRequired.retainAll(required);
                    }
                }
            } catch (Throwable t) {
                // Older REST may not expose the SearchQuery param getters; still document existence.
                System.out.println("  note: could not enumerate params for " + cls.getSimpleName()
                        + " (" + t + ")");
            }
            if (alwaysRequired == null) {
                alwaysRequired = new TreeSet<String>();
            }
            List<String> requiredParams = new ArrayList<String>(alwaysRequired);
            List<String> optionalParams = new ArrayList<String>();
            for (String param : allParams) {
                if (!alwaysRequired.contains(param)) {
                    optionalParams.add(param);
                }
            }
            return new SearchHandlerInfo(cls, id, supportedResource, requiredParams, optionalParams,
                    new ArrayList<String>(descriptions));
        }

        private static Set<String> paramNames(Set<SearchParameter> params) {
            Set<String> names = new TreeSet<String>();
            if (params != null) {
                for (SearchParameter param : params) {
                    if (param != null && param.getName() != null) {
                        names.add(param.getName());
                    }
                }
            }
            return names;
        }
    }
}
