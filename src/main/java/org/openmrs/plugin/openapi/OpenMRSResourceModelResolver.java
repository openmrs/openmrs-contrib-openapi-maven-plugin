package org.openmrs.plugin.openapi;

import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.Attributable;
import org.openmrs.Auditable;
import org.openmrs.Changeable;
import org.openmrs.ConceptDatatype;
import org.openmrs.FormRecordable;
import org.openmrs.OpenmrsObject;
import org.openmrs.Retireable;
import org.openmrs.Voidable;
import org.openmrs.customdatatype.Customizable;
import org.openmrs.module.webservices.rest.SimpleObject;
import org.openmrs.module.webservices.rest.util.ReflectionUtil;
import org.openmrs.module.webservices.rest.web.annotation.PropertyGetter;
import org.openmrs.module.webservices.rest.web.annotation.RepHandler;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.openmrs.module.webservices.rest.web.resource.api.Creatable;
import org.openmrs.module.webservices.rest.web.resource.api.Deletable;
import org.openmrs.module.webservices.rest.web.resource.api.Listable;
import org.openmrs.module.webservices.rest.web.resource.api.Purgeable;
import org.openmrs.module.webservices.rest.web.resource.api.Retrievable;
import org.openmrs.module.webservices.rest.web.resource.api.Searchable;
import org.openmrs.module.webservices.rest.web.resource.api.SubResource;
import org.openmrs.module.webservices.rest.web.resource.api.Updatable;
import org.openmrs.module.webservices.rest.web.resource.api.Uploadable;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingSubclassHandler;
import org.openmrs.module.webservices.rest.web.response.ResourceDoesNotSupportOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;

public class OpenMRSResourceModelResolver extends ModelResolver {

  private static final Logger log = LoggerFactory.getLogger(OpenMRSResourceModelResolver.class);

  public static final Representation[] STANDARD_REPRESENTATIONS = {Representation.DEFAULT, Representation.FULL, Representation.REF};

  /** Domain class -> the name of the resource documenting it. @see #registerResourceNames */
  private static final Map<Class<?>, String> RESOURCE_NAME_BY_SUPPORTED_CLASS = new LinkedHashMap<>();

  /** Subclass-handled domain class -> its parent resource name, and -> its subtype name. */
  private static final Map<Class<?>, String> PARENT_BY_SUBCLASS = new LinkedHashMap<>();

  private static final Map<Class<?>, String> VARIANT_BY_SUBCLASS = new LinkedHashMap<>();

  /** {@code RestConstants.PROPERTY_FOR_TYPE} — the subtype discriminator stamped on responses. */
  private static final String TYPE_PROPERTY =
      org.openmrs.module.webservices.rest.web.RestConstants.PROPERTY_FOR_TYPE;

  /** Trailing "Resource", optionally followed by a version suffix such as "1_8" or "2_0". */
  private static final Pattern RESOURCE_SUFFIX = Pattern.compile("Resource(\\d+(_\\d+)*)?$");

  private static final List<Class<?>> RESOURCE_ABILITY_INTERFACES = java.util.Arrays.asList(
      Retrievable.class, Creatable.class, Updatable.class, Deletable.class,
      Searchable.class, Listable.class, Purgeable.class, Uploadable.class, SubResource.class
  );
  private static final List<Class<?>> DELEGATE_ABILITY_INTERFACES = java.util.Arrays.asList(
      FormRecordable.class, Retireable.class, Voidable.class, Changeable.class, Auditable.class,
      Customizable.class, org.openmrs.Creatable.class, Attributable.class
  );

  public OpenMRSResourceModelResolver(ObjectMapper mapper) {
    super(withStablePropertyOrder(mapper));
  }

