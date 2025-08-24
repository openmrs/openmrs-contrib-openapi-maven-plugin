/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.plugin.rest.analyzer.introspection;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openmrs.module.webservices.rest.web.annotation.PropertyGetter;
import org.openmrs.module.webservices.rest.web.annotation.PropertySetter;
import org.openmrs.module.webservices.rest.web.resource.api.Resource;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceHandler;
import org.openmrs.module.webservices.rest.web.resource.impl.DelegatingResourceDescription;
import org.openmrs.module.webservices.rest.web.representation.Representation;
import org.springframework.beans.BeanUtils;

/**
 * Default implementation of {@link SchemaIntrospectionService}
 */
public class SchemaIntrospectionServiceImpl implements SchemaIntrospectionService {
	
	protected final Logger log = LoggerFactory.getLogger(getClass());
	
	/**
	 * @see org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionService#getDelegateType(Resource)
	 */
	@Override
	public Class<?> getDelegateType(Resource resource) {
		if (resource == null) {
			return null;
		}
		
		if (!(resource instanceof DelegatingResourceHandler)) {
			log.warn("Resource " + resource.getClass().getName() + " is not a DelegatingResourceHandler");
			return null;
		}
		
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
	 * @see org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionService#discoverAvailableProperties(Class)
	 */
	@Override
	public Map<String, String> discoverAvailableProperties(Class<?> delegateType) {
		if (delegateType == null) {
			return new HashMap<String, String>();
		}
		
		Map<String, String> properties = new HashMap<String, String>();
		
		Class<?> currentClass = delegateType;
		while (currentClass != null && !currentClass.equals(Object.class)) {
			processFields(currentClass, properties);
			currentClass = currentClass.getSuperclass();
		}
		
		PropertyDescriptor[] propertyDescriptors = BeanUtils.getPropertyDescriptors(delegateType);
		for (PropertyDescriptor descriptor : propertyDescriptors) {
			if ("class".equals(descriptor.getName()) || descriptor.getReadMethod() == null) {
				continue;
			}
			
			Method readMethod = descriptor.getReadMethod();
			
			if (Modifier.isPublic(readMethod.getModifiers()) && !Modifier.isStatic(readMethod.getModifiers())) {
				String typeName = getTypeName(readMethod.getGenericReturnType());
				properties.put(descriptor.getName(), typeName);
			}
		}
		
		return properties;
	}
	
	/**
	 * @see org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionService#discoverResourceProperties(Resource)
	 */
	@Override
	public Map<String, String> discoverResourceProperties(Resource resource) {
		Class<?> delegateType = getDelegateType(resource);
		Map<String, String> properties = discoverAvailableProperties(delegateType);
		
		if (resource != null) {
			discoverAnnotatedProperties(resource.getClass(), properties);
		}
		
		return properties;
	}
	
	/**
	 * Discovers properties defined by PropertyGetter and PropertySetter annotations in a resource class
	 * and its superclasses
	 *
	 * @param resourceClass The resource class to scan for annotations
	 * @param properties The map to add discovered properties to
	 */
	private void discoverAnnotatedProperties(Class<?> resourceClass, Map<String, String> properties) {
		Class<?> currentClass = resourceClass;
		while (currentClass != null && !currentClass.equals(Object.class)) {
			for (Method method : currentClass.getDeclaredMethods()) {
				PropertyGetter getter = method.getAnnotation(PropertyGetter.class);
				if (getter != null) {
					String propertyName = getter.value();
					Type returnType = method.getGenericReturnType();
					properties.put(propertyName, getTypeName(returnType));
				}
				
				PropertySetter setter = method.getAnnotation(PropertySetter.class);
				if (setter != null && method.getParameterTypes().length > 1) {
					String propertyName = setter.value();
					Type paramType = method.getGenericParameterTypes()[1];
					if (!properties.containsKey(propertyName)) {
						properties.put(propertyName, getTypeName(paramType));
					}
				}
			}
			currentClass = currentClass.getSuperclass();
		}
	}
	
	/**
	 * Helper method to process fields from a class and add them to the properties map
	 * 
	 * @param clazz The class to process fields from
	 * @param properties The map to add properties to
	 */
	private void processFields(Class<?> clazz, Map<String, String> properties) {
		Field[] fields = clazz.getDeclaredFields();
		for (Field field : fields) {
			if (Modifier.isPublic(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
				String typeName = getTypeName(field.getGenericType());
				properties.put(field.getName(), typeName);
			}
		}
	}
	
	/**
	 * Helper method to get a user-friendly type name from a Type object
	 * 
	 * @param type The type to get a name for
	 * @return A user-friendly type name string
	 */
	/**
	 * Enhanced type name resolution that preserves collection type accuracy.
	 * Properly handles Set vs List distinctions and generic type parameters.
	 */
	private String getTypeName(Type type) {
		if (type instanceof Class) {
			Class<?> clazz = (Class<?>) type;
			
			// Preserve collection interface names for accuracy
			if (Set.class.isAssignableFrom(clazz)) {
				return "Set<Object>"; // Default when no generic info available
			} else if (List.class.isAssignableFrom(clazz)) {
				return "List<Object>"; // Default when no generic info available
			} else if (Collection.class.isAssignableFrom(clazz)) {
				return "Collection<Object>"; // Default when no generic info available
			}
			
			return clazz.getSimpleName();
		} else if (type instanceof ParameterizedType) {
			ParameterizedType paramType = (ParameterizedType) type;
			Type rawType = paramType.getRawType();
			Type[] typeArgs = paramType.getActualTypeArguments();
			
			StringBuilder sb = new StringBuilder();
			if (rawType instanceof Class) {
				Class<?> rawClass = (Class<?>) rawType;
				
				// Use proper collection interface names
				if (Set.class.isAssignableFrom(rawClass)) {
					sb.append("Set");
				} else if (List.class.isAssignableFrom(rawClass)) {
					sb.append("List");
				} else if (Collection.class.isAssignableFrom(rawClass)) {
					sb.append("Collection");
				} else {
					sb.append(rawClass.getSimpleName());
				}
			} else {
				sb.append(rawType.toString());
			}
			
			if (typeArgs.length > 0) {
				sb.append("<");
				for (int i = 0; i < typeArgs.length; i++) {
					if (i > 0) {
						sb.append(", ");
					}
					if (typeArgs[i] instanceof Class) {
						sb.append(((Class<?>) typeArgs[i]).getSimpleName());
					} else if (typeArgs[i] instanceof ParameterizedType) {
						// Recursively handle nested generic types
						sb.append(getTypeName(typeArgs[i]));
					} else {
						sb.append(typeArgs[i].toString());
					}
				}
				sb.append(">");
			}
			
			return sb.toString();
		} else {
			return type.toString();
		}
	}
	
	/**
	 * @see org.openmrs.plugin.rest.analyzer.introspection.SchemaIntrospectionService#determineAccuratePropertyType(String, DelegatingResourceDescription.Property, DelegatingResourceHandler, Map)
	 */
	@Override
	public String determineAccuratePropertyType(String propertyName, 
	                                          DelegatingResourceDescription.Property property,
	                                          DelegatingResourceHandler<?> handler,
	                                          Map<String, String> introspectedProperties) {
		
		log.debug("=== RESOLVING TYPE FOR PROPERTY: {} ===", propertyName);
		log.debug("Handler class: {}", handler.getClass().getSimpleName());
		log.debug("Introspected properties contains '{}': {}", propertyName, introspectedProperties.containsKey(propertyName));
		
		if (introspectedProperties.containsKey(propertyName)) {
			String introspectedType = introspectedProperties.get(propertyName);
			log.debug("STRATEGY 1 SUCCESS - Found introspected type for '{}': {}", propertyName, introspectedType);
			return introspectedType;
		}
		
		String representationType = resolveFromRepresentationMetadata(propertyName, property, handler);
		if (representationType != null) {
			log.debug("STRATEGY 2 RESULT - Resolved type from representation metadata for '{}': {}", propertyName, representationType);
			if (isRepresentationType(representationType)) {
				log.debug("STRATEGY 2 REJECTED - '{}' is a representation type, not a Java type", representationType);
			} else {
				log.debug("STRATEGY 2 SUCCESS - Using representation metadata type: {}", representationType);
				return representationType;
			}
		}
		
		log.debug("STRATEGY 3 - Attempting direct reflection on delegate class...");
		String reflectedType = reflectPropertyType(propertyName, handler);
		if (reflectedType != null) {
			log.debug("STRATEGY 3 SUCCESS - Reflected type for '{}': {}", propertyName, reflectedType);
			return reflectedType;
		}
		
		log.debug("STRATEGY 4 - Attempting resource method resolution...");
		String resourceMethodType = resolveFromResourceMethods(propertyName, handler);
		if (resourceMethodType != null) {
			log.debug("STRATEGY 4 SUCCESS - Resolved type from resource methods for '{}': {}", propertyName, resourceMethodType);
			return resourceMethodType;
		}
		
		log.debug("STRATEGY 5 - All strategies failed, using conservative inference...");
		String inferredType = inferTypeFromPropertyName(propertyName, handler);
		log.debug("FINAL RESULT - Using conservative inference for '{}': {}", propertyName, inferredType);
		log.debug("=== TYPE RESOLUTION COMPLETE FOR: {} -> {} ===", propertyName, inferredType);
		return inferredType;
	}
	
	/**
	 * Check if the type is actually a representation type rather than a Java type
	 */
	private boolean isRepresentationType(String type) {
		return "REF".equals(type) || "DEFAULT".equals(type) || "FULL".equals(type) || 
		       "Full".equals(type) || "Default".equals(type) || "Ref".equals(type);
	}
	
	/**
	 * Examines the resource class for PropertyGetter annotated methods or custom getters
	 * to determine accurate property types.
	 */
	private String resolveFromResourceMethods(String propertyName, DelegatingResourceHandler<?> handler) {
		try {
			Class<?> resourceClass = handler.getClass();
			
			Method[] methods = resourceClass.getDeclaredMethods();
			for (Method method : methods) {
				PropertyGetter propertyGetter = method.getAnnotation(PropertyGetter.class);
				
				if (propertyGetter != null && propertyGetter.value().equals(propertyName)) {
					Type returnType = method.getGenericReturnType();
					return getTypeName(returnType);
				}
			}
			
			String getterName = "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
			try {
				// Try parameterless getter first (most common case)
				Method getter = resourceClass.getMethod(getterName);
				if (getter != null) {
					Type returnType = getter.getGenericReturnType();
					return getTypeName(returnType);
				}
			} catch (NoSuchMethodException e) {
				// Try getter with delegate type parameter (less common)
				try {
					Class<?> delegateType = getDelegateType((Resource) handler);
					if (delegateType != null) {
						Method getter = resourceClass.getMethod(getterName, delegateType);
						if (getter != null) {
							Type returnType = getter.getGenericReturnType();
							return getTypeName(returnType);
						}
					}
				} catch (NoSuchMethodException | SecurityException e2) {
					log.debug("No conventional getter found for property: {}", propertyName);
				}
			} catch (SecurityException e) {
				log.debug("Security exception accessing getter for property: {}", propertyName);
			}
			
		} catch (Exception e) {
			log.debug("Error resolving type from resource methods for '{}': {}", propertyName, e.getMessage());
		}
		
		return null;
	}
	
	/**
	 * Resolves type from DelegatingResourceDescription.Property representation metadata
	 */
	private String resolveFromRepresentationMetadata(String propertyName, 
	                                               DelegatingResourceDescription.Property property,
	                                               DelegatingResourceHandler<?> handler) {
		try {
			Class<?> convertAsType = property.getConvertAs();
			if (convertAsType != null) {
				String typeName = getTypeName(convertAsType);
				log.debug("Found convertAs type for '{}': {}", propertyName, typeName);
				return typeName;
			}
			
			Method method = property.getMethod();
			if (method != null) {
				Type returnType = method.getGenericReturnType();
				String typeName = getTypeName(returnType);
				log.debug("Found method return type for '{}': {}", propertyName, typeName);
				return typeName;
			}
			
			String delegateProperty = property.getDelegateProperty();
			if (delegateProperty != null && !delegateProperty.equals(propertyName)) {
				String delegateType = reflectDelegatePropertyType(delegateProperty, handler);
				if (delegateType != null) {
					log.debug("Found delegate property type for '{}' -> '{}': {}", propertyName, delegateProperty, delegateType);
					return delegateType;
				}
			}
			
			Representation representation = property.getRep();
			if (representation != null) {
				return resolveRepresentationType(propertyName, representation, handler);
			}
			
		} catch (Exception e) {
			log.debug("Could not resolve type from representation metadata for '{}': {}", propertyName, e.getMessage());
		}
		
		return null;
	}
	
	/**
	 * Reflects on the delegate class to get the type of a specific delegate property
	 */
	private String reflectDelegatePropertyType(String delegatePropertyName, DelegatingResourceHandler<?> handler) {
		try {
			if (!(handler instanceof Resource)) {
				return null;
			}
			
			Class<?> delegateType = getDelegateType((Resource) handler);
			if (delegateType == null) {
				return null;
			}
			
			PropertyDescriptor[] descriptors = BeanUtils.getPropertyDescriptors(delegateType);
			for (PropertyDescriptor descriptor : descriptors) {
				if (descriptor.getName().equals(delegatePropertyName) && descriptor.getReadMethod() != null) {
					Type propertyType = descriptor.getReadMethod().getGenericReturnType();
					return getTypeName(propertyType);
				}
			}
			
		} catch (Exception e) {
			log.debug("Could not reflect delegate property type for '{}': {}", delegatePropertyName, e.getMessage());
		}
		
		return null;
	}
	
	/**
	 * Resolves property type based on representation type (REF, DEFAULT, FULL)
	 */
	private String resolveRepresentationType(String propertyName, Representation representation, DelegatingResourceHandler<?> handler) {
		String reflectedType = reflectPropertyType(propertyName, handler);
		if (reflectedType != null) {
			log.debug("Found reflected type for representation property '{}': {}", propertyName, reflectedType);
			return reflectedType;
		}
		
		String safeType = resolveSafePatterns(propertyName);
		log.debug("Using safe pattern fallback for '{}': {}", propertyName, safeType);
		return safeType;
	}
	
	/**
	 * Uses reflection to get property type directly from delegate class
	 */
	private String reflectPropertyType(String propertyName, DelegatingResourceHandler<?> handler) {
		try {
			if (!(handler instanceof Resource)) {
				log.debug("Handler {} is not a Resource, skipping reflection", handler.getClass().getSimpleName());
				return null;
			}
			
			Class<?> delegateType = getDelegateType((Resource) handler);
			if (delegateType == null) {
				log.debug("No delegate type found for handler {}", handler.getClass().getSimpleName());
				return null;
			}
			
			log.debug("Reflecting property '{}' on delegate type: {}", propertyName, delegateType.getName());
			
			PropertyDescriptor[] descriptors = BeanUtils.getPropertyDescriptors(delegateType);
			log.debug("Found {} property descriptors on {}", descriptors.length, delegateType.getSimpleName());
			
			for (PropertyDescriptor descriptor : descriptors) {
				if (descriptor.getName().equals(propertyName)) {
					if (descriptor.getReadMethod() != null) {
						Type propertyType = descriptor.getReadMethod().getGenericReturnType();
						String typeName = getTypeName(propertyType);
						log.debug("FOUND property '{}' via PropertyDescriptor with type: {} (method: {})", 
						        propertyName, typeName, descriptor.getReadMethod().getName());
						return typeName;
					} else {
						log.debug("Property '{}' found but has no read method", propertyName);
					}
				}
			}
			
			log.debug("Property '{}' NOT FOUND in PropertyDescriptors on {}", propertyName, delegateType.getSimpleName());
			
		} catch (Exception e) {
			log.error("Error reflecting property type for '{}': {}", propertyName, e.getMessage(), e);
		}
		
		return null;
	}
	
	/**
	 * Uses comprehensive reflection strategies to determine accurate property types.
	 * PropertyGetter annotation scanning gets priority to ensure accurate type resolution.
	 */
	private String inferTypeFromPropertyName(String propertyName, DelegatingResourceHandler<?> handler) {
		if (propertyName == null) return "String";
		
		// STRATEGY 1: Use actual PropertyGetter annotation scanning first - highest accuracy
		String annotationBasedType = resolveFromActualPropertyGetterAnnotations(propertyName, handler);
		if (annotationBasedType != null) {
			log.debug("Found type via PropertyGetter annotations for '{}': {}", propertyName, annotationBasedType);
			return annotationBasedType;
		}
		
		// STRATEGY 2: Safe pattern matching for universal OpenMRS properties only
		String safePatternType = resolveSafePatterns(propertyName);
		log.debug("Using safe pattern type for '{}': {}", propertyName, safePatternType);
		return safePatternType;
	}
	
	/**
	 * Scans the actual resource class for PropertyGetter annotations to resolve property types.
	 * This replaces the previous hardcoded approach with dynamic annotation scanning.
	 */
	private String resolveFromActualPropertyGetterAnnotations(String propertyName, DelegatingResourceHandler<?> handler) {
		if (handler == null) {
			return null;
		}
		
		try {
			Class<?> resourceClass = handler.getClass();
			Map<String, String> annotatedProperties = new HashMap<>();
			
			// Reuse the existing discoverAnnotatedProperties method
			discoverAnnotatedProperties(resourceClass, annotatedProperties);
			
			// Check if our property was found
			String resolvedType = annotatedProperties.get(propertyName);
			if (resolvedType != null) {
				log.debug("Found property '{}' with type '{}' via PropertyGetter annotation scanning", propertyName, resolvedType);
				return resolvedType;
			}
			
			log.debug("Property '{}' not found in PropertyGetter annotations for class {}", propertyName, resourceClass.getSimpleName());
			return null;
			
		} catch (Exception e) {
			log.warn("Failed to scan PropertyGetter annotations for property '{}': {}", propertyName, e.getMessage());
			return null;
		}
	}
	
	/**
	 * Uses only very safe, unambiguous pattern matching for basic OpenMRS properties.
	 * Removed List<Object> fallback and hardcoded collection patterns that prevent proper type discovery.
	 */
	private String resolveSafePatterns(String propertyName) {
		// Safe individual property patterns - these are consistent across OpenMRS
		if (propertyName.equals("id")) {
			return "Integer";
		} else if (propertyName.equals("uuid")) {
			return "String";
		} else if (propertyName.equals("display")) {
			return "String";
		} else if (propertyName.equals("voided") || propertyName.equals("retired")) {
			return "Boolean";
		} else if (propertyName.equals("dateCreated") || propertyName.equals("dateChanged") || 
		           propertyName.equals("dateVoided") || propertyName.equals("dateRetired")) {
			return "Date";
		}
		
		// No generic fallbacks - let PropertyGetter scanning or reflection handle collections
		// Ultimate fallback only for truly unknown properties
		return "String";
	}
}