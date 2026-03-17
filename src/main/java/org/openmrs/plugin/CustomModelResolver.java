package org.openmrs.plugin;

import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.ConceptDatatype;
import org.openmrs.OpenmrsObject;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.util.ReflectionUtil;
import org.openmrs.module.webservices.rest.web.annotation.RepHandler;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.api.Resource;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.response.ResourceDoesNotSupportOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;

public class CustomModelResolver extends ModelResolver {

  private static final Logger log = LoggerFactory.getLogger(CustomModelResolver.class);

  public static final Representation[] STANDARD_REPRESENTATIONS = {Representation.DEFAULT, Representation.FULL, Representation.REF};

  public CustomModelResolver(ObjectMapper mapper) {
    super(mapper);
  }

  @Override
  public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
      if (type instanceof OpenmrsResourceAnnotatedType) {
        OpenmrsResourceAnnotatedType<?> omrsType = (OpenmrsResourceAnnotatedType<?>) type;
        DelegatingResourceHandler<?> handler = omrsType.getHandler();

        List<Schema<?>> representationSchemas = resolveRepresentationSchemasForResource(omrsType, context, chain);
        // create a combined Schema that can be anyOf the representation schemas
        ObjectSchema combinedSchema = new ObjectSchema();
        for(Schema<?> repSchema: representationSchemas) {
          combinedSchema.addAnyOfItem(repSchema);
        }

        combinedSchema.setDescription("One of the supported representations for " + getResourceName(handler));
        return combinedSchema;
      }
      else {
        return super.resolve(type, context, chain);
      }
  }

  /**
   * Generates schemas for all supported representations of the given REST resource handler.
   * @param <T>
   * @param type
   * @param context
   * @param chain
   * @return
   */
  public <T> List<Schema<?>> resolveRepresentationSchemasForResource(OpenmrsResourceAnnotatedType<T> type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    DelegatingResourceHandler<T> handler = type.getHandler();
    List<Schema<?>> ret = new ArrayList<>();

    List<String> generatedReps = new ArrayList<>();

    // ========== GET representations ==========

    // try the standard reps first
    // TODO: use Retrievable#getAvailableRepresentations?
    for (Representation rep : STANDARD_REPRESENTATIONS) {
      try {
        // models BaseDelegatingResource.asRepresentation()
        DelegatingResourceDescription desc = handler.getRepresentationDescription(rep);
        Schema<?> repResourceSchema = resolveSchemaForResourceDescription(handler, desc, false, context, chain);
        if (repResourceSchema != null) {
          repResourceSchema.addExtension("x-openmrs-representation", rep.getRepresentation());
          repResourceSchema.name(getResourceName(handler) + StringUtils.capitalize(rep.getRepresentation()));
          ret.add(repResourceSchema);
          generatedReps.add(rep.getRepresentation());
        }
      } catch (RuntimeException e) {
        log.warn("Error generating schema for " + getResourceName(handler) + " representation " + rep.getRepresentation(), e);
      }
    }

    // scan through methods in handler and look for ones with @RepHandler annotation
    Method[] methods = handler.getClass().getMethods();
    for (Method method : methods) {
      RepHandler repHandler = method.getAnnotation(RepHandler.class);
      if(repHandler != null) {
        String repName = repHandler.name();
        Schema<?> repResourceSchema = resolve(new AnnotatedType(method.getGenericReturnType()), context, chain);
        repResourceSchema.name(getResourceName(handler) + StringUtils.capitalize(repName));
        ret.add(repResourceSchema);
        generatedReps.add(repName);
      }
    }

    // TODO: custom representations

    // ========== CREATE representations ==========
    DelegatingResourceDescription createDesc = getCreateDescription(handler);
    if(createDesc != null) {
      try {
        Schema<?> createResourceSchema = resolveSchemaForResourceDescription(handler, createDesc, true, context, chain);
        if (createResourceSchema != null) {
          createResourceSchema.name(getResourceName(handler) + "Create");
          ret.add(createResourceSchema);
          generatedReps.add("create");
        }
      } catch (RuntimeException e) {
        log.warn("Error generating schema for " + getResourceName(handler) + " create representation", e);
      }
    }

    // ========== UPDATE representations ==========
    DelegatingResourceDescription updateDesc = getUpdatableDescription(handler);
    if(updateDesc != null) {
      try {
        Schema<?> updateResourceSchema = resolveSchemaForResourceDescription(handler, updateDesc, true, context, chain);
        if (updateResourceSchema != null) {
          updateResourceSchema.name(getResourceName(handler) + "Update");
          ret.add(updateResourceSchema);
          generatedReps.add("update");
        }
      } catch (RuntimeException e) {
        log.warn("Error generating schema for " + getResourceName(handler) + " update representation", e);
      }

    }

    log.info("Generated schemas for " + getResourceName(handler) + " representations: " + StringUtils.join(generatedReps, ", "));

    return ret;
  }

  private static DelegatingResourceDescription getCreateDescription(DelegatingResourceHandler<?> handler) {
    try {
      return handler.getCreatableProperties();
    } catch(ResourceDoesNotSupportOperationException e) {
      return null;
    }
  }

  private static DelegatingResourceDescription getUpdatableDescription(DelegatingResourceHandler<?> handler) {
    try {
      return handler.getUpdatableProperties();
    } catch(ResourceDoesNotSupportOperationException e) {
      return null;
    }
  }

  /**
   * Generates a schema for the given REST resource handler and based on the given description
   * @param <T>
   * @param handler
   * @param desc
   * @param write
   * @param context
   * @param chain
   * @return
   */
  private <T> ObjectSchema resolveSchemaForResourceDescription(DelegatingResourceHandler<T> handler, DelegatingResourceDescription desc, boolean write, ModelConverterContext context, Iterator<ModelConverter> chain) {

    if(desc == null) {
      return null;
    }

    ObjectSchema objectSchema = new ObjectSchema();
    // models  BaseDelegatingConverter#convertDelegateToRepresentation() function
    for (Map.Entry<String, DelegatingResourceDescription.Property> e : desc.getProperties().entrySet()) {
      DelegatingResourceDescription.Property property = e.getValue();
      String propertyName = e.getKey();

      try {
        objectSchema.addProperty(propertyName, getRestResourcePropertySchema(handler, property, write, context, chain));
        if(property.isRequired()) {
          objectSchema.addRequiredItem(propertyName);
        }
      } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | RuntimeException e1) {
        log.warn("Error getting type of property: " + getResourceName(handler) + "." + propertyName, e1);
      }
    }

    return objectSchema;
  }

  // models DelegatingResourceDescription.Property#evaluate
  private <T> Schema<?> getRestResourcePropertySchema(DelegatingResourceHandler<T> handler, DelegatingResourceDescription.Property property, boolean write, ModelConverterContext context, Iterator<ModelConverter> chain) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
    String delegateProperty = property.getDelegateProperty();
    Method propertyMethod = property.getMethod();
    Representation rep = property.getRep();
    Type propertyType = null;
    if(delegateProperty != null) {

      if(!write) {
        // models BaseDelegaingConverter.getProperty()
        // TODO: Model BaseDelegatingResource.getProperty()'s getResourceHandler call
        //       - use reflection to get subclassHandlers
        // TODO: handle override, like UserResource1_8.getProperty()
        Method annotatedGetter = ReflectionUtil.findPropertyGetterMethod(handler, delegateProperty);
        if (annotatedGetter != null) {
          propertyType = annotatedGetter.getGenericReturnType();
        }
        else {
          PropertyDescriptor propertyDescriptor = getPropertyDescriptor(handler, delegateProperty);
          Method m = propertyDescriptor != null ? propertyDescriptor.getReadMethod() : null;
          if(m != null) {
            propertyType = m.getGenericReturnType();
          }
          else {
            throw new RuntimeException("Unable to get readable property: " + getResourceName(handler) + "." + delegateProperty);
          }
        }
      } else {
        // models BaseDelegatingConverter.setProperty()
        Method annotatedSetter = ReflectionUtil.findPropertySetterMethod(handler, delegateProperty);
        if (annotatedSetter != null) {
          propertyType = annotatedSetter.getGenericReturnType();
        }
        else {
          PropertyDescriptor propertyDescriptor = getPropertyDescriptor(handler, delegateProperty);
          Method m = propertyDescriptor != null ? propertyDescriptor.getWriteMethod() : null;
          if(m != null) {
            propertyType = m.getGenericParameterTypes()[0];
          } else {
            throw new RuntimeException("Unable to get writeable property: " + getResourceName(handler) + "." + delegateProperty);
          }
        }
      }
    }
    else if (propertyMethod != null) {
      if(!write) {
        propertyType = propertyMethod.getGenericReturnType();
      } else {
        // I don't really know for sure cause I can't find this being used anywhere
        propertyType = propertyMethod.getGenericParameterTypes()[0];
      }
    }
    else {
      throw new RuntimeException();
    }

    if(isOpenmrsObject(propertyType)) {
      return getRefSchemaForResource(propertyType, rep);
    } else if(isCollection(propertyType)) {
      Type itemType = TypeFactory.defaultInstance().constructCollectionType(java.util.List.class, TypeFactory.defaultInstance().constructType(propertyType).getContentType()).getContentType();
      if(isOpenmrsObject(itemType)) {
        ArraySchema arraySchema = new ArraySchema();
        arraySchema.items(getRefSchemaForResource(itemType, rep));
        return arraySchema;
      } else {
        return resolve(new AnnotatedType(itemType), context, chain);
      }
    }else {
      return resolve(new AnnotatedType(propertyType), context, chain);
    }
  }

  private <T> PropertyDescriptor getPropertyDescriptor(DelegatingResourceHandler<T> handler, String property) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

    T delegate = null;
    try {
      // special case for Concept.datatype to get the ConceptNumeric subclass
      delegate = handler.newDelegate(new SimpleObject().add("datatype", ConceptDatatype.NUMERIC_UUID));
    } catch(ResourceDoesNotSupportOperationException e) {
      // ignore;
    }

    if(delegate != null) {
      // this works best as that's closest to how converter.getProperty() and BaseDelegatingConverter.setProperty() operate
      // However, it does require that newDelegate() actually returns an Object (not true for non-Retrievable Resources)
      return PropertyUtils.getPropertyDescriptor(delegate, property);
    }
    else {
      Class<?> delegateType = getDelegateType(handler);
      PropertyDescriptor[] descriptors = PropertyUtils.getPropertyDescriptors(delegateType);
      for(PropertyDescriptor pd : descriptors) {
        if(pd.getName().equals(property)) {
          return pd;
        }
      }
      log.warn("Could not find property descriptor for " + getResourceName(handler));
      return null;
    }
  }

  private Class<?> getDelegateType(DelegatingResourceHandler<?> resource) {

		Class<?> resourceClass = resource.getClass();

		while (resourceClass != null) {
			Type[] genericInterfaces = resourceClass.getGenericInterfaces();
			for (Type genericInterface : genericInterfaces) {
				if (genericInterface instanceof ParameterizedType) {
					ParameterizedType parameterizedType = (ParameterizedType) genericInterface;
					Type rawType = parameterizedType.getRawType();

					if (rawType instanceof Class
					        && DelegatingResourceHandler.class.isAssignableFrom((Class<?>) rawType)) {
						Type[] typeArgs = parameterizedType.getActualTypeArguments();
						if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
							return (Class<?>) typeArgs[0];
						}
					}
				}
			}

			Type genericSuperclass = resourceClass.getGenericSuperclass();
			if (genericSuperclass instanceof ParameterizedType) {
				ParameterizedType parameterizedType = (ParameterizedType) genericSuperclass;
				Type[] typeArgs = parameterizedType.getActualTypeArguments();

				if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
					return (Class<?>) typeArgs[0];
				}
			}

			resourceClass = resourceClass.getSuperclass();
		}

		log.warn("Could not determine delegate type for " + resource.getClass().getName());
		return null;
	}

  private Schema<?> getRefSchemaForResource(Type delegateType, Representation rep) {
    Schema<?> ret = new Schema<>();
    ret.$ref(getResourceSpecFilename(delegateType, rep));
    return ret;
  }

  public static String getResourceSpecFilename(Type delegateType, Representation rep) {
    Class<?> rawClass = TypeFactory.rawClass(delegateType);
    return "./types/" + rawClass.getSimpleName() + StringUtils.capitalize(rep.getRepresentation()) + ".json";
  }

  public static String getResourceName(DelegatingResourceHandler<?> handler) {
      String className = handler.getClass().getSimpleName();
      int index = className.indexOf("Resource");
      if (index >= 0) {
          return className.substring(0, index);
      }
      return className;
  }

  /**
   * Returns whether the input class is an OpenmrsObject (i.e. Patient, Visit, Encounter, etc...)
   * Note that this is NOT the same as REST resources (i.e. PatientResource1_9)
   */
  private boolean isOpenmrsObject(Type t) {
    return OpenmrsObject.class.isAssignableFrom(TypeFactory.rawClass(t));
  }

  private boolean isCollection(Type t) {
    Class<?> rawClass = TypeFactory.rawClass(t);
    return java.util.Collection.class.isAssignableFrom(rawClass);
  }
}