  /**
   * Returns a copy of the mapper that orders bean properties alphabetically.
   * <p>
   * Schemas built by {@code super.resolve()} come from Jackson's bean introspection, which lists
   * fields in declaration order and then appends getter-only properties in
   * {@code Class.getDeclaredMethods()} order — and that order has no definition and genuinely
   * varies between JVM runs. It made {@code org.openmrs.module.Module} (whose
   * {@code getModuleIdAsPath()} has no backing field) and {@code org.w3c.dom.Document} (all
   * getter-only) emit a different property order on each run, so consecutive runs against the same
   * module produced different bytes.
   * <p>
   * Only bean-introspected schemas are affected. Representation schemas are built by this class
   * from a {@code DelegatingResourceDescription} and keep that description's own order.
   * <p>
   * The mapper is copied rather than reconfigured: {@code Json31.mapper()} is a shared singleton
   * in swagger-core and is also used to serialise the output.
   */
  static ObjectMapper withStablePropertyOrder(ObjectMapper mapper) {
    ObjectMapper copy = mapper.copy();
    copy.setConfig(copy.getSerializationConfig().with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
    return copy;
  }

  @Override
  public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
      if (type instanceof OpenmrsResourceAnnotatedType) {
        OpenmrsResourceAnnotatedType<?> omrsType = (OpenmrsResourceAnnotatedType<?>) type;
        DelegatingResourceHandler<?> handler = omrsType.getHandler();

        List<Schema<?>> getSchemas = new ArrayList<>();
        List<Schema<?>> writeSchemas = new ArrayList<>();
        for (Schema<?> s : resolveSchemasWithSubtypes(omrsType, context, chain)) {
          if (s.getName() != null && s.getName().contains("Get_")) {
            getSchemas.add(s);
          } else {
            writeSchemas.add(s);
          }
        }

        String resourceName = getResourceName(handler);
        ObjectSchema combinedSchema = Schemas.object();
        combinedSchema.setDescription("One of the supported representations for " + resourceName);

        List<String> abilities = new ArrayList<>();
        for (Class<?> ability : RESOURCE_ABILITY_INTERFACES) {
          if (ability.isAssignableFrom(handler.getClass())) {
            abilities.add(ability.getSimpleName());
          }
        }
        combinedSchema.addExtension("x-openmrs-resource-abilities", abilities);

        Class<?> handlerDelegateType = getDelegateType(handler);
        if (handlerDelegateType != null) {
          List<String> delegateAbilities = new ArrayList<>();
          for (Class<?> ability : DELEGATE_ABILITY_INTERFACES) {
            if (ability.isAssignableFrom(handlerDelegateType)) {
              delegateAbilities.add(ability.getSimpleName());
            }
          }
          combinedSchema.addExtension("x-openmrs-delegate-abilities", delegateAbilities);
        }

        // Build intermediary ResourceGet schema as anyOf of all ResourceGet_* schemas
        if (!getSchemas.isEmpty()) {
          ObjectSchema getSchema = Schemas.object();
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
      //
      // When used with a concrete type argument (e.g. SimpleObject<AuditInfo>), that argument
      // documents the actual shape of the map's contents (see SimpleObject's class-level javadoc
      // in the REST module) - resolve it like any other type instead of falling back to a generic
      // object schema. Raw usage and wildcard usage (SimpleObject<?>, the vast majority of the
      // codebase) still fall through to the generic schema below, since there's no real type to
      // resolve.
      if (type.getType() != null
              && SimpleObject.class.isAssignableFrom(TypeFactory.rawClass(type.getType()))) {
        if (type.getType() instanceof ParameterizedType) {
          Type typeArg = ((ParameterizedType) type.getType()).getActualTypeArguments()[0];
          if (typeArg instanceof Class && typeArg != Object.class) {
            return resolve(new AnnotatedType(typeArg), context, chain);
          }
        }
        ObjectSchema schema = Schemas.object();
        schema.additionalProperties(Boolean.TRUE);
        return schema;
      }

      // A property whose declared type is java.lang.Class must not be bean-introspected. Jackson
      // treats Class as a POJO and documents the JVM reflection API — module, classLoader,
      // recordComponents, protectionDomain and friends — as if it were part of the REST API;
      // OrderType.javaClass alone came out 87KB and 14 levels deep. That graph is also cyclic, so
      // the point where the walk gets cut off is decided by property iteration order, which makes
      // the documented subset arbitrary.
      //
      // What the API actually returns is the class name: ConversionUtil.convertToRepresentation()
      // finds no Converter for a Class and falls through to "we have no choice but to return the
      // plain object", which Jackson serialises as a string —
      // {"javaClass": "org.openmrs.DrugOrder"}.
      if (type.getType() != null && Class.class.equals(TypeFactory.rawClass(type.getType()))) {
        return Schemas.of("string").description("Fully qualified Java class name");
      }

      // Same problem, other libraries. A property whose type belongs to a third-party API gets
      // bean-introspected into that API's object graph, which then appears in the spec as though
      // it were part of the REST API:
      //
      //   Module.config / Module.sqldiff  hold config.xml and sqldiff.xml as parsed DOM
      //                                   documents, and dragged in eleven org.w3c.dom schemas
      //                                   (Node, NodeList, NamedNodeMap, TypeInfo, Attr, ...) —
      //                                   most of Module.json
      //   ServerLog.memoryAppender        is a log4j Appender, and dragged in Filter, Property,
      //                                   LayoutSerializable and ErrorHandler along with the
      //                                   appender's own lifecycle flags
      //
      // None of it has a meaningful JSON shape, so say the type is undocumented rather than
      // describe someone else's API.
      if (type.getType() != null) {
        Class<?> rawClass = TypeFactory.rawClass(type.getType());
        String library = foreignApiLabel(rawClass);
        if (library != null) {
          Schema<?> schema = Schemas.object();
          schema.setDescription("A " + rawClass.getSimpleName() + " (" + library
              + "); not part of the REST API, shape unspecified");
          schema.addExtension("x-openmrs-undocumented-type", rawClass.getName());
          return schema;
        }
      }

      // A property whose declared type is an OpenmrsObject that a REST resource documents is a
      // reference to that resource — not a bean-introspected copy of the domain class.
      //
      // Every other path already knows this: the representation path short-circuits
      // OpenmrsObject properties to getRefSchemaForResource(), and so do the custom-representation
      // path and the @Schema-annotation path. What was missing was the fall-through. Once
      // super.resolve() bean-introspects any type, Swagger walks its properties and re-enters this
      // method for each one, and an OpenmrsObject-typed property landed here with nothing to catch
      // it — so it was documented twice, once as the resource's representation schemas and once as
      // a bean schema named after the domain class.
      //
      // Two things went wrong as a result:
      //
      //   Correctness. Both live in one flat components/schemas namespace keyed by simple name, so
      //   whichever was written last won. For User, Privilege, Module, TaskDefinition and VisitType
      //   the resource's anyOf-over-representations won, and Concept.creator — a read-only audit
      //   field — resolved to anyOf[UserGet, UserCreate, UserUpdate], a union including two write
      //   payload shapes. For Concept, ConceptName, Drug and 12 more the bean schema won, and the
      //   resource's own union was displaced by a copy of the domain class.
      //
      //   Size. The domain classes are mutually recursive (Concept <-> ConceptName <->
      //   ConceptDescription <-> Drug <-> ConceptAttribute), so bean introspection multiplied:
      //   one $ref to the bean Concept expanded to 18,228 nodes against 4 for ConceptGet_ref, and
      //   the whole document expanded to 975,834 nodes against 52,540 once this branch exists.
      //
      // REF is the representation, matching every other nested-resource site — it is what
      // ConversionUtil.convertToRepresentation() returns for an OpenmrsObject with no sub-rep.
      //
      // The test is "a resource documents this class", not "this class is an OpenmrsObject".
      // Those are not the same set: ModuleResource1_8's supported class is
      // org.openmrs.module.Module, a module descriptor that implements no OpenmrsObject interface,
      // and ModuleAction.modules[].advicePoints[] was still bean-introspecting it. Asking the
      // resource registry directly also means the lookup handles the cases where the class and its
      // resource are named differently (Allergy -> PatientAllergyResource2_0,
      // GlobalProperty -> SystemSettingResource1_9) for free, since getResourceSpecFilename()
      // already mirrors RestServiceImpl.getResourceBySupportedClass().
      //
      // A null return means no resource documents the class, and those types must keep falling
      // through to bean introspection rather than become unresolvedTypeSchema() — 16 schemas in
      // webservices.rest depend on that. SimpleObject, java.lang.Class and foreign-API types are
      // already handled above, so they never reach here.
      if (type.getType() != null
              && getResourceSpecFilename(type.getType(), Representation.REF) != null) {
        return getRefSchemaForResource(type.getType(), Representation.REF);
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
  /**
   * Generates a resource's schemas, folding in its subclass handlers when it has a class hierarchy.
   * <p>
   * A {@code DelegatingSubclassHandler} is not a resource: it has no REST name and no routes of its
   * own. {@code BaseDelegatingResource} binds it to a parent resource and dispatches on a
   * {@code type} discriminator — {@code asRepresentation()} asks
   * {@code getResourceHandler(delegate)} for the description, then {@code maybeDecorateWithType()}
   * stamps the response with {@code handler.getTypeName()}. So {@code v1/order} really returns one
   * of {@code order}, {@code drugorder} or {@code testorder}, and that is what gets documented:
   * one variant schema per type, unioned under the resource's own schema name.
   * <p>
   * {@code oneOf} plus a {@code discriminator} rather than {@code anyOf}, because the subclass
   * shapes are strict supersets of the base — a {@code drugorder} payload also validates against
   * the base schema, so a plain union would be true but useless. Pinning {@code type} to a
   * {@code const} per variant makes exactly one branch match and lets tooling resolve the type
   * directly.
   * <p>
   * {@code Update} is the exception. There {@code type} is optional — {@code DelegatingCrudResource.update()}
   * only validates it against the existing object when supplied, and rejects a change of type — so
   * a discriminator cannot be relied on to select a branch and {@code anyOf} is the accurate
   * statement.
   */
  private <T> List<Schema<?>> resolveSchemasWithSubtypes(OpenmrsResourceAnnotatedType<T> type,
      ModelConverterContext context, Iterator<ModelConverter> chain) {
    List<DelegatingSubclassHandler<?, ?>> subclassHandlers = type.getSubclassHandlers();
    if (subclassHandlers.isEmpty()) {
      return resolveRepresentationSchemasForResource(type, context, chain);
    }

    DelegatingResourceHandler<T> handler = type.getHandler();
    String resourceName = getResourceName(handler);

    // kind ("Get_default", "Create", …) -> type name -> that type's schema
    Map<String, Map<String, Schema<?>>> byKind = new LinkedHashMap<>();
    collectVariant(byKind, resourceName, getBaseTypeName(handler),
        resolveRepresentationSchemasForResource(type, context, chain));
    for (DelegatingSubclassHandler<?, ?> subclassHandler : subclassHandlers) {
      @SuppressWarnings({ "unchecked", "rawtypes" })
      OpenmrsResourceAnnotatedType<?> variantType = new OpenmrsResourceAnnotatedType(
          (DelegatingResourceHandler<?>) subclassHandler, null, resourceName);
      collectVariant(byKind, resourceName, getSubclassTypeName(subclassHandler),
          resolveRepresentationSchemasForResource(variantType, context, chain));
    }

    List<Schema<?>> ret = new ArrayList<>();
    for (Map.Entry<String, Map<String, Schema<?>>> entry : byKind.entrySet()) {
      String kind = entry.getKey();
      Map<String, Schema<?>> variants = entry.getValue();
      // type is stamped on every response and required on create; on update it may be omitted.
      boolean typeAlwaysPresent = !"Update".equals(kind);

      for (Map.Entry<String, Schema<?>> variant : variants.entrySet()) {
        addTypeProperty(variant.getValue(), variant.getKey(), typeAlwaysPresent);
      }

      if (variants.size() == 1) {
        // Only one type describes this kind — nothing to union.
        Schema<?> only = variants.values().iterator().next();
        only.name(resourceName + kind);
        ret.add(only);
        continue;
      }
      ret.add(buildTypeUnion(resourceName, kind, variants, typeAlwaysPresent, context));
    }
    return ret;
  }

  /** Files each schema under its kind (the part of the name after the resource name). */
  private static void collectVariant(Map<String, Map<String, Schema<?>>> byKind, String resourceName,
      String typeName, List<Schema<?>> schemas) {
    for (Schema<?> schema : schemas) {
      String name = schema.getName();
      if (name == null || !name.startsWith(resourceName)) {
        continue;
      }
      byKind.computeIfAbsent(name.substring(resourceName.length()), k -> new LinkedHashMap<>())
          .put(typeName, schema);
    }
  }

  /**
   * Defines each variant in the context under {@code <Resource><Kind>_<type>} and returns the union
   * that carries the resource's own schema name. Variants are defined rather than returned so that
   * the {@code <Resource>Get} anyOf built by {@link #resolve} lists only the unions.
   */
  private static Schema<?> buildTypeUnion(String resourceName, String kind,
      Map<String, Schema<?>> variants, boolean typeAlwaysPresent, ModelConverterContext context) {
    ObjectSchema union = Schemas.object();
    union.name(resourceName + kind);
    union.setDescription("One of the " + resourceName + " subtypes, selected by '" + TYPE_PROPERTY + "'");
    Discriminator discriminator = new Discriminator().propertyName(TYPE_PROPERTY);

    for (Map.Entry<String, Schema<?>> entry : variants.entrySet()) {
      String typeName = entry.getKey();
      Schema<?> variant = entry.getValue();
      String variantName = resourceName + kind + "_" + typeName;
      variant.name(variantName);
      variant.addExtension("x-openmrs-type", typeName);
      context.defineModel(variantName, variant);

      String ref = "#/schemas/" + variantName;
      if (typeAlwaysPresent) {
        union.addOneOfItem(Schemas.ref(ref));
        discriminator.mapping(typeName, ref);
      } else {
        union.addAnyOfItem(Schemas.ref(ref));
      }
    }
    if (typeAlwaysPresent) {
      union.setDiscriminator(discriminator);
    }
    return union;
  }

  /** Pins the discriminator to one value on a variant schema. */
  private static void addTypeProperty(Schema<?> variant, String typeName, boolean required) {
    if (variant.getProperties() != null && variant.getProperties().containsKey(TYPE_PROPERTY)) {
      return;
    }
    // A single-value enum rather than 3.1's `const`: the spec is written with
    // io.swagger.v3.core.util.Json's 3.0 mapper, which silently drops `const`. `enum` expresses the
    // same constraint and survives both writers.
    Schema<String> typeSchema = Schemas.<String>of("string")
        .description("Discriminator identifying this subtype");
    typeSchema.addEnumItemObject(typeName);
    variant.addProperty(TYPE_PROPERTY, typeSchema);
    if (required) {
      variant.addRequiredItem(TYPE_PROPERTY);
    }
  }

  /**
   * The type name of the resource's own class, which {@code BaseDelegatingResource.getTypeName()}
   * derives from the last segment of the REST name ("v1/order" -> "order").
   */
  static String getBaseTypeName(DelegatingResourceHandler<?> handler) {
    Class<?> cls = handler.getClass();
    while (cls != null) {
      org.openmrs.module.webservices.rest.web.annotation.Resource ann =
          cls.getAnnotation(org.openmrs.module.webservices.rest.web.annotation.Resource.class);
      if (ann != null && !ann.name().isEmpty()) {
        return ann.name().substring(ann.name().lastIndexOf('/') + 1);
      }
      cls = cls.getSuperclass();
    }
    return getResourceName(handler).toLowerCase();
  }

  /** {@code getTypeName()} can throw for a handler not expecting to run outside a request. */
  static String getSubclassTypeName(DelegatingSubclassHandler<?, ?> handler) {
    try {
      String name = handler.getTypeName();
      if (name != null && !name.isEmpty()) {
        return name;
      }
    } catch (RuntimeException e) {
      log.warn("getTypeName() failed for {}", handler.getClass().getName(), e);
    }
    return handler.getClass().getSimpleName().toLowerCase();
  }

  public <T> List<Schema<?>> resolveRepresentationSchemasForResource(OpenmrsResourceAnnotatedType<T> type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    DelegatingResourceHandler<T> handler = type.getHandler();
    String schemaName = type.getSchemaName() != null ? type.getSchemaName() : getResourceName(handler);
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
          repResourceSchema.name(schemaName + "Get_" + rep.getRepresentation());
          ret.add(repResourceSchema);
          generatedReps.add(rep.getRepresentation());
        }
      } catch (RuntimeException e) {
        log.warn("Error generating schema for " + schemaName + " representation " + rep.getRepresentation(), e);
      }
    }

    // scan through methods in handler and look for ones with @RepHandler annotation.
    // mirrors BaseDelegatingResource.asRepresentation(): @RepHandler is only used when
    // getRepresentationDescription() returns null for the representation it covers.
    // Class.getMethods() has no defined order and genuinely varies between JVM runs, which made
    // the generated anyOf lists differ run-to-run. Sort so the output is reproducible.
    Method[] methods = handler.getClass().getMethods();
    Arrays.sort(methods, Comparator.comparing(Method::getName).thenComparing(Method::toString));
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
        repResourceSchema.name(schemaName + "Get_" + repName);
        ret.add(repResourceSchema);
        generatedReps.add(repName);
      }
    }

