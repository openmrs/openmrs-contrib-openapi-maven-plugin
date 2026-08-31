package org.openmrs.plugin.openapi;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.openmrs.module.webservices.rest.web.representation.Representation;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates per-controller OpenAPI documentation from {@code @RequestMapping} annotations, for the
 * {@code @Controller} classes {@link HandlerScanner} found on the classpath (except the two
 * resource-dispatch controllers).
 */
public class ControllerDocumenter {

    /** Name given to the path variable standing in for a `**` version wildcard. */
    private static final String VERSION_VARIABLE = "version";

    private static final Logger log = LoggerFactory.getLogger(ControllerDocumenter.class);

    private static final Set<String> EXCLUDED_CONTROLLERS = new HashSet<>(Arrays.asList(
        "MainResourceController", "MainSubResourceController"
    ));

    /** Schema names already registered as REST resource $ref placeholders. */
    private Map<String, Schema<?>> resourceSchemas;

    /**
     * The API tag of every controller actually documented, sorted. The caller declares these in the
     * document's top-level {@code tags}, which is where a tag a operation uses is supposed to be
     * described.
     */
    private final java.util.SortedSet<String> documentedApiTags = new java.util.TreeSet<>();

    /** @see #documentedApiTags */
    public java.util.SortedSet<String> getDocumentedApiTags() {
        return documentedApiTags;
    }

