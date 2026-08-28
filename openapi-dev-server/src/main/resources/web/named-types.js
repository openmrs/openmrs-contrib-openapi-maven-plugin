/*
 * NamedUnionTypes — a Swagger UI plugin that puts schema names back into the type expression,
 * for union branches and for reference sites (properties, array items, additionalProperties).
 *
 * Swagger UI renders an OpenAPI 3.1 document with its JSON Schema 2020-12 renderer, which
 * dereferences $refs before rendering. Two places lose the schema name as a result:
 *
 *   1. Each anyOf/oneOf branch's label. Already fixed spec-side: the branch label is
 *      `#${index} ${getTitle(schema)}`, and getTitle reads `title`, so emitting `title` on
 *      every named schema is enough. No plugin needed for that.
 *
 *   2. The type expression next to the accordion, which comes from getType:
 *          AlertGet             object | (object | object | object | object)
 *          Encounter.location   object
 *          Encounter.orders     array<object | (object | object | object)>
 *      getType is computed purely from type/properties/items/combining keywords and never
 *      looks at `title` or `$ref`. That is what this plugin fixes:
 *          AlertGet             object | (AlertGet_default | AlertGet_full | AlertGet_ref | ...)
 *          Encounter.location   LocationGet_ref
 *          Encounter.orders     array<OrderGet_ref>
 *      The union half is namedTypeExpression; the reference-site half, which needs to know
 *      where in the document the schema sits, is referenceTypeExpression + isReferenceSite.
 *
 * Implemented as a wrapComponents on JSONSchema202012KeywordType rather than an override of
 * fn.jsonSchema202012.getType: wrapComponents is scoped to one named component, whereas
 * replacing a key inside the fn namespace risks clobbering its siblings (getTitle, useFn,
 * useComponent, ...) depending on how plugin `fn` objects are merged.
 *
 * The expression mirrors Swagger UI's own getType exactly — same operators, same order,
 * same parenthesisation:
 *     [basePart, (oneOf join " | "), (anyOf join " | "), (allOf join " & ")]
 *         .filter(Boolean).join(" | ")
 * The only change is that a branch carrying a `title` contributes its title instead of its
 * structural type. Anything with no combining keyword is handed straight to the original
 * component, so ordinary schemas render exactly as before.
 */
