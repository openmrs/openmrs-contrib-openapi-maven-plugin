package org.openmrs.plugin;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openmrs.module.webservices.rest.SimpleObject;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomModelResolverTest {

    /**
     * A stand-in for a typed SimpleObject payload, e.g. AuditInfo in the REST module.
     */
    public static class TypedPayload {

        public String getName() {
            return null;
        }

        public Integer getCount() {
            return null;
        }
    }

    interface Methods {

        SimpleObject getRaw();

        SimpleObject<?> getWildcard();

        SimpleObject<TypedPayload> getTyped();
    }

    private static ModelConverters newConverters() {
        ModelConverters converters = new ModelConverters();
        converters.addConverter(new CustomModelResolver(Json31.mapper()));
        return converters;
    }

    private static ResolvedSchema resolve(String methodName) throws NoSuchMethodException {
        Method method = Methods.class.getMethod(methodName);
        return newConverters().resolveAsResolvedSchema(new AnnotatedType(method.getGenericReturnType()));
    }

    @Test
    void resolve_shouldReturnGenericObjectSchemaForRawSimpleObject() throws Exception {
        ResolvedSchema resolved = resolve("getRaw");

        assertNotNull(resolved.schema);
        assertEquals(Boolean.TRUE, resolved.schema.getAdditionalProperties());
        assertNull(resolved.schema.getProperties());
    }

    @Test
    void resolve_shouldReturnGenericObjectSchemaForWildcardSimpleObject() throws Exception {
        ResolvedSchema resolved = resolve("getWildcard");

        assertNotNull(resolved.schema);
        assertEquals(Boolean.TRUE, resolved.schema.getAdditionalProperties());
        assertNull(resolved.schema.getProperties());
    }

    @Test
    void resolve_shouldResolveTheActualTypeForATypedSimpleObject() throws Exception {
        ResolvedSchema resolved = resolve("getTyped");

        assertNotNull(resolved.schema);
        // it should NOT have fallen back to the generic "any object" schema
        assertFalse(Boolean.TRUE.equals(resolved.schema.getAdditionalProperties()));

        // the resolved (possibly referenced) schema should describe TypedPayload's own properties
        Schema<?> typedSchema = resolved.schema;
        if ((typedSchema.getProperties() == null || typedSchema.getProperties().isEmpty())
                && resolved.referencedSchemas != null) {
            Schema<?> referenced = resolved.referencedSchemas.get("TypedPayload");
            assertNotNull(referenced, "Expected a referenced schema named TypedPayload, found: "
                    + resolved.referencedSchemas.keySet());
            typedSchema = referenced;
        }

        Map<String, Schema> properties = typedSchema.getProperties();
        assertNotNull(properties);
        assertTrue(properties.containsKey("name"));
        assertTrue(properties.containsKey("count"));
    }
}