    /**
     * Operations whose id came from an explicit {@code @Operation(operationId = ...)}. Identity,
     * not equality: two operations can legitimately be equal in content.
     */
    private final Set<Operation> explicitlyNamed =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Operation, Boolean>());

    /**
     * Generates per-controller JSON files under controllersDir, merges controller DTO schemas into
     * mainComponents, and returns a Paths map for inclusion in the main openapi.json.
     */
    public Paths document(List<Class<?>> controllerClasses, Path controllersDir, Components mainComponents,
            String ownedLocationsSemicolon) throws IOException {
        Paths allPaths = new Paths();
        com.fasterxml.jackson.databind.ObjectMapper mapper = io.swagger.v3.core.util.Json31.mapper();

        // Capture the resource $ref placeholders so resolveType() can check them before
        // falling through to Jackson bean introspection.
        @SuppressWarnings("unchecked")
        Map<String, Schema<?>> existingSchemas = (Map<String, Schema<?>>) (Map<?, ?>) mainComponents.getSchemas();
        resourceSchemas = existingSchemas != null ? existingSchemas : new LinkedHashMap<String, Schema<?>>();

        java.util.Set<String> ownedLocations = (ownedLocationsSemicolon != null && !ownedLocationsSemicolon.isEmpty())
                ? new java.util.HashSet<String>(java.util.Arrays.asList(ownedLocationsSemicolon.split(";")))
                : null;

        // Controllers arrive as classes from a classpath scan rather than as Spring beans; only
        // bean.getClass() was ever used here, so the two are equivalent (and no proxy unwrapping
        // is needed for scanned classes).
        List<Class<?>> beans = new ArrayList<>(controllerClasses);
        beans.sort((a, b) -> a.getSimpleName().compareTo(b.getSimpleName()));

        int documented = 0;
        List<String> skipped = new ArrayList<>();
        List<DocumentedController> documentedControllers = new ArrayList<>();
        explicitlyNamed.clear();
        documentedApiTags.clear();

        for (Class<?> cls : beans) {
            if (ownedLocations != null && !isModuleOwned(cls, ownedLocations)) continue;
            if (EXCLUDED_CONTROLLERS.contains(cls.getSimpleName())) {
                skipped.add(cls.getSimpleName() + " (resource-dispatch controller)");
                continue;
            }

            String basePath = classBasePath(cls);
            if (basePath == null) {
                skipped.add(cls.getSimpleName() + " (no class-level @RequestMapping path)");
                continue;
            }

            // Fresh converters per controller to avoid schema accumulation across controllers.
            // The resource-ref converter runs first: any type already registered as a REST
            // resource gets a $ref instead of full bean introspection, which prevents Jackson
            // from ever recursing into their transitive dependencies.
            // ModelConverters' own default resolver uses Json.mapper() — the 3.0 mapper, with
            // Jackson's unsorted bean introspection — so controller schemas built from POJOs
            // reordered between runs (emrapi's DiagnosisController moved ConceptDatatype's
            // time/date/boolean properties every time). addConverter inserts at the head of the
            // chain, so this one wins.
            ModelConverters converters = new ModelConverters();
            converters.addConverter(new io.swagger.v3.core.jackson.ModelResolver(
                OpenMRSResourceModelResolver.withStablePropertyOrder(io.swagger.v3.core.util.Json31.mapper())));
            converters.addConverter(new io.swagger.v3.core.converter.ModelConverter() {
                @Override
                public Schema<?> resolve(AnnotatedType type,
                        io.swagger.v3.core.converter.ModelConverterContext context,
                        java.util.Iterator<io.swagger.v3.core.converter.ModelConverter> chain) {
                    // The REF representation, not the bare resource name. The bare name holds the
                    // resource's anyOf over [Get, Create, Update], so a controller POJO field typed
                    // VisitType was documented as "a visit type in any of its read and write
                    // shapes" — including two request-body shapes a response field can never hold.
                    // REF is what the API actually returns for a nested resource, and it is what
                    // the resource path emits for the same field.
                    //
                    // The lookup goes to the resource registry rather than to this module's own
                    // schemas, because the registry is keyed by every resource on the classpath —
                    // dependencies included — while the module's schemas hold only what the module
                    // itself documents. Asking the module was the bug: emrapi documents no core
                    // resources, so nothing matched, and every core domain class a controller DTO
                    // touched fell through to Jackson bean introspection. The core domain graph is
                    // densely cyclic (Concept <-> ConceptAnswer <-> Drug, Person <-> User <->
                    // Role), so EmrEncounterController's three endpoints pulled in 84 schemas with
                    // 58 distinct reference cycles — enough to hang Swagger UI outright when the
                    // GET operation is expanded. Against the registry the same endpoints need 15.
                    //
                    // Going through the registry also fixes the names. resourceNameFor() mirrors
                    // RestServiceImpl.getResourceBySupportedClass(), so a class documented by a
                    // differently-named resource resolves correctly — Allergy is documented by
                    // PatientAllergyResource2_0, and the old simple-name guess emitted
                    // AllergyGet_ref, a name nothing defines.
                    String schemaName = OpenMRSResourceModelResolver
                            .componentSchemaNameForResource(type.getType(), Representation.REF);
                    if (schemaName != null) {
                        return Schemas.ref("#/components/schemas/" + schemaName);
                    }
                    return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
                }
            });

            Map<String, Schema<?>> controllerSchemas = new LinkedHashMap<>();
            Paths controllerPaths = new Paths();

            // getDeclaredMethods() has no defined order and varies between JVM versions. That
            // matters when two handlers collide on the same path and verb (e.g.
            // ConceptReferenceController1_9 has a form-consuming and a JSON-consuming POST on
            // /conceptreferences) — whichever is processed last wins. Sort so the winner is at
            // least stable across runs and JVMs.
            Method[] declaredMethods = cls.getDeclaredMethods();
            Arrays.sort(declaredMethods,
                    Comparator.comparing(Method::getName).thenComparing(Method::toString));
            for (Method method : declaredMethods) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                processMethod(method, basePath, apiTagFor(cls.getSimpleName()),
                        controllerPaths, controllerSchemas, converters);
            }

            if (controllerPaths.isEmpty()) {
                skipped.add(cls.getSimpleName() + " (no mapped request methods)");
                continue;
            }
            documented++;
            DocumentedController documentedController =
                    new DocumentedController(cls, controllerPaths, controllerSchemas);
            documentedControllers.add(documentedController);
            documentedApiTags.add(documentedController.tag);
        }

        // Only here is every controller in the module visible at once, which is what deciding a
        // unique operationId needs. Nothing may be serialised before this runs.
        assignUniqueOperationIds(documentedControllers);

        for (DocumentedController controller : documentedControllers) {
            // Titled before serialising: the per-controller file is written below, ahead of the
            // openapi.json pass in OpenApiSpecGenerator, and without this the same schema would
            // carry a title in openapi.json but not in the controller's own file.
            Schemas.titleAll(controller.schemas);

            // Merge controller DTO schemas into the main openapi.json components.
            // Never overwrite existing resource $ref placeholders.
            for (Map.Entry<String, Schema<?>> e : controller.schemas.entrySet()) {
                if (!resourceSchemas.containsKey(e.getKey())) {
                    mainComponents.addSchemas(e.getKey(), e.getValue());
                }
            }

            controller.paths.forEach(allPaths::addPathItem);

            // Write per-controller file: { "components": { "schemas": {...} }, "paths": {...} }
            ObjectNode root = mapper.createObjectNode();
            if (!controller.schemas.isEmpty()) {
                ObjectNode schemasNode = mapper.createObjectNode();
                for (Map.Entry<String, Schema<?>> e : controller.schemas.entrySet()) {
                    schemasNode.set(e.getKey(), mapper.valueToTree(e.getValue()));
                }
                ObjectNode componentsNode = mapper.createObjectNode();
                componentsNode.set("schemas", schemasNode);
                root.set("components", componentsNode);
            }
            root.set("paths", mapper.valueToTree(controller.paths));

            Path outFile = controllersDir.resolve(controller.cls.getSimpleName() + ".json");
            Files.write(outFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            System.out.println("Wrote controller file: " + outFile.toAbsolutePath());
        }

        System.out.println("Documented " + documented + " controllers from this module.");
        if (!skipped.isEmpty()) {
            System.out.println("Skipped " + skipped.size() + " module controller(s):");
            for (String reason : skipped) {
                System.out.println("  " + reason);
            }
        }

        return allPaths;
    }

    /** One controller's output, held until operationIds can be made unique across the module. */
    private static class DocumentedController {
        final Class<?> cls;
        final String tag;
        final Paths paths;
        final Map<String, Schema<?>> schemas;

        DocumentedController(Class<?> cls, Paths paths, Map<String, Schema<?>> schemas) {
            this.cls = cls;
            this.tag = apiTagFor(cls.getSimpleName());
            this.paths = paths;
            this.schemas = schemas;
        }

        /**
         * Every operation with the verb it is mapped to, in an order that does not vary between
         * runs.
         * <p>
         * A list rather than a map keyed by the operation: swagger's {@code Operation} implements
         * {@code equals} by content, so the two operations a {@code method = {GET, POST}} mapping
         * produces are equal until they are given different ids — and keying by them silently
         * collapsed the pair that most needed distinguishing.
         */
        List<OperationSite> operationSites() {
            List<OperationSite> sites = new ArrayList<>();
            for (String path : new java.util.TreeSet<>(paths.keySet())) {
                // readOperationsMap() enumerates the verbs in a fixed order of its own.
                for (Map.Entry<PathItem.HttpMethod, Operation> e
                        : paths.get(path).readOperationsMap().entrySet()) {
                    sites.add(new OperationSite(e.getValue(), e.getKey()));
                }
            }
            return sites;
        }
    }

    /** One operation as mapped: the operation itself and the verb it answers. */
    private static class OperationSite {
        final Operation operation;
        final PathItem.HttpMethod verb;

        OperationSite(Operation operation, PathItem.HttpMethod verb) {
            this.operation = operation;
            this.verb = verb;
        }
    }

    /**
     * The tag a controller's operations carry, which is also what a generator names its API class
     * after. Only a trailing REST-version suffix is removed:
     * <p>
     * {@code ActiveVisitController} → {@code ActiveVisitController},
     * {@code SessionController1_9} → {@code SessionController}.
     * <p>
     * The {@code Controller} suffix is deliberately <b>kept</b>. Stripping it (an earlier version
     * did) gave shorter class names — {@code DiagnosisApi} rather than {@code DiagnosisControllerApi}
     * — but let a controller collide with a <em>resource</em> of the same name, and then the two
     * shared a docs group and would share a generated client class: {@code HL7MessageController1_8}
     * and the {@code HL7Message} resource both reduced to {@code HL7Message}, as did
     * {@code FormResourceController1_9} and the {@code form/resource} sub-resource. Keeping the
     * suffix makes that collision structurally impossible, since no resource name ends in
     * {@code Controller}, and it tells a reader which half of the API a class came from — which
     * matters more once resources are generated too.
     * <p>
     * The version suffix goes because it is not part of the endpoint's identity: it names the
     * REST-version-specific implementation, and carrying it through would both look wrong
     * ({@code SessionController1_9} generates {@code SessionController19Api} — the underscore is
     * dropped) and rename a published client export the day a module adds a newer version of the
     * same controller.
     */
    static String apiTagFor(String controllerClassName) {
        return CONTROLLER_VERSION_SUFFIX.matcher(controllerClassName).replaceFirst("");
    }

    /**
     * A trailing REST-version marker, but only where it follows the word {@code Controller} — so
     * {@code Legacy1xRestController} keeps the digits that are part of its name, and a class not
     * ending in {@code Controller} is left alone entirely.
     */
    private static final java.util.regex.Pattern CONTROLLER_VERSION_SUFFIX =
            java.util.regex.Pattern.compile("(?<=Controller)\\d+(?:_\\d+)*$");

    /**
     * Makes every controller operationId unique across the module's document.
     * <p>
     * An operationId must be unique among all operations in an OpenAPI document — openapi-generator
     * rejects a repeat outright — and it becomes the exported function name in a generated client.
     * Three passes, each only touching names the previous one left ambiguous, so the common case
     * keeps the plain Java method name:
     * <ol>
     *   <li><b>verb suffix</b>, for a method mapped to several HTTP verbs. Unavoidable: those are
     *       genuinely different operations produced by one method.</li>
     *   <li><b>controller prefix</b>, for a name two controllers both claim
     *       ({@code AppointmentController} and {@code AppointmentsController} share eight).</li>
     *   <li><b>numeric suffix</b>, a safety net so the document is valid whatever happens.</li>
     * </ol>
     * Passes 2 and 3 rename an operation that was previously unique the moment a second claimant
     * appears, which for a published client is a renamed export. That is the price of readable
     * names in the common case; {@code @Operation(operationId = "...")} pins a name against it and
     * is never rewritten here. Every adjustment is printed so it is visible rather than silent.
     */
    private void assignUniqueOperationIds(List<DocumentedController> controllers) {
        List<String> adjustments = new ArrayList<>();

        // Pass 1 — one Java method, several verbs.
        for (DocumentedController controller : controllers) {
            Map<String, List<OperationSite>> byId = new LinkedHashMap<>();
            for (OperationSite site : controller.operationSites()) {
                byId.computeIfAbsent(site.operation.getOperationId(), k -> new ArrayList<>())
                        .add(site);
            }
            for (Map.Entry<String, List<OperationSite>> e : byId.entrySet()) {
                if (e.getValue().size() < 2) {
                    continue;
                }
                for (OperationSite site : e.getValue()) {
                    if (explicitlyNamed.contains(site.operation)) {
                        continue;
                    }
                    site.operation.setOperationId(
                            e.getKey() + capitalize(site.verb.name().toLowerCase()));
                    adjustments.add(controller.cls.getSimpleName() + ": " + e.getKey()
                            + " -> " + site.operation.getOperationId() + " (mapped to several verbs)");
                }
            }
        }

        // Pass 2 — one name, several controllers. Computed before any rename so that both sides of
        // a collision are qualified, rather than only whichever is visited second.
        Map<String, Set<String>> claimants = new LinkedHashMap<>();
        for (DocumentedController controller : controllers) {
            for (OperationSite site : controller.operationSites()) {
                claimants.computeIfAbsent(site.operation.getOperationId(),
                        k -> new java.util.TreeSet<>()).add(controller.cls.getSimpleName());
            }
        }
        for (DocumentedController controller : controllers) {
            for (OperationSite site : controller.operationSites()) {
                Operation op = site.operation;
                String id = op.getOperationId();
                if (explicitlyNamed.contains(op) || claimants.get(id).size() < 2) {
                    continue;
                }
                op.setOperationId(uncapitalize(controller.tag) + capitalize(id));
                adjustments.add(controller.cls.getSimpleName() + ": " + id + " -> "
                        + op.getOperationId() + " (also used by " + String.join(", ",
                        without(claimants.get(id), controller.cls.getSimpleName())) + ")");
            }
        }

        // Pass 3 — whatever is still duplicated, including two explicit ids that collide.
        Set<String> used = new java.util.HashSet<>();
        for (DocumentedController controller : controllers) {
            for (OperationSite site : controller.operationSites()) {
                Operation op = site.operation;
                String id = op.getOperationId();
                if (used.add(id)) {
                    continue;
                }
                String candidate;
                for (int n = 2; !used.add(candidate = id + n); n++) {
                    // find the first free suffix
                }
                op.setOperationId(candidate);
                adjustments.add(controller.cls.getSimpleName() + ": " + id + " -> " + candidate
                        + " (still ambiguous; annotate one of them with"
                        + " @Operation(operationId = \"...\") to choose the names yourself —"
                        + " the module must depend on io.swagger.core.v3:swagger-annotations"
                        + " (provided) for the annotation to be visible)");
            }
        }

        if (!adjustments.isEmpty()) {
            System.out.println("Disambiguated " + adjustments.size() + " operationId(s):");
            for (String adjustment : adjustments) {
                System.out.println("  " + adjustment);
            }
        }
    }

    private static List<String> without(Set<String> names, String excluded) {
        List<String> rest = new ArrayList<>(names);
        rest.remove(excluded);
        return rest;
    }

    private static final java.util.regex.Pattern PATH_VARIABLE =
            java.util.regex.Pattern.compile("\\{([^}/]+)\\}");

    /**
     * Declares every {@code {name}} in the path that no {@code @PathVariable} accounted for.
     * <p>
     * Spring tolerates a mapping whose template variable no parameter binds —
     * {@code RecurringAppointmentsController.editAppointment} maps {@code PUT /{appointmentUuid}}
     * and takes only a request body, reading the uuid out of that body instead. OpenAPI does not:
     * every template variable must be declared, and openapi-generator refuses the document
     * outright ("Declared path parameter appointmentUuid needs to be defined as a path parameter").
     * <p>
     * The value is still part of the URL a client has to build, so declaring it as a required
     * string is both what the specification demands and what the endpoint actually takes. Runs
     * after the {@code version} wildcard is handled, so that one keeps its richer declaration.
     */
    private static void declareRemainingPathVariables(Operation op, String fullPath) {
        Set<String> declared = new HashSet<>();
        if (op.getParameters() != null) {
            for (Parameter existing : op.getParameters()) {
                if ("path".equals(existing.getIn())) {
                    declared.add(existing.getName());
                }
            }
        }
        java.util.regex.Matcher m = PATH_VARIABLE.matcher(fullPath);
        while (m.find()) {
            String name = m.group(1);
            if (declared.add(name)) {
                op.addParametersItem(new Parameter().name(name).in("path").required(true)
                        .description("Present in the route but not bound by the handler method; "
                                + "the value is still part of the URL.")
                        .schema(Schemas.of("string")));
            }
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String uncapitalize(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private void processMethod(Method method, String basePath, String apiTag,
            Paths paths, Map<String, Schema<?>> schemas, ModelConverters converters) {

        String subPath = "";
        List<RequestMethod> httpMethods = new ArrayList<>();

        RequestMapping rm = method.getAnnotation(RequestMapping.class);
        if (rm != null) {
            subPath = firstVal(rm.value(), rm.path());
            httpMethods.addAll(Arrays.asList(rm.method()));
        }

        GetMapping gm = method.getAnnotation(GetMapping.class);
        if (gm != null) {
            subPath = firstVal(gm.value(), gm.path());
            httpMethods.add(RequestMethod.GET);
        }

        PostMapping pm = method.getAnnotation(PostMapping.class);
        if (pm != null) {
            subPath = firstVal(pm.value(), pm.path());
            httpMethods.add(RequestMethod.POST);
        }

        PutMapping ptm = method.getAnnotation(PutMapping.class);
        if (ptm != null) {
            subPath = firstVal(ptm.value(), ptm.path());
            httpMethods.add(RequestMethod.PUT);
        }

        DeleteMapping dm = method.getAnnotation(DeleteMapping.class);
        if (dm != null) {
            subPath = firstVal(dm.value(), dm.path());
            httpMethods.add(RequestMethod.DELETE);
        }

        PatchMapping pam = method.getAnnotation(PatchMapping.class);
        if (pam != null) {
            subPath = firstVal(pam.value(), pam.path());
            httpMethods.add(RequestMethod.PATCH);
        }

        if (httpMethods.isEmpty()) return;

        String fullPath = normalizeRestPath(joinPath(basePath, subPath));

        PathItem pathItem = paths.get(fullPath);
        if (pathItem == null) {
            pathItem = new PathItem();
        }
        for (RequestMethod hm : httpMethods) {
            // A fresh Operation per verb. A method mapped to several verbs
            // (@RequestMapping(method = {GET, POST}), which queue's Legacy1xRestController uses
            // four times) used to share one instance across all of them, so the two could never
            // carry the distinct operationIds the OpenAPI specification requires.
            Operation op = buildOperation(method, apiTag, schemas, converters);
            if (fullPath.contains("{" + VERSION_VARIABLE + "}")) {
                // No controller method declares a @PathVariable for it — the segment comes from
                // the wildcard in the mapping, not from the signature — but an OpenAPI path
                // template variable must still be declared or the document is invalid.
                op.addParametersItem(versionParam());
            }
            declareRemainingPathVariables(op, fullPath);
            switch (hm) {
                case GET:    pathItem.get(op);    break;
                case POST:   pathItem.post(op);   break;
                case PUT:    pathItem.put(op);    break;
                case DELETE: pathItem.delete(op); break;
                case PATCH:  pathItem.patch(op);  break;
                default:     break;
            }
        }
        paths.addPathItem(fullPath, pathItem);
    }

    /**
     * The name a generated client gives this operation.
     * <p>
     * The Java method name is used verbatim: it is already camelCase, it is what a module author
     * would guess, and — unlike a name synthesised from the path — it does not change when the
     * route changes. Generators that receive no {@code operationId} invent one from the path
     * ({@code wsRestVersionEmrapiPatientdiagnosesGet}), which is both unreadable and unstable.
     * <p>
     * Deliberately <em>not</em> qualified with the controller name. An operation id is an exported
     * function name in the generated client, so a rule that qualifies only on collision would
     * rename an existing export the day someone adds a colliding method to an unrelated
     * controller — a breaking change to a published package, caused by an unrelated commit.
     * Collisions are instead rejected outright by {@code GenerateTypeScriptMojo}, which sees every
     * controller in the module at once; the escape hatch is an explicit
     * {@code @Operation(operationId = "...")} on one of the two methods.
     */
    private static boolean hasExplicitOperationId(Method method) {
        io.swagger.v3.oas.annotations.Operation declared = declaredOperation(method);
        return declared != null && !declared.operationId().isEmpty();
    }

    private static String operationIdFor(Method method) {
        String declared = declaredOperationId(method);
        return declared != null ? declared : method.getName();
    }

    /**
     * The {@code @Operation(operationId = "…")} a method declares, or null.
     * <p>
     * Shared with {@link DispatchControllerNames}, which needs the same rule for the two REST
     * dispatch controllers: the annotation is how the REST module names an operation whose Java
     * method name is not the name it wants published.
     */
    static String declaredOperationId(Method method) {
        io.swagger.v3.oas.annotations.Operation declared = declaredOperation(method);
        return declared != null && !declared.operationId().isEmpty()
            ? declared.operationId() : null;
    }

    /** The {@code @Operation(summary = ...)} text, which becomes the doc comment on the client method. */
    private static String declaredSummary(Method method) {
        io.swagger.v3.oas.annotations.Operation declared = declaredOperation(method);
        if (declared != null && !declared.summary().isEmpty()) {
            return declared.summary();
        }
        return null;
    }

    /**
     * Returns the method's {@code @Operation} annotation, or null.
     * <p>
     * Controller classes are loaded by the <em>module</em> classloader, which is the parent of the
     * one this class was loaded by — so it can only resolve the annotation type when the module
     * itself depends on swagger-annotations. Where it does not, the annotation is simply absent
     * and lookup returns null rather than failing, which is the behaviour wanted: the annotation
     * is an optional override, not a requirement.
     */
    private static io.swagger.v3.oas.annotations.Operation declaredOperation(Method method) {
        try {
            return method.getAnnotation(io.swagger.v3.oas.annotations.Operation.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Operation buildOperation(Method method, String apiTag, Map<String, Schema<?>> schemas,
            ModelConverters converters) {
        // One tag: the controller this operation belongs to. IF A SECOND IS EVER ADDED IT MUST GO
        // AFTER THIS ONE.
        //
        // Tags are a semantic channel — categories a reader or a docs UI groups by — but every code
        // generator also overloads a tag to mean "the class this method goes on" and emits the
        // operation once per tag, and a tag-grouping docs UI likewise renders it once per tag. The
        // spec used to carry a blanket "Controllers" tag alongside this one, and Swagger UI duly
        // listed every controller endpoint twice: once under its controller, once under
        // "Controllers". Nothing needed that tag — the dev server tells controllers from resources
        // by which directory the file is in, not by tag — so it is gone.
        //
        // generate-typescript still runs with openapi-generator's KEEP_ONLY_FIRST_TAG_IN_OPERATION
        // normalizer, which discards all but the first tag from the model it generates from (the
        // document on disk is untouched). That is a no-op while there is only one tag, and is the
        // reason a future semantic tag can be added without duplicating methods across API classes
        // — provided it goes after this one.
        Operation op = new Operation().addTagsItem(apiTag);
        op.setOperationId(operationIdFor(method));
        if (hasExplicitOperationId(method)) {
            // Author-chosen, so uniquification leaves it alone.
            explicitlyNamed.add(op);
        }
        String summary = declaredSummary(method);
        if (summary != null) {
            op.setSummary(summary);
        }
        List<Parameter> params = new ArrayList<>();
        RequestBody requestBody = null;

        for (java.lang.reflect.Parameter param : method.getParameters()) {
            PathVariable pv = param.getAnnotation(PathVariable.class);
            if (pv != null) {
                String name = firstNonEmpty(pv.value(), pv.name(), param.getName());
                params.add(new Parameter().name(name).in("path").required(true)
                    .schema(scalarSchema(param.getType())));
                continue;
            }

            RequestParam rp = param.getAnnotation(RequestParam.class);
            if (rp != null) {
                String name = firstNonEmpty(rp.value(), rp.name(), param.getName());
                Schema<?> schema = scalarSchema(param.getType());
                if (!ValueConstants.DEFAULT_NONE.equals(rp.defaultValue())) {
                    schema.setDefault(rp.defaultValue());
                }
                params.add(new Parameter().name(name).in("query").required(rp.required()).schema(schema));
                continue;
            }

            org.springframework.web.bind.annotation.RequestBody rb =
                param.getAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
            if (rb != null) {
                Schema<?> bodySchema = resolveType(param.getParameterizedType(), schemas, converters);
                if (bodySchema != null) {
                    requestBody = new RequestBody().required(rb.required())
                        .content(jsonContent(bodySchema));
                }
            }
        }

        if (!params.isEmpty()) op.setParameters(params);
        if (requestBody != null) op.setRequestBody(requestBody);

        Type returnType = unwrapResponseEntity(method.getGenericReturnType());
        ApiResponses responses = new ApiResponses();
        if (isVoidType(returnType)) {
            responses.addApiResponse("204", new ApiResponse().description("No Content"));
        } else {
            Schema<?> schema = resolveType(returnType, schemas, converters);
            ApiResponse response = new ApiResponse().description("OK");
            if (schema != null) response.content(jsonContent(schema));
            responses.addApiResponse("200", response);
        }
        op.setResponses(responses);

        return op;
    }

    /**
     * Resolves a Java type to a Swagger Schema, registering any referenced POJO schemas.
     * For plain types (String, primitives, Map, List) returns a direct schema.
     * For POJOs delegates to ModelConverters which uses Jackson introspection.
     */
    private Schema<?> resolveType(Type type, Map<String, Schema<?>> schemas, ModelConverters converters) {
        if (type == null || type == void.class || type == Void.class) return null;

        if (type instanceof Class) {
            Class<?> cls = (Class<?>) type;
            if (cls == String.class) return Schemas.<String>of("string");
            if (cls == byte[].class) return Schemas.of("string").format("binary");
            if (cls == boolean.class || cls == Boolean.class) return Schemas.<Boolean>of("boolean");
            if (Number.class.isAssignableFrom(cls) || (cls.isPrimitive() && cls != boolean.class))
                return Schemas.of("number");
            if (cls == Object.class) return Schemas.object();
            if (Map.class.isAssignableFrom(cls)) return Schemas.object();
            // MultipartFile — check by name to avoid hard dependency on servlet API version
            if ("MultipartFile".equals(cls.getSimpleName())) return Schemas.of("string").format("binary");
        }

        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Class<?> raw = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(raw) || Iterable.class.isAssignableFrom(raw)) {
                Type itemType = pt.getActualTypeArguments()[0];
                Schema<?> items = resolveType(itemType, schemas, converters);
                return Schemas.array().items(items != null ? items : Schemas.object());
            }
            if (Map.class.isAssignableFrom(raw)) return Schemas.object();
        }

        // POJO: use ModelConverters (Jackson-based introspection)
        try {
            ResolvedSchema resolved = converters.resolveAsResolvedSchema(new AnnotatedType(type));
            if (resolved == null) return Schemas.object();
            if (resolved.referencedSchemas != null) {
                for (Map.Entry<?, ?> e : resolved.referencedSchemas.entrySet()) {
                    String name = (String) e.getKey();
                    // Skip types that already have a REST resource $ref placeholder — their
                    // schema lives in a resources/*.json file and refs to them will resolve there.
                    if (!resourceSchemas.containsKey(name)) {
                        @SuppressWarnings("unchecked")
                        Schema<?> s = (Schema<?>) e.getValue();
                        schemas.put(name, s);
                    }
                }
            }
            // Jackson returns the inline object schema as resolved.schema, not a $ref.
            // If the primary type ended up registered by name in referencedSchemas, emit a
            // $ref so the requestBody/response points to the named component instead of inlining.
            Class<?> rawType = com.fasterxml.jackson.databind.type.TypeFactory.rawClass(type);
            if (rawType != null && resolved.referencedSchemas != null
                    && resolved.referencedSchemas.containsKey(rawType.getSimpleName())) {
                return Schemas.ref("#/components/schemas/" + rawType.getSimpleName());
            }
            return resolved.schema != null ? resolved.schema : Schemas.object();
        } catch (Exception e) {
            log.debug("Could not resolve schema for {}: {}", type, e.getMessage());
            return Schemas.object();
        }
    }

    // ---- static helpers ----

    /**
     * The REST API version segment a controller matches with {@code **}. Only {@code v1} exists
     * today, so it is documented as the default rather than as a free string.
     */
    private static Parameter versionParam() {
        Schema<String> schema = Schemas.<String> of("string")._default("v1");
        schema.addEnumItemObject("v1");
        return new Parameter().name(VERSION_VARIABLE).in("path").required(true)
            .description("REST API version segment; the controller matches any value, only v1 is in use")
            .schema(schema);
    }

    private static String classBasePath(Class<?> cls) {
        RequestMapping rm = cls.getAnnotation(RequestMapping.class);
        if (rm == null) return "";  // no class-level mapping; method annotations carry the full path
        String[] vals = rm.value().length > 0 ? rm.value() : rm.path();
        return vals.length > 0 ? vals[0] : "";
    }

    private static boolean isModuleOwned(Class<?> cls, java.util.Set<String> ownedLocations) {
        return ModuleOwnership.isOwned(cls, ownedLocations);
    }

    /**
     * Maps a controller's Spring mapping onto the URL a client actually calls: OpenMRS mounts the
     * REST servlet at {@code /ws}, so {@code "/rest/v1/visitconfiguration"} is served at
     * {@code "/ws/rest/v1/visitconfiguration"}.
     * <p>
     * A controller that means to serve every REST version writes the version segment as Spring's
     * {@code **} wildcard — {@code "/rest/&#42;&#42;/emrapi/doseFormGroups"} in
     * {@code openmrs-module-emrapi}. That is modelled as a path variable, giving
     * {@code "/ws/rest/{version}/emrapi/doseFormGroups"}. In practice the only value that reaches
     * these controllers is {@code v1}, but a variable keeps the documented path a real callable URL
     * and keeps the wildcard out of the spec — an OpenAPI path template cannot contain {@code *}.
     * <p>
     * This used to drop the wildcard segment along with everything before it, which silently ate
     * {@code /rest} too and produced {@code "/ws/emrapi/doseFormGroups"} — a path that does not
     * exist.
     */
    private static String normalizeRestPath(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        normalized = normalized.replace("/**/", "/{" + VERSION_VARIABLE + "}/");
        if (normalized.endsWith("/**")) {
            normalized = normalized.substring(0, normalized.length() - 3) + "/{" + VERSION_VARIABLE + "}";
        }
        // Only the REST servlet lives under /ws. A controller mapped "module/emrapi/foo.form" is an
        // ordinary module page served from the application root, and prefixing it produced
        // "/ws/module/emrapi/foo.form" — a URL that does not exist. Across the verified modules
        // every class-level mapping starts with either "rest" or "module", so the first segment is
        // a reliable discriminator.
        return normalized.startsWith("/rest/") || normalized.equals("/rest")
            ? "/ws" + normalized : normalized;
    }

    private static String joinPath(String base, String sub) {
        if (sub == null || sub.isEmpty()) return base;
        return sub.startsWith("/") ? base + sub : base + "/" + sub;
    }

    private static String firstVal(String[] primary, String[] secondary) {
        if (primary.length > 0) return primary[0];
        if (secondary.length > 0) return secondary[0];
        return "";
    }

    private static String firstNonEmpty(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isEmpty()) return s;
        }
        return "arg";
    }

    private static Schema<?> scalarSchema(Class<?> type) {
        if (type == int.class || type == Integer.class)
            return Schemas.<Integer>of("integer").format("int32");
        if (type == long.class || type == Long.class)
            return Schemas.<Long>of("integer").format("int64");
        if (type == boolean.class || type == Boolean.class)
            return Schemas.<Boolean>of("boolean");
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class)
            return Schemas.<Double>of("number");
        return Schemas.<String>of("string");
    }

    private static boolean isVoidType(Type type) {
        return type == void.class || type == Void.class;
    }

    private static Type unwrapResponseEntity(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            if (ResponseEntity.class.equals(pt.getRawType())) {
                Type[] args = pt.getActualTypeArguments();
                return args.length > 0 ? args[0] : Object.class;
            }
        }
        if (type == ResponseEntity.class) return Object.class;
        return type;
    }

    private static Content jsonContent(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema(schema));
    }
}
