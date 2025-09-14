package org.openmrs.plugin;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;

public class Tester {
  public static void main(String[] args) {
    
    OpenAPI openAPI = new OpenAPI(SpecVersion.V31)
        .info(new Info()
            .title("Person API")
            .version("1.0.0")
            .description("OpenAPI documentation for Person class"));

    ModelConverters converters = ModelConverters.getInstance(true);

    // ************
    // this line forces us to use CustomModelResolver to generate schema for the Person type.
    // If this line is commented out, the built-in ModelResolver is used instead.
    converters.addConverter(new CustomModelResolver(Json31.mapper()));
    
    // Generate schema for Person class
    ResolvedSchema resolvedSchema = converters.readAllAsResolvedSchema(Person.class);
    
    Components components = new Components();
    components.addSchemas("Person", resolvedSchema.schema);
    
    if (resolvedSchema.referencedSchemas != null) {
      resolvedSchema.referencedSchemas.forEach(components::addSchemas);
    }
    openAPI.components(components);
    
    String json = io.swagger.v3.core.util.Json.pretty(openAPI);
    System.out.println(json);
  }

  /**
   * A dummy class for testing OpenAPI spec generation. 
   */
  @Schema(description = "Person entity representing a user in the system")
  static class Person {
    // @Schema(description = "Unique identifier for the person", example = "12345")
    public String id;
    
    // @Schema(description = "Person's date of birth", example = "1990-01-15")
    public LocalDate birthday;
    
    // @Schema(description = "Person's first name", example = "John")
    public String firstName;
    
    // @Schema(description = "Person's last name", example = "Doe")
    public String lastName;

    public List<Person> friends;

    public Location location;
  }

  @Schema(description = "Contains latitude and longitude")
  static class Location {
    public double lat;
    public double lng;
  }
}
