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
                    Class<?> raw = com.fasterxml.jackson.databind.type.TypeFactory.rawClass(type.getType());
                    if (raw != null) {
                        // Prefer the REF representation over the bare resource name. The bare name
                        // holds the resource's anyOf over [Get, Create, Update], so a controller
                        // POJO field typed VisitType was documented as "a visit type in any of its
                        // read and write shapes" — including two request-body shapes a response
                        // field can never hold. REF is what the API actually returns for a nested
                        // resource, and it is what the resource path emits for the same field.
                        String ref = raw.getSimpleName() + "Get_ref";
                        if (resourceSchemas.containsKey(ref)) {
                            return Schemas.ref("#/components/schemas/" + ref);
                        }
                        if (resourceSchemas.containsKey(raw.getSimpleName())) {
                            return Schemas.ref("#/components/schemas/" + raw.getSimpleName());
                        }
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
                processMethod(method, basePath, controllerPaths, controllerSchemas, converters);
            }

            if (controllerPaths.isEmpty()) {
                skipped.add(cls.getSimpleName() + " (no mapped request methods)");
                continue;
            }
            documented++;

            // Merge controller DTO schemas into the main openapi.json components.
            // Never overwrite existing resource $ref placeholders.
            for (Map.Entry<String, Schema<?>> e : controllerSchemas.entrySet()) {
                if (!resourceSchemas.containsKey(e.getKey())) {
                    mainComponents.addSchemas(e.getKey(), e.getValue());
                }
            }

            controllerPaths.forEach(allPaths::addPathItem);

            // Write per-controller file: { "components": { "schemas": {...} }, "paths": {...} }
            ObjectNode root = mapper.createObjectNode();
            if (!controllerSchemas.isEmpty()) {
                ObjectNode schemasNode = mapper.createObjectNode();
                for (Map.Entry<String, Schema<?>> e : controllerSchemas.entrySet()) {
                    schemasNode.set(e.getKey(), mapper.valueToTree(e.getValue()));
                }
                ObjectNode componentsNode = mapper.createObjectNode();
                componentsNode.set("schemas", schemasNode);
                root.set("components", componentsNode);
            }
            root.set("paths", mapper.valueToTree(controllerPaths));

            Path outFile = controllersDir.resolve(cls.getSimpleName() + ".json");
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

    private void processMethod(Method method, String basePath,
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
        Operation op = buildOperation(method, schemas, converters);
        if (fullPath.contains("{" + VERSION_VARIABLE + "}")) {
            // No controller method declares a @PathVariable for it — the segment comes from the
            // wildcard in the mapping, not from the signature — but an OpenAPI path template
            // variable must still be declared or the document is invalid.
            op.addParametersItem(versionParam());
        }

        PathItem pathItem = paths.get(fullPath);
        if (pathItem == null) {
            pathItem = new PathItem();
        }
        for (RequestMethod hm : httpMethods) {
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

    private Operation buildOperation(Method method, Map<String, Schema<?>> schemas,
            ModelConverters converters) {
        Operation op = new Operation().addTagsItem("Controllers");
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
