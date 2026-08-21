# OpenMRS REST Representation Analyzer

| :zap:        This plug is currently in development and is not ready for general use.  |
|---------------------------------------------------------------------------------------|

This maven plugin aims to generate 100% complete and accurate OpenAPI documentation of REST resources and controllers for any OpenMRS module, by inspecting it via reflection at build time. It should be able to answer the following:
- For a given module:
  - What are its REST Resources?
  - What are its controllers?
- For a given OpenMRS REST Resource (ex: patient, encounter, visit):
  - what are its supported representations (ex: `default`, `ref`, `full`)?
  - what CRUD operations are supported?
  - what fields are included for each supported representation?
  - what fields are supported for custom representations: (ex: `?v=custom:(uuid,display)`)
  - what search handlers are supported, and what are the required fields for each search handler?
  - what are the required fields when creating the resource?
  - what are the required fields when updating the resource?
- For a given controller:
  - what URL path is the controller serves?
  - what are its inputs and outputs?