(function (global) {
  'use strict';

  // A schema's own name, from its $ref pointer's last segment or else its title. null when it
  // has neither, i.e. when it is an inline anonymous schema.
  //
  // $ref comes first because it is the only one available while an accordion is collapsed.
  // Swagger UI resolves $refs lazily on expand, so a collapsed union's branches are still
  // {$ref: "#/components/schemas/AlertGet_default"} objects with no title and no structure —
  // which is why stock Swagger UI shows "(any | any | any | any)" until you expand it, and
  // "(object | object | object | object)" afterwards. Reading the pointer's last segment
  // names the branch in both states, and does not depend on the spec carrying titles.
  function schemaName(schema) {
    if (schema && typeof schema === 'object') {
      if (typeof schema.$ref === 'string' && schema.$ref !== '') {
        var segment = schema.$ref.split('/').pop();
        if (segment) {
          return decodeURIComponent(segment.replace(/~1/g, '/').replace(/~0/g, '~'));
        }
      }
      if (typeof schema.title === 'string' && schema.title !== '') {
        return schema.title;
      }
    }
    return null;
  }

  // A branch names itself, falling back to Swagger UI's own answer.
  function branchLabel(schema, getType) {
    return schemaName(schema) || getType(schema);
  }

  // Returns the replacement expression, or null to mean "not a union, leave it alone".
  // Deliberately does not use the schema's OWN title: that is already the accordion label,
  // and repeating it as the type would read as "AlertGet AlertGet".
  function namedTypeExpression(schema, getType) {
    if (!schema || typeof schema !== 'object') {
      return null;
    }
    var combining = [['oneOf', ' | '], ['anyOf', ' | '], ['allOf', ' & ']]
      .filter(function (entry) { return Array.isArray(schema[entry[0]]); });
    if (combining.length === 0) {
      return null;
    }

    var parts = [];

    // The base part is whatever getType makes of the schema with its combining keywords
    // removed — "object" for a schema that also declares type/properties. A bare "any" is
    // dropped rather than rendered as "any | (...)", which is what getType itself produces
    // for a schema that is nothing but an anyOf.
    var bare = Object.assign({}, schema);
    delete bare.oneOf;
    delete bare.anyOf;
    delete bare.allOf;
    if (Object.keys(bare).length > 0) {
      var base = getType(bare);
      if (base && base !== 'any') {
        parts.push(base);
      }
    }

    combining.forEach(function (entry) {
      var keyword = entry[0];
      var separator = entry[1];
      parts.push('(' + schema[keyword].map(function (branch) {
        return branchLabel(branch, getType);
      }).join(separator) + ')');
    });

    return parts.join(' | ');
  }

  // ---------------------------------------------------------------------------------------
  // Reference sites
  //
  // A property that points at a named schema renders as a bare "object":
  //
  //     location    object          <- want: LocationGet_ref
  //     orders      array<object | (object | object | object)>
  //                                 <- want: array<OrderGet_ref>
  //
  // namedTypeExpression above does not help, because such a property carries no combining
  // keyword and so is handed straight to Swagger UI. And Swagger UI cannot help either:
  // getType is computed from type/properties/items/const/format alone — it reads neither
  // $ref nor title — so the name is simply not in the expression it builds. This half puts
  // it back.
  //
  // Naming is applied only at positions where the accordion label is NOT already the
  // schema's own name, so it adds information rather than repeating the label:
  //
  //     properties/<key>       label is the property key       -> name it
  //     patternProperties/<re> label is the pattern            -> name it
  //     additionalProperties   label is "additionalProperties" -> name it
  //     items, prefixItems/<i> label is "items"                -> name it
  //
  // and skipped everywhere else. The two that matter:
  //
  //     level 0                the Schemas section entry, labelled with the schema name
  //     anyOf|oneOf|allOf/<i>  a union branch, already labelled "#0 AlertGet_default" by
  //                            Swagger UI's own getTitle
  //
  // Both would otherwise read "AlertGet_default AlertGet_default".
  function isReferenceSite(path) {
    if (!Array.isArray(path) || path.length === 0) {
      return false;
    }
    var last = path[path.length - 1];
    var parent = path.length >= 2 ? path[path.length - 2] : null;
    return parent === 'properties'
      || parent === 'patternProperties'
      || parent === 'prefixItems'
      || last === 'additionalProperties'
      || last === 'items';
  }

  // Swagger UI renders an array as "array<item>", computing the item type with getType and so
  // losing the item's name too. Mirrors its getArrayType for the single-items case only;
  // anything more elaborate (prefixItems, contains) is left to Swagger UI.
  function isArraySchema(schema) {
    return schema.type === 'array'
      || (Array.isArray(schema.type) && schema.type.indexOf('array') !== -1);
  }

  // Returns the replacement expression for a reference site, or null to leave it alone.
  function referenceTypeExpression(schema, getType) {
    if (!schema || typeof schema !== 'object') {
      return null;
    }
    // Unions are namedTypeExpression's business; it runs first and handles them.
    if (schema.oneOf || schema.anyOf || schema.allOf) {
      return null;
    }

    var own = schemaName(schema);
    if (own) {
      return own;
    }

    if (isArraySchema(schema) && !Array.isArray(schema.prefixItems) && schema.items) {
      // The item may be named outright, or be a union of named schemas — an OpenMRS *Get_ref
      // for a resource with subtypes is a oneOf, which is how "orders" ends up as
      // array<object | (object | object | object)>.
      var item = schemaName(schema.items)
        || namedTypeExpression(schema.items, getType);
      if (item) {
        return 'array<' + item + '>';
      }
    }
    return null;
  }

  global.NamedUnionTypesPlugin = function (system) {
    var React = system.React;
    return {
      wrapComponents: {
        JSONSchema202012KeywordType: function (Original, sys) {
          // Resolved once, at wrap time, so the component below has a fixed shape and calls
          // the same hooks in the same order on every render. KeywordType is handed only
          // {schema} as props, so usePath is the only way to know WHERE this schema sits, and
          // position is what keeps reference-site naming from duplicating an accordion label.
          // If a future Swagger UI drops the hook, the plugin quietly falls back to naming
          // union branches only — which is all it did before.
          var usePath = sys.fn.jsonSchema202012.usePath;
          var canReadPath = typeof usePath === 'function';

          return function NamedKeywordType(props) {
            var getType = sys.fn.jsonSchema202012.getType;
            // Unconditional: hook order must not vary between renders.
            var path = canReadPath ? usePath().path : null;

            var expression = namedTypeExpression(props.schema, getType);
            if (expression === null && isReferenceSite(path)) {
              expression = referenceTypeExpression(props.schema, getType);
            }
            if (expression === null) {
              return React.createElement(Original, props);
            }
            // Same markup and classes as Swagger UI's own KeywordType, so styling matches —
            // except for text-transform. The stylesheet sets
            //   .json-schema-2020-12__attribute { text-transform: lowercase }
            // which is right for structural types ("object", "array<any>") and wrong for schema
            // names: it rendered PatientGet_default as "patientget_default". Schema names are
            // case-significant, so this opts out.
            return React.createElement(
              'strong',
              {
                className: 'json-schema-2020-12__attribute json-schema-2020-12__attribute--primary',
                style: { textTransform: 'none' }
              },
              expression + (props.isCircular ? ' [circular]' : '')
            );
          };
        }
      }
    };
  };

  // exported for testing
  global.NamedUnionTypesPlugin._namedTypeExpression = namedTypeExpression;
  global.NamedUnionTypesPlugin._referenceTypeExpression = referenceTypeExpression;
  global.NamedUnionTypesPlugin._isReferenceSite = isReferenceSite;
})(window);
