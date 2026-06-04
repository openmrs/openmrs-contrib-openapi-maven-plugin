package org.openmrs.plugin.rest.analyzer;

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
import org.springframework.context.ApplicationContext;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers all @Controller beans in the Spring context (except the two resource-dispatch
 * controllers) and generates per-controller OpenAPI documentation from @RequestMapping annotations.
 */
public class ControllerDocumenter {

    private static final Logger log = LoggerFactory.getLogger(ControllerDocumenter.class);

    private static final Set<String> EXCLUDED_CONTROLLERS = new HashSet<>(Arrays.asList(
        "MainResourceController", "MainSubResourceController"
    ));

    /**
     * Discovers all @Controller beans, generates per-controller JSON files under controllersDir,
     * merges controller DTO schemas into mainComponents, and returns a Paths map for inclusion
     * in the main openapi.json.
     */
    public Paths document(ApplicationContext ctx, Path controllersDir, Components mainComponents)
            throws IOException {
        Paths allPaths = new Paths();
        com.fasterxml.jackson.databind.ObjectMapper mapper = io.swagger.v3.core.util.Json.mapper();

        List<Object> beans = new ArrayList<>(ctx.getBeansWithAnnotation(Controller.class).values());
        beans.sort((a, b) -> targetClass(a).getSimpleName().compareTo(targetClass(b).getSimpleName()));

        for (Object bean : beans) {
            Class<?> cls = targetClass(bean);
            if (EXCLUDED_CONTROLLERS.contains(cls.getSimpleName())) continue;

            String basePath = classBasePath(cls);
            if (basePath == null) continue;

            // Fresh converters per controller to avoid schema accumulation across controllers.
            // Uses default ModelResolver (plain Java bean introspection via Jackson).
            ModelConverters converters = new ModelConverters();

            Map<String, Schema<?>> controllerSchemas = new LinkedHashMap<>();
            Paths controllerPaths = new Paths();

            for (Method method : cls.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                processMethod(method, basePath, controllerPaths, controllerSchemas, converters);
            }

            if (controllerPaths.isEmpty()) continue;

            // Merge controller schemas into the main openapi.json components so that
            // $ref: "#/components/schemas/Foo" refs in the inline paths section resolve correctly.
            for (Map.Entry<String, Schema<?>> e : controllerSchemas.entrySet()) {
                mainComponents.addSchemas(e.getKey(), e.getValue());
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
            log.info("Wrote controller file: {}", outFile);
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

        String fullPath = joinPath(basePath, subPath);
        Operation op = buildOperation(method, schemas, converters);

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
        Operation op = new Operation();
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
            if (cls == String.class) return new Schema<String>().type("string");
            if (cls == byte[].class) return new Schema<>().type("string").format("binary");
            if (cls == boolean.class || cls == Boolean.class) return new Schema<Boolean>().type("boolean");
            if (Number.class.isAssignableFrom(cls) || (cls.isPrimitive() && cls != boolean.class))
                return new Schema<>().type("number");
            if (cls == Object.class) return new ObjectSchema();
            if (Map.class.isAssignableFrom(cls)) return new ObjectSchema();
            // MultipartFile — check by name to avoid hard dependency on servlet API version
            if ("MultipartFile".equals(cls.getSimpleName())) return new Schema<>().type("string").format("binary");
        }

        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            Class<?> raw = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(raw) || Iterable.class.isAssignableFrom(raw)) {
                Type itemType = pt.getActualTypeArguments()[0];
                Schema<?> items = resolveType(itemType, schemas, converters);
                return new ArraySchema().items(items != null ? items : new ObjectSchema());
            }
            if (Map.class.isAssignableFrom(raw)) return new ObjectSchema();
        }

        // POJO: use ModelConverters (Jackson-based introspection)
        try {
            ResolvedSchema resolved = converters.resolveAsResolvedSchema(new AnnotatedType(type));
            if (resolved == null) return new ObjectSchema();
            if (resolved.referencedSchemas != null) {
                @SuppressWarnings("unchecked")
                Map<String, Schema<?>> refs = (Map<String, Schema<?>>) (Map<?, ?>) resolved.referencedSchemas;
                schemas.putAll(refs);
            }
            // resolved.schema is typically $ref: "#/components/schemas/TypeName"
            return resolved.schema != null ? resolved.schema : new ObjectSchema();
        } catch (Exception e) {
            log.debug("Could not resolve schema for {}: {}", type, e.getMessage());
            return new ObjectSchema();
        }
    }

    // ---- static helpers ----

    private static Class<?> targetClass(Object bean) {
        Class<?> cls = bean.getClass();
        // Unwrap CGLIB proxies (class names contain "$$EnhancerBySpringCGLIB$$" etc.)
        while (cls.getName().contains("$$")) {
            cls = cls.getSuperclass();
        }
        return cls;
    }

    private static String classBasePath(Class<?> cls) {
        RequestMapping rm = cls.getAnnotation(RequestMapping.class);
        if (rm == null) return null;
        String[] vals = rm.value().length > 0 ? rm.value() : rm.path();
        return vals.length > 0 ? vals[0] : null;
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
            return new Schema<Integer>().type("integer").format("int32");
        if (type == long.class || type == Long.class)
            return new Schema<Long>().type("integer").format("int64");
        if (type == boolean.class || type == Boolean.class)
            return new Schema<Boolean>().type("boolean");
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class)
            return new Schema<Double>().type("number");
        return new Schema<String>().type("string");
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