    // ========== CUSTOM representation ==========
    try {
      ObjectSchema customSchema = resolveCustomRepresentationSchema(handler, context, chain);
      if (customSchema != null) {
        customSchema.addExtension("x-openmrs-representation", "custom");
        customSchema.name(schemaName + "Get_custom");
        ret.add(customSchema);
        generatedReps.add("custom");
      }
    } catch (RuntimeException e) {
      log.warn("Error generating custom representation schema for " + schemaName, e);
    }

    // ========== CREATE representations ==========
    DelegatingResourceDescription createDesc = getCreateDescription(handler);
    if(createDesc != null) {
      try {
        Schema<?> createResourceSchema = resolveSchemaForResourceDescription(handler, createDesc, true, context, chain);
        if (createResourceSchema != null) {
          createResourceSchema.name(schemaName + "Create");
          ret.add(createResourceSchema);
          generatedReps.add("create");
        }
      } catch (RuntimeException e) {
        log.warn("Error generating schema for " + schemaName + " create representation", e);
      }
    }

    // ========== UPDATE representations ==========
    DelegatingResourceDescription updateDesc = getUpdatableDescription(handler);
    if(updateDesc != null) {
      try {
        Schema<?> updateResourceSchema = resolveSchemaForResourceDescription(handler, updateDesc, true, context, chain);
        if (updateResourceSchema != null) {
          updateResourceSchema.name(schemaName + "Update");
          ret.add(updateResourceSchema);
          generatedReps.add("update");
        }
      } catch (RuntimeException e) {
        log.warn("Error generating schema for " + schemaName + " update representation", e);
      }

    }

