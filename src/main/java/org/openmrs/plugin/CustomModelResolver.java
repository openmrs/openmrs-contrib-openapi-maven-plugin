package org.openmrs.plugin;

import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.ConceptDatatype;
import org.openmrs.OpenmrsObject;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.util.ReflectionUtil;
import org.openmrs.module.webservices.rest.web.annotation.PropertyGetter;
import org.openmrs.module.webservices.rest.web.annotation.RepHandler;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.api.Creatable;
import org.openmrs.module.webservices.rest.web.resource.api.Deletable;
import org.openmrs.module.webservices.rest.web.resource.api.Listable;
import org.openmrs.module.webservices.rest.web.resource.api.Purgeable;
import org.openmrs.module.webservices.rest.web.resource.api.Resource;
import org.openmrs.module.webservices.rest.web.resource.api.Retrievable;
import org.openmrs.module.webservices.rest.web.resource.api.Searchable;
import org.openmrs.module.webservices.rest.web.resource.api.SubResource;
import org.openmrs.module.webservices.rest.web.resource.api.Updatable;
import org.openmrs.module.webservices.rest.web.resource.api.Uploadable;
import org.openmrs.module.webservices.rest.web.resource.impl.DataDelegatingCrudResource;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingSubResource;
import org.openmrs.module.webservices.rest.web.resource.impl.MetadataDelegatingCrudResource;
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

  private static final List<Class<?>> ABILITY_INTERFACES = java.util.Arrays.asList(
      Retrievable.class, Creatable.class, Updatable.class, Deletable.class,
      Searchable.class, Listable.class, Purgeable.class, Uploadable.class, SubResource.class
  );

  public CustomModelResolver(ObjectMapper mapper) {
    super(mapper);
  }

  @Override
  public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
      if (type instanceof OpenmrsResourceAnnotatedType) {
        OpenmrsResourceAnnotatedType<?> omrsType = (OpenmrsResourceAnnotatedType<?>) type;
        DelegatingResourceHandler<?> handler = omrsType.getHandler();

        List<Schema<?>> getSchemas = new ArrayList<>();
        List<Schema<?>> writeSchemas = new ArrayList<>();
        for (Schema<?> s : resolveRepresentationSchemasForResource(omrsType, context, chain)) {
          if (s.getName() != null && s.getName().contains("Get_")) {
            getSchemas.add(s);
          } else {
            writeSchemas.add(s);
          }
        }

        String resourceName = getResourceName(handler);
        ObjectSchema combinedSchema = new ObjectSchema();
        combinedSchema.setDescription("One of the supported representations for " + resourceName);

        List<String> abilities = new ArrayList<>();
        for (Class<?> ability : ABILITY_INTERFACES) {
          if (ability.isAssignableFrom(handler.getClass())) {
            abilities.add(ability.getSimpleName());
          }
        }
        combinedSchema.addExtension("x-openmrs-abilities", abilities);

        // Build intermediary ResourceGet schema as anyOf of all ResourceGet_* schemas
        if (!getSchemas.isEmpty()) {
          ObjectSchema getSchema = new ObjectSchema();
          getSchema.name(resourceName + "Get");
          for (Schema<?> s : getSchemas) {
            context.defineModel(s.getName(), s);
            Schema<?> ref = new Schema<>();
            ref.$ref("#/schemas/" + s.getName());
            getSchema.addAnyOfItem(ref);
          }
          context.defineModel(getSchema.getName(), getSchema);
          Schema<?> getRef = new Schema<>();
          getRef.$ref("#/schemas/" + getSchema.getName());
          combinedSchema.addAnyOfItem(getRef);
        }

        // Add write schemas (ResourceCreate, ResourceUpdate) directly
        for (Schema<?> s : writeSchemas) {
          context.defineModel(s.getName(), s);
          Schema<?> ref = new Schema<>();
          ref.$ref("#/schemas/" + s.getName());
          combinedSchema.addAnyOfItem(ref);
        }

        return combinedSchema;
      }

      // SimpleObject is a LinkedHashMap<String, Object>. The default Jackson resolver
      // maps Object values to {"type": "object"} in additionalProperties, but SimpleObject
      // values can be any JSON type (strings, dates, booleans, nested objects, etc.).
      if (type.getType() != null
              && SimpleObject.class.isAssignableFrom(TypeFactory.rawClass(type.getType()))) {
        ObjectSchema schema = new ObjectSchema();
        schema.additionalProperties(Boolean.TRUE);
        return schema;
      }

      return super.resolve(type, context, chain);
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
          repResourceSchema.name(getResourceName(handler) + "Get_" + rep.getRepresentation());
          ret.add(repResourceSchema);
          generatedReps.add(rep.getRepresentation());
        }
      } catch (RuntimeException e) {
        log.warn("Error generating schema for " + getResourceName(handler) + " representation " + rep.getRepresentation(), e);
      }
    }

    // scan through methods in handler and look for ones with @RepHandler annotation.
    // mirrors BaseDelegatingResource.asRepresentation(): @RepHandler is only used when
    // getRepresentationDescription() returns null for the representation it covers.
    Method[] methods = handler.getClass().getMethods();
    for (Method method : methods) {
      RepHandler repHandler = method.getAnnotation(RepHandler.class);
      if(repHandler != null) {
        String repName = getRepHandlerName(repHandler);
        if(generatedReps.contains(repName)) {
          continue;
        }
        // skip @RepHandler methods for representations already covered by getRepresentationDescription()
        boolean alreadyCovered = false;
        for (Representation standardRep : STANDARD_REPRESENTATIONS) {
          if (repHandler.value().isAssignableFrom(standardRep.getClass())) {
            try {
              if (handler.getRepresentationDescription(standardRep) != null) {
                alreadyCovered = true;
                break;
              }
            } catch (RuntimeException ignored) {}
          }
        }
        if (alreadyCovered) continue;

        Schema<?> repResourceSchema = resolveSchemaForRepHandlerMethod(method, handler, context, chain);
        repResourceSchema.addExtension("x-openmrs-representation", repName);
        repResourceSchema.name(getResourceName(handler) + "Get_" + repName);
        ret.add(repResourceSchema);
        generatedReps.add(repName);
      }
    }

    // ========== CUSTOM representation ==========
    try {
      ObjectSchema customSchema = resolveCustomRepresentationSchema(handler, context, chain);
      if (customSchema != null) {
        customSchema.addExtension("x-openmrs-representation", "custom");
        customSchema.name(getResourceName(handler) + "Get_custom");
        ret.add(customSchema);
        generatedReps.add("custom");
      }
    } catch (RuntimeException e) {
      log.warn("Error generating custom representation schema for " + getResourceName(handler), e);
    }

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
        if(write) {
          log.warn("Error getting schema for writable property: " + getResourceName(handler) + "." + propertyName);
        } else {
          log.warn("Error getting schema for readableproperty: " + getResourceName(handler) + "." + propertyName);
        }
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
          // @PropertySetter methods have signature: setter(T instance, V value) — the value type
          // is the second parameter. getGenericReturnType() would give void, which is wrong.
          propertyType = annotatedSetter.getGenericParameterTypes()[1];
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
      if(write) {
        // In create/update operations, other resources are referenced by UUID string only.
        // The REST module's ConversionUtil.convert() accepts a UUID string and resolves the entity.
        // (Confirmed by getCREATEModel() implementations which use StringProperty().example("uuid"))
        return uuidRefSchema(propertyType);
      }
      // `rep` reflects the Representation passed to DelegatingResourceDescription.addProperty(name, rep)
      // in the resource's getRepresentationDescription(). In practice it is never null: the no-rep
      // overload addProperty(String) passes null, which DelegatingResourceDescription normalizes to
      // Representation.DEFAULT (DelegatingResourceDescription line 85: `if (rep == null) rep = DEFAULT`).
      // Resources that nest other resources typically use Representation.REF for their default
      // representation (e.g. VisitResource1_9: addProperty("patient", Representation.REF)) and
      // Representation.DEFAULT for full (e.g. addProperty("encounters", Representation.DEFAULT)).
      // The DEFAULT fallback below is defensive and in practice never reached.
      return getRefSchemaForResource(propertyType, rep != null ? rep : Representation.DEFAULT);
    } else if(isCollection(propertyType)) {
      Type itemType = TypeFactory.defaultInstance().constructCollectionType(java.util.List.class, TypeFactory.defaultInstance().constructType(propertyType).getContentType()).getContentType();
      if(isOpenmrsObject(itemType)) {
        ArraySchema arraySchema = new ArraySchema();
        if(write) {
          arraySchema.items(uuidRefSchema(itemType));
        } else {
          arraySchema.items(getRefSchemaForResource(itemType, rep != null ? rep : Representation.DEFAULT));
        }
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

  /**
   * Discovers all properties accessible via a custom representation for this resource.
   * Returns a map of property name to the Method that provides the value (either a
   * @PropertyGetter on the handler or a JavaBean getter on the delegate). Keeping the Method
   * lets resolveCustomRepresentationSchema check swagger annotations on the method.
   *
   * Custom representations go through BaseDelegatingConverter.getProperty(), which:
   *   1. Checks for @PropertyGetter-annotated methods on the resource handler first
   *   2. Falls back to PropertyUtils.getProperty(delegate, name) — JavaBean getters on the delegate
   *
   * This method discovers both, so the resulting schema covers everything a caller can request.
   */
  private <T> Map<String, Method> discoverCustomRepProperties(DelegatingResourceHandler<T> handler) {
    Map<String, Method> properties = new LinkedHashMap<>();

    Class<?> delegateType = getDelegateType(handler);
    if (delegateType != null) {
      // JavaBean getter-based properties on the delegate (the PropertyUtils fallback path)
      for (PropertyDescriptor pd : PropertyUtils.getPropertyDescriptors(delegateType)) {
        if ("class".equals(pd.getName()) || pd.getReadMethod() == null) continue;
        Method readMethod = pd.getReadMethod();
        if (Modifier.isPublic(readMethod.getModifiers()) && !Modifier.isStatic(readMethod.getModifiers())) {
          properties.put(pd.getName(), readMethod);
        }
      }
    }

    // @PropertyGetter annotations on the resource handler hierarchy.
    // These override delegate properties at runtime (checked first in getProperty()).
    // Walk most-derived → base using putIfAbsent so the most-derived handler's getter wins.
    Class<?> handlerClass = handler.getClass();
    while (handlerClass != null && !handlerClass.equals(Object.class)) {
      for (Method m : handlerClass.getDeclaredMethods()) {
        PropertyGetter getter = m.getAnnotation(PropertyGetter.class);
        if (getter != null) {
          properties.putIfAbsent(getter.value(), m);
        }
      }
      handlerClass = handlerClass.getSuperclass();
    }

    return properties;
  }

  /**
   * Generates an ObjectSchema for the custom representation of the given resource.
   * All properties are optional (the caller specifies any subset via ?v=custom:(...)).
   * For sub-resource (OpenmrsObject) properties, defaults to REF representation since that is
   * the most compact form; callers can request a different sub-rep per-property, e.g.
   * {@code ?v=custom:(patient:default)}.
   */
  private <T> ObjectSchema resolveCustomRepresentationSchema(DelegatingResourceHandler<T> handler,
      ModelConverterContext context, Iterator<ModelConverter> chain) {

    Map<String, Method> props = discoverCustomRepProperties(handler);
    if (props.isEmpty()) {
      return null;
    }

    ObjectSchema schema = new ObjectSchema();
    for (Map.Entry<String, Method> entry : props.entrySet()) {
      String propName = entry.getKey();
      Method method = entry.getValue();
      Type propType = method.getGenericReturnType();
      try {
        Schema<?> propSchema;
        if (isOpenmrsObject(propType)) {
          propSchema = getRefSchemaForResource(propType, Representation.REF);
        } else if (isCollection(propType)) {
          ArraySchema arraySchema = new ArraySchema();
          com.fasterxml.jackson.databind.JavaType itemJavaType =
              TypeFactory.defaultInstance().constructType(propType).getContentType();
          if (itemJavaType != null) {
            if (isOpenmrsObject(itemJavaType)) {
              arraySchema.items(getRefSchemaForResource(itemJavaType, Representation.REF));
            } else {
              Schema<?> itemSchema = resolve(new AnnotatedType(itemJavaType), context, chain);
              if (itemSchema != null) arraySchema.items(itemSchema);
            }
          }
          propSchema = arraySchema;
        } else if (java.util.Map.class.isAssignableFrom(TypeFactory.rawClass(propType))) {
          // Map types (e.g. Map<String, PersonAttribute>) must NOT go through super.resolve():
          // Jackson would recursively resolve the value type as a full POJO, which cascades
          // inline schema registration for PersonAttribute → Person → Concept → ...
          ObjectSchema mapSchema = new ObjectSchema();
          com.fasterxml.jackson.databind.JavaType valueJavaType =
              TypeFactory.defaultInstance().constructType(propType).getContentType();
          if (valueJavaType != null && isOpenmrsObject(valueJavaType)) {
            mapSchema.additionalProperties(getRefSchemaForResource(valueJavaType, Representation.REF));
          } else {
            mapSchema.additionalProperties(Boolean.TRUE);
          }
          propSchema = mapSchema;
        } else {
          propSchema = resolve(new AnnotatedType(propType), context, chain);
        }
        if (propSchema != null) {
          schema.addProperty(propName, propSchema);
        }
      } catch (RuntimeException e) {
        log.warn("Error resolving schema for custom property {}.{}", getResourceName(handler), propName, e);
      }
    }

    return schema.getProperties() != null && !schema.getProperties().isEmpty() ? schema : null;
  }

  /**
   * Returns a schema for a UUID string field that references another REST resource.
   * Uses {type: string, description: "UUID of <ResourceName>", x-openmrs-resource: "<ResourceName>"}
   * so both human readers and tooling can identify which resource the UUID points to.
   */
  private static Schema<?> uuidRefSchema(Type delegateType) {
    String resourceName = TypeFactory.rawClass(delegateType).getSimpleName();
    Schema<?> schema = new Schema<>().type("string").description("UUID of " + resourceName).format("uuid");
    schema.addExtension("x-openmrs-resource", resourceName);
    return schema;
  }

  private Schema<?> getRefSchemaForResource(Type delegateType, Representation rep) {
    Schema<?> ret = new Schema<>();
    ret.$ref(getResourceSpecFilename(delegateType, rep));
    return ret;
  }

  public static String getResourceSpecFilename(Type delegateType, Representation rep) {
    Class<?> rawClass = TypeFactory.rawClass(delegateType);
    String resourceName = rawClass.getSimpleName();
    return "./" + resourceName + ".json#/schemas/" + resourceName + "Get_" + rep.getRepresentation();
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

  /**
   * Resolves the schema for a @RepHandler-annotated method.
   *
   * Normally we resolve the method's return type. However, three base-class REF handlers all
   * return SimpleObject and build their DelegatingResourceDescription inside the method body,
   * making it impossible to derive the schema from the return type alone:
   *
   *   DataDelegatingCrudResource.asRef()          — uuid, display, voided (conditional), selfLink
   *   MetadataDelegatingCrudResource.convertToRef()— uuid, display, retired (conditional), selfLink
   *   DelegatingSubResource.asRef()               — uuid, display, voided (conditional), selfLink
   *
   * We hard-code the known descriptions for each. The voided/retired fields are conditional at
   * runtime (only added when the delegate is voided/retired), but we always include them in the
   * schema since a schema describes what the response MAY contain.
   *
   * TODO: Change these methods in the REST module to return a properly typed object instead of
   * SimpleObject. That would let us derive the schema from the return type like any other method,
   * eliminating these special cases entirely.
   */
  private Schema<?> resolveSchemaForRepHandlerMethod(Method method, DelegatingResourceHandler<?> handler,
      ModelConverterContext context, Iterator<ModelConverter> chain) {
    Class<?> declaring = method.getDeclaringClass();
    if (DataDelegatingCrudResource.class.equals(declaring) && "asRef".equals(method.getName())) {
      return hardCodedRefSchema(handler, "voided", context, chain);
    }
    if (MetadataDelegatingCrudResource.class.equals(declaring) && "convertToRef".equals(method.getName())) {
      return hardCodedRefSchema(handler, "retired", context, chain);
    }
    if (DelegatingSubResource.class.equals(declaring) && "asRef".equals(method.getName())) {
      return hardCodedRefSchema(handler, "voided", context, chain);
    }
    return resolve(new AnnotatedType(method.getGenericReturnType()), context, chain);
  }

  private Schema<?> hardCodedRefSchema(DelegatingResourceHandler<?> handler, String voidedOrRetired,
      ModelConverterContext context, Iterator<ModelConverter> chain) {
    DelegatingResourceDescription description = new DelegatingResourceDescription();
    description.addProperty("uuid");
    description.addProperty("display");
    description.addProperty(voidedOrRetired);
    description.addSelfLink();
    Schema<?> schema = resolveSchemaForResourceDescription(handler, description, false, context, chain);
    return schema != null ? schema : new ObjectSchema().additionalProperties(Boolean.TRUE);
  }

  /**
   * Returns the representation name for a @RepHandler annotation.
   * If the annotation has an explicit name(), use it.
   * Otherwise instantiate value() with its no-arg constructor and call getRepresentation()
   * (e.g. FullRepresentation -> "full").
   */
  private static String getRepHandlerName(RepHandler repHandler) {
    if (!repHandler.name().isEmpty()) {
      return repHandler.name();
    }
    try {
      return repHandler.value().getDeclaredConstructor().newInstance().getRepresentation();
    } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
      log.warn("Could not determine representation name for @RepHandler {}", repHandler.value().getName(), e);
      return "unknown";
    }
  }

  /**
   * Returns the REST path for a resource handler by reading the name() field of its
   * {@code @Resource} annotation (e.g. "v1/visit" → "/ws/rest/v1/visit").
   * Falls back to lowercasing the resource name if no annotation is found.
   */
  public static String getResourceRestPath(DelegatingResourceHandler<?> handler) {
    Class<?> cls = handler.getClass();
    while (cls != null) {
      org.openmrs.module.webservices.rest.web.annotation.Resource ann =
          cls.getAnnotation(org.openmrs.module.webservices.rest.web.annotation.Resource.class);
      if (ann != null && !ann.name().isEmpty()) {
        return "/ws/rest/" + ann.name();
      }
      cls = cls.getSuperclass();
    }
    return "/ws/rest/v1/" + getResourceName(handler).toLowerCase();
  }

  private boolean isCollection(Type t) {
    Class<?> rawClass = TypeFactory.rawClass(t);
    return java.util.Collection.class.isAssignableFrom(rawClass);
  }
}
