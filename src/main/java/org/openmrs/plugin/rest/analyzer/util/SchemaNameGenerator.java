package org.openmrs.plugin.rest.analyzer.util;

import org.apache.commons.lang3.StringUtils;
import java.util.Locale;

/**
 * Centralized utility for generating consistent PascalCase schema names in OpenAPI specifications.
 * This class ensures that schema definition names and $ref reference names always match,
 * preventing the broken reference issues that occur when different parts of the code
 * use different naming strategies. Uses clean PascalCase naming for better readability
 * and consistency with Java naming conventions.
 */
public final class SchemaNameGenerator {
    
    private static final Locale LOCALE = Locale.ROOT;
    
    /**
     * Transforms a resource or delegate type name to kebab-case base name.
     * This preserves the current behavior used in processResourceHandler.
     * 
     * Examples:
     * - "QueueRoomResource" -> "Queue-room"
     * - "QueueResource" -> "Queue" 
     * - "PatientResource" -> "Patient"
     * - "QueueRoom" -> "Queue-room" (for delegate types)
     * 
     * @param typeName the class simple name (e.g., "QueueRoomResource" or "QueueRoom")
     * @return kebab-case base name with first letter capitalized
     */
    public static String toKebabCase(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "Unknown";
        }
        
        String base = typeName.endsWith("Resource") 
            ? typeName.substring(0, typeName.length() - "Resource".length())
            : typeName;
        

        String kebab = base.replaceAll("([A-Z])", "-$1").toLowerCase(LOCALE);
        
        if (kebab.startsWith("-")) {
            kebab = kebab.substring(1);
        }
        
        return StringUtils.capitalize(kebab);
    }
    
    /**
     * Generates the final schema name using PascalCase convention.
     * This eliminates kebab-case issues and creates clean, readable schema names.
     * 
     * Examples:
     * - ("QueueRoomResource", "default") -> "QueueRoomDefault"
     * - ("QueueResource", "full") -> "QueueFull"
     * - ("PatientResource", "ref") -> "PatientRef"
     * - ("PatientIdentifierResource", "ref") -> "PatientIdentifierRef"
     * 
     * @param typeName the class simple name (resource or delegate type)
     * @param representation the representation name ("default", "full", "ref", etc.)
     * @return the final schema name for use in components/schemas in PascalCase
     */
    public static String schemaName(String typeName, String representation) {
        if (representation == null || representation.isEmpty()) {
            representation = "default";
        }
        
        String base = typeName.endsWith("Resource") 
            ? typeName.substring(0, typeName.length() - "Resource".length())
            : typeName;
        
        String capitalizedRep = StringUtils.capitalize(representation.toLowerCase(LOCALE));
        
        return base + capitalizedRep;
    }
    
    /**
     * Validates if a schema name follows the expected PascalCase pattern.
     * This can be used for testing and validation purposes.
     * 
     * @param schemaName the schema name to validate
     * @return true if the name follows the expected PascalCase pattern
     */
    public static boolean isValidSchemaName(String schemaName) {
        if (schemaName == null || schemaName.isEmpty()) {
            return false;
        }

        return schemaName.matches("^[A-Z][a-zA-Z]*[A-Z][a-z]+$") || 
               schemaName.matches("^[A-Z][a-z]+$");
    }
    
    /**
     * Extracts the representation part from a schema name.
     * Used for debugging and validation.
     * 
     * @param schemaName the full schema name (e.g., "Queue-roomDefault")
     * @return the representation part (e.g., "Default") or null if not found
     */
    public static String extractRepresentation(String schemaName) {
        if (schemaName == null || schemaName.isEmpty()) {
            return null;
        }
        
        String[] representations = {"Default", "Full", "Ref", "Custom"};
        for (String rep : representations) {
            if (schemaName.endsWith(rep)) {
                return rep;
            }
        }
        
        return null;
    }
    
    /**
     * Extracts the base name (without representation) from a schema name.
     * Used for debugging and validation.
     * 
     * @param schemaName the full schema name (e.g., "Queue-roomDefault")
     * @return the base name part (e.g., "Queue-room") or the original name if no representation found
     */
    public static String extractBaseName(String schemaName) {
        String representation = extractRepresentation(schemaName);
        if (representation != null) {
            return schemaName.substring(0, schemaName.length() - representation.length());
        }
        return schemaName;
    }
        
    /**
     * Extracts the base resource name from a delegate class, handling version suffixes and compound names.
     * This is the primary method for normalizing delegate type names to match introspection service results.
     * 
     * Examples:
     * - UserAndPassword1_8 -> User
     * - LocationTag1_9 -> LocationTag  
     * - Patient1_8 -> Patient
     * - QueueRoom -> QueueRoom (no change needed)
     * 
     * @param delegateType the delegate class (e.g., UserAndPassword1_8.class)
     * @return the base resource name that matches introspection service results
     */
    public static String extractBaseResourceName(Class<?> delegateType) {
        if (delegateType == null) {
            return "Unknown";
        }
        
        String className = delegateType.getSimpleName();
        

        String baseClassName = className.replaceAll("\\d+_\\d+$", "");
        

        if (baseClassName.contains("And")) {
            String[] parts = baseClassName.split("And");
            if (parts.length > 0) {
                baseClassName = parts[0];
            }
        }
        
        return baseClassName;
    }
    
    /**
     * Cleans up type strings by removing representation source information.
     * Used for property type names that come from introspection with metadata.
     * 
     * Examples:
     * - "User (from DEFAULT representation)" -> "User"
     * - "Location (from introspection)" -> "Location" 
     * - "Patient" -> "Patient" (no change)
     * 
     * @param javaType the type string that may contain source information
     * @return the cleaned type name
     */
    public static String cleanTypeString(String javaType) {
        if (javaType == null) return "String";
        
        int fromIndex = javaType.indexOf(" (from ");
        if (fromIndex > 0) {
            return javaType.substring(0, fromIndex).trim();
        }
        
        return javaType.trim();
    }
    
    /**
     * Unified method for generating schema names from delegate classes.
     * This is the primary method for schema definitions - combines delegate type processing with schema naming.
     * 
     * @param delegateType the delegate class from the resource handler
     * @param representation the representation name ("default", "full", "ref", etc.)
     * @return the final schema name for components/schemas
     */
    public static String schemaNameFromDelegateType(Class<?> delegateType, String representation) {
        String baseResourceName = extractBaseResourceName(delegateType);
        return schemaName(baseResourceName, representation);
    }
    
    /**
     * Unified method for generating schema names from property type strings.
     * This is the primary method for property references - combines type cleaning with schema naming.
     * 
     * @param propertyType the property type string from introspection 
     * @param representation the representation hint ("default", "full", "ref", etc.)
     * @return the final schema name for $ref references
     */
    public static String schemaNameFromPropertyType(String propertyType, String representation) {
        String cleanType = cleanTypeString(propertyType);
        return schemaName(cleanType, representation);
    }
}