    log.info("Generated schemas for " + schemaName + " representations: " + StringUtils.join(generatedReps, ", "));

    return ret;
  }

  /**
   * Whether a {@code <Resource>Create} schema will be generated for this handler. Uses exactly the
   * predicate schema generation uses, so a path can never end up referencing a schema that was
   * never defined.
   */
  public static boolean hasCreateSchema(DelegatingResourceHandler<?> handler) {
    return getCreateDescription(handler) != null;
  }

  /**
   * Whether a {@code <Resource>Update} schema will be generated for this handler.
   *
   * @see #hasCreateSchema
   */
  public static boolean hasUpdateSchema(DelegatingResourceHandler<?> handler) {
    return getUpdatableDescription(handler) != null;
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

    ObjectSchema objectSchema = Schemas.object();
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
          Schema<?> annotationSchema = resolveSchemaFromSwaggerAnnotations(annotatedGetter, rep, context, chain);
          if (annotationSchema != null) return annotationSchema;
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
        ArraySchema arraySchema = Schemas.array();
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
      // TODO: remove this after plan-representation-typing.md is done
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
    // Walk most-derived -> base using putIfAbsent so the most-derived handler's getter wins.
    Class<?> handlerClass = handler.getClass();
    while (handlerClass != null && !handlerClass.equals(Object.class)) {
      // getDeclaredMethods() has no defined order, and insertion order here becomes the property
      // order of the custom representation schema. Sort, as the @RepHandler scan above does.
      Method[] declared = handlerClass.getDeclaredMethods();
      Arrays.sort(declared, Comparator.comparing(Method::getName).thenComparing(Method::toString));
      for (Method m : declared) {
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

    ObjectSchema schema = Schemas.object();
    for (Map.Entry<String, Method> entry : props.entrySet()) {
      String propName = entry.getKey();
      Method method = entry.getValue();
      Type propType = method.getGenericReturnType();
      try {
        // Check for swagger annotations on the method first — they take precedence over
        // the declared return type (useful for @PropertyGetters returning Object).
        Schema<?> propSchema = resolveSchemaFromSwaggerAnnotations(method, Representation.REF, context, chain);
        if (propSchema != null) {
          schema.addProperty(propName, propSchema);
          continue;
        }
        if (isOpenmrsObject(propType)) {
          propSchema = getRefSchemaForResource(propType, Representation.REF);
        } else if (isCollection(propType)) {
          ArraySchema arraySchema = Schemas.array();
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
          // inline schema registration for PersonAttribute -> Person -> Concept -> ...
          ObjectSchema mapSchema = Schemas.object();
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
   * Reads schema metadata from the @PropertyGetter fields on a getter method and builds the
   * corresponding OpenAPI schema. Returns null when no schema hint is present, so the caller
   * falls through to normal type-based resolution.
   *
   * Priority: arraySchema field > schema field. Within schema: implementation > anyOf/oneOf.
   *
   * Using fully qualified annotation names to avoid collision with io.swagger.v3.oas.models.media.Schema.
   */
  private Schema<?> resolveSchemaFromSwaggerAnnotations(Method getter, Representation propertyRep,
      ModelConverterContext context, Iterator<ModelConverter> chain) {

    PropertyGetter pg = getter.getAnnotation(PropertyGetter.class);
    if (pg == null) return null;

    /*
     * TODO: Uncomment this when this is fixed: https://openmrs.atlassian.net/browse/RESTWS-1042

    // arraySchema() and schema() were added to @PropertyGetter in REST module 4.0+.
    // Older modules compile against a version that lacks these elements; calling them
    // throws AbstractMethodError. Treat that as "no hint present".
    try {
      // arraySchema field takes precedence
      io.swagger.v3.oas.annotations.media.Schema itemAnn = pg.arraySchema().schema();
      if (itemAnn.anyOf().length > 0 || itemAnn.oneOf().length > 0
          || itemAnn.implementation() != Void.class) {
        Schema<?> itemSchema = buildSchemaFromAnnotation(itemAnn, propertyRep, context, chain);
        if (itemSchema != null) {
          ArraySchema arr = Schemas.array();
          arr.items(itemSchema);
          return arr;
        }
      }

      // schema field
      io.swagger.v3.oas.annotations.media.Schema schemaAnn = pg.schema();
      if (schemaAnn.implementation() != Void.class
          || schemaAnn.anyOf().length > 0 || schemaAnn.oneOf().length > 0) {
        return buildSchemaFromAnnotation(schemaAnn, propertyRep, context, chain);
      }
    } catch (NoSuchMethodError | AbstractMethodError ignored) {
      // @PropertyGetter on this module's REST version has no schema()/arraySchema() elements
    }
    */

    return null;
  }

  /**
   * Builds an OpenAPI Schema from a @Schema annotation.
   *
   * - implementation: emits a cross-file $ref for OpenmrsObject types using propertyRep,
   *   falling back to REF. For non-OpenmrsObject types, resolves normally.
   * - anyOf / oneOf: builds an anyOf schema; OpenmrsObject items use propertyRep for their $ref,
   *   everything else resolves normally.
   */
  private Schema<?> buildSchemaFromAnnotation(
      io.swagger.v3.oas.annotations.media.Schema ann,
      Representation propertyRep, ModelConverterContext context, Iterator<ModelConverter> chain) {

    if (ann.implementation() != Void.class) {
      Class<?> cls = ann.implementation();
      if (isOpenmrsObject(cls)) {
        return getRefSchemaForResource(cls, propertyRep != null ? propertyRep : Representation.REF);
      }
      return resolve(new AnnotatedType(cls), context, chain);
    }

    Class<?>[] types = ann.anyOf().length > 0 ? ann.anyOf() : ann.oneOf();
    if (types.length == 0) return null;

    @SuppressWarnings("rawtypes")
    List<Schema> anyOfItems = new ArrayList<>();
    for (Class<?> type : types) {
      if (isOpenmrsObject(type)) {
        anyOfItems.add(getRefSchemaForResource(type, propertyRep != null ? propertyRep : Representation.REF));
      } else {
        Schema<?> s = resolve(new AnnotatedType(type), context, chain);
        if (s != null) anyOfItems.add(s);
      }
    }
    Schema<Object> result = new Schema<>();
    result.anyOf(anyOfItems);
    return result;
  }

  /**
   * Returns a schema for a UUID string field that references another REST resource.
   * Uses {type: string, description: "UUID of <ResourceName>", x-openmrs-resource: "<ResourceName>"}
   * so both human readers and tooling can identify which resource the UUID points to.
   */
  private static Schema<?> uuidRefSchema(Type delegateType) {
    String resourceName = TypeFactory.rawClass(delegateType).getSimpleName();
    Schema<?> schema = Schemas.of("string").description("UUID of " + resourceName).format("uuid");
    schema.addExtension("x-openmrs-resource", resourceName);
    return schema;
  }

  private Schema<?> getRefSchemaForResource(Type delegateType, Representation rep) {
    Class<?> rawClass = TypeFactory.rawClass(delegateType);
    String target = getResourceSpecFilename(delegateType, rep);
    if (target == null) {
      return unresolvedTypeSchema(rawClass);
    }
    Schema<?> ret = new Schema<>();
    ret.$ref(target);
    return ret;
  }

  /**
   * The {@code $ref} for a property whose declared type is {@code delegateType}, or null when no
   * resource documents that type.
   * <p>
   * The target is the <em>resource that supports the class</em>, not the class itself. Those differ
   * more often than they look: {@code Allergy} is documented by {@code PatientAllergyResource2_0},
   * {@code PatientProgram} by {@code ProgramEnrollmentResource}, {@code GlobalProperty} by
   * {@code SystemSettingResource1_9}. Naming the file after the class produced
   * {@code ./Allergy.json}, which is never written — 17 of the spec's dangling refs came from here.
   * <p>
   * Lookup mirrors {@code RestServiceImpl.getResourceBySupportedClass()}: exact supported class
   * first, then the most-derived supported superclass. That second step is what resolves
   * {@code ConceptNumeric} to the {@code Concept} resource, and a subclass-handled class such as
   * {@code DrugOrder} to {@code Order} — where it can be pinned further to that subtype's variant.
   */
  public static String getResourceSpecFilename(Type delegateType, Representation rep) {
    Class<?> rawClass = TypeFactory.rawClass(delegateType);

    String variant = VARIANT_BY_SUBCLASS.get(rawClass);
    if (variant != null) {
      String parent = PARENT_BY_SUBCLASS.get(rawClass);
      return "./" + parent + ".json#/schemas/" + parent + "Get_" + rep.getRepresentation()
          + "_" + variant;
    }

    String resourceName = resourceNameFor(rawClass);
    if (resourceName == null) {
      return null;
    }
    return "./" + resourceName + ".json#/schemas/" + resourceName + "Get_" + rep.getRepresentation();
  }

  /** @see RestServiceImpl#getResourceBySupportedClass */
  private static String resourceNameFor(Class<?> rawClass) {
    String exact = RESOURCE_NAME_BY_SUPPORTED_CLASS.get(rawClass);
    if (exact != null) {
      return exact;
    }
    Class<?> best = null;
    for (Class<?> supported : RESOURCE_NAME_BY_SUPPORTED_CLASS.keySet()) {
      if (supported.isAssignableFrom(rawClass) && (best == null || best.isAssignableFrom(supported))) {
        best = supported;
      }
    }
    return best == null ? null : RESOURCE_NAME_BY_SUPPORTED_CLASS.get(best);
  }

  /**
   * Stands in for a property whose type no resource documents — {@code AllergyReaction},
   * {@code ConceptAnswer} and the abstract {@code Attribute}/{@code AttributeType} bases have no
   * REST resource of their own. Emitting a {@code $ref} to a file that is never written is worse
   * than admitting the type is undocumented: it makes the whole document unloadable in a strict
   * consumer.
   */
  private static Schema<?> unresolvedTypeSchema(Class<?> rawClass) {
    Schema<?> schema = Schemas.object();
    schema.setDescription("A " + rawClass.getSimpleName()
        + ", which no REST resource documents; shape unspecified");
    schema.addExtension("x-openmrs-undocumented-type", rawClass.getName());
    return schema;
  }

  /**
   * Registers what each domain class is documented by, so {@code $ref} targets name a resource that
   * actually exists. Must run before any schema is resolved.
   *
   * @param handlers all discovered resource handlers, dependencies included — a cross-module ref
   *            target is usually a dependency's resource
   * @param subclassHandlers parent resource handler -> the subclass handlers bound to it
   */
  public static void registerResourceNames(List<DelegatingResourceHandler<?>> handlers,
      Map<DelegatingResourceHandler<?>, List<DelegatingSubclassHandler<?, ?>>> subclassHandlers) {
    RESOURCE_NAME_BY_SUPPORTED_CLASS.clear();
    PARENT_BY_SUBCLASS.clear();
    VARIANT_BY_SUBCLASS.clear();

    for (DelegatingResourceHandler<?> handler : handlers) {
      Class<?> supported = supportedClassOf(handler);
      if (supported != null && !RESOURCE_NAME_BY_SUPPORTED_CLASS.containsKey(supported)) {
        RESOURCE_NAME_BY_SUPPORTED_CLASS.put(supported, getResourceName(handler));
      }
    }
    for (Map.Entry<DelegatingResourceHandler<?>, List<DelegatingSubclassHandler<?, ?>>> entry
        : subclassHandlers.entrySet()) {
      String parent = getResourceName(entry.getKey());
      for (DelegatingSubclassHandler<?, ?> subclassHandler : entry.getValue()) {
        Class<?> subclass = subclassHandler.getSubclassHandled();
        if (subclass != null && !VARIANT_BY_SUBCLASS.containsKey(subclass)) {
          PARENT_BY_SUBCLASS.put(subclass, parent);
          VARIANT_BY_SUBCLASS.put(subclass, getSubclassTypeName(subclassHandler));
        }
      }
    }
  }

  /** The {@code supportedClass} of a resource or sub-resource, or null for neither. */
  private static Class<?> supportedClassOf(DelegatingResourceHandler<?> handler) {
    org.openmrs.module.webservices.rest.web.annotation.Resource resource = handler.getClass()
        .getAnnotation(org.openmrs.module.webservices.rest.web.annotation.Resource.class);
    if (resource != null) {
      return resource.supportedClass();
    }
    org.openmrs.module.webservices.rest.web.annotation.SubResource sub = subResourceAnnotation(handler);
    return sub == null ? null : sub.supportedClass();
  }

  /**
   * The name a handler's schemas and output file are keyed by, derived by stripping the trailing
   * {@code Resource<version>} suffix from its class name
   * (e.g. {@code PersonNameResource1_8} -> {@code PersonName}).
   * <p>
   * The suffix is anchored at the end rather than matched at its first occurrence, because a
   * sub-resource class legitimately contains "Resource" twice: truncating at the first one made
   * {@code FormResourceResource1_9} and {@code FormResource1_9} both claim the name "Form", so the
   * sub-resource silently overwrote the parent resource's file. Anchoring at the end also stops a
   * class whose name begins with "Resource" from yielding an empty name.
   */
  public static String getResourceName(DelegatingResourceHandler<?> handler) {
      return stripResourceSuffix(handler.getClass().getSimpleName());
  }

  /**
   * The name of a sub-resource's parent resource (e.g. "Person" for {@code PersonNameResource1_8}),
   * or null when the handler is not a {@code @SubResource}. Used only for human-readable summaries.
   */
  public static String getParentResourceName(DelegatingResourceHandler<?> handler) {
      org.openmrs.module.webservices.rest.web.annotation.SubResource sub = subResourceAnnotation(handler);
      return sub == null ? null : stripResourceSuffix(sub.parent().getSimpleName());
  }

  /**
   * The {@code @SubResource} annotation declared on the handler's own class, or null. The
   * annotation is not {@code @Inherited} and the REST module reads it off the concrete class too
   * ({@code DelegatingSubResource.getUri()}), so this deliberately does not walk superclasses.
   */
  private static org.openmrs.module.webservices.rest.web.annotation.SubResource subResourceAnnotation(
          DelegatingResourceHandler<?> handler) {
      return handler.getClass().getAnnotation(
          org.openmrs.module.webservices.rest.web.annotation.SubResource.class);
  }

  private static String stripResourceSuffix(String className) {
      Matcher matcher = RESOURCE_SUFFIX.matcher(className);
      if (matcher.find() && matcher.start() > 0) {
          return className.substring(0, matcher.start());
      }
      return className;
  }

  /**
   * Third-party APIs that must never be bean-introspected into the spec, mapped to the name used
   * to describe them. A type is matched by its own package or by any it inherits from, so an
   * OpenMRS subclass of a foreign base — {@code MemoryAppender extends AbstractAppender} — is
   * caught too.
   */
  private static final Map<String, String> FOREIGN_API_PACKAGES = new LinkedHashMap<String, String>() {
    {
      put("org.w3c.dom.", "W3C DOM");
      put("org.apache.logging.log4j.", "log4j");
      put("org.apache.log4j.", "log4j");
    }
  };

  /** The library a class belongs to if it is a foreign API type, else null. */
  private static String foreignApiLabel(Class<?> rawClass) {
    for (Class<?> current = rawClass; current != null && !Object.class.equals(current);
        current = current.getSuperclass()) {
      String label = packageLabel(current);
      if (label != null) {
        return label;
      }
      for (Class<?> implemented : current.getInterfaces()) {
        label = packageLabel(implemented);
        if (label != null) {
          return label;
        }
      }
    }
    return null;
  }

  private static String packageLabel(Class<?> cls) {
    for (Map.Entry<String, String> entry : FOREIGN_API_PACKAGES.entrySet()) {
      if (cls.getName().startsWith(entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  /**
   * Returns whether the input class is an OpenmrsObject (i.e. Patient, Visit, Encounter, etc...)
   * Note that this is NOT the same as REST resources (i.e. PatientResource1_9)
   */
  private boolean isOpenmrsObject(Type t) {
    return OpenmrsObject.class.isAssignableFrom(TypeFactory.rawClass(t));
  }

  /**
   * Resolves the schema for a @RepHandler-annotated method by reflecting on its declared return type.
   */
  private Schema<?> resolveSchemaForRepHandlerMethod(Method method, DelegatingResourceHandler<?> handler,
      ModelConverterContext context, Iterator<ModelConverter> chain) {
    return resolve(new AnnotatedType(method.getGenericReturnType()), context, chain);
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
   * {@code @Resource} annotation (e.g. "v1/visit" -> "/ws/rest/v1/visit").
   * All paths use the /ws servlet prefix to match the actual OpenMRS mount point.
   * <p>
   * A {@code @SubResource} has no {@code @Resource} of its own; it is reached through its parent
   * and served by {@code MainSubResourceController}, whose routes are
   * {@code /{resource}/{parentUuid}/{subResource}}. So the path is the parent's name, the parent
   * UUID, then the sub-resource's path (e.g. "/ws/rest/v1/person/{parentUuid}/name").
   * <p>
   * Falls back to lowercasing the resource name if no annotation is found.
   */
  public static String getResourceRestPath(DelegatingResourceHandler<?> handler) {
    org.openmrs.module.webservices.rest.web.annotation.SubResource sub = subResourceAnnotation(handler);
    if (sub != null) {
      org.openmrs.module.webservices.rest.web.annotation.Resource parent = sub.parent()
          .getAnnotation(org.openmrs.module.webservices.rest.web.annotation.Resource.class);
      if (parent != null && !parent.name().isEmpty()) {
        return "/ws/rest/" + parent.name() + "/{parentUuid}/" + sub.path();
      }
      // System.out, not the logger: SLF4J from inside the isolated generator classloader does
      // not reach Maven's output, and a sub-resource landing on a flat path is worth seeing.
      System.out.println("WARN  sub-resource " + handler.getClass().getName() + " names parent "
          + sub.parent().getName() + ", which carries no @Resource annotation; "
          + "falling back to a flat path");
    }
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
