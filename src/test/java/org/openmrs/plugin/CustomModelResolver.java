package org.openmrs.plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Iterator;

import org.openmrs.plugin.Tester.Person;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;

/**
 * Note: 
 * - ModelResolver.java:579: List<BeanPropertyDefinition> properties = beanDesc.findProperties();
 */
public class CustomModelResolver extends ModelResolver {

  public CustomModelResolver(ObjectMapper mapper) {
    super(mapper);
  }

  @Override
  public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
      if (type.getType().getTypeName().contains("Person")) {;
          return resolveSchemaForClass(Person.class, context, chain);
      }
      else {
        return super.resolve(type, context, chain);
      }
  }

  public ObjectSchema resolveSchemaForClass(Class<?> c, ModelConverterContext context, Iterator<ModelConverter> chain) {
    ObjectSchema objectSchema = new ObjectSchema();
    for(Field f : c.getFields()) {
      String fieldName = f.getName();
      Type fieldType = f.getType();
      AnnotatedType aType = new AnnotatedType(fieldType);
      objectSchema.addProperty(fieldName, resolve(aType, context, chain));
    }
    return objectSchema;
  }

}
