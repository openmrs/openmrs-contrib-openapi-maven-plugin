/*
 * NamedUnionTypes — a Swagger UI plugin that names union branches in the type expression.
 *
 * Swagger UI renders an OpenAPI 3.1 document with its JSON Schema 2020-12 renderer, which
 * dereferences $refs before rendering. Two places lose the schema name as a result:
 *
 *   1. Each anyOf/oneOf branch's label. Already fixed spec-side: the branch label is
 *      `#${index} ${getTitle(schema)}`, and getTitle reads `title`, so emitting `title` on
 *      every named schema is enough. No plugin needed for that.
 *
 *   2. The type expression next to the accordion, which comes from getType:
 *          AlertGet   object | (object | object | object | object)
 *      getType is computed purely from type/properties/items/combining keywords and never
 *      looks at `title`. That is what this plugin fixes:
 *          AlertGet   object | (AlertGet_default | AlertGet_full | AlertGet_ref | AlertGet_custom)
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

  // A branch names itself from its $ref, then its title, then Swagger UI's own answer.
  //
  // $ref comes first because it is the only one available while the accordion is collapsed.
  // Swagger UI resolves $refs lazily on expand, so a collapsed union's branches are still
  // {$ref: "#/components/schemas/AlertGet_default"} objects with no title and no structure —
  // which is why stock Swagger UI shows "(any | any | any | any)" until you expand it, and
  // "(object | object | object | object)" afterwards. Reading the pointer's last segment
  // names the branch in both states, and does not depend on the spec carrying titles.
  function branchLabel(schema, getType) {
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
    return getType(schema);
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

  global.NamedUnionTypesPlugin = function (system) {
    var React = system.React;
    return {
      wrapComponents: {
        JSONSchema202012KeywordType: function (Original, sys) {
          return function NamedKeywordType(props) {
            var getType = sys.fn.jsonSchema202012.getType;
            var expression = namedTypeExpression(props.schema, getType);
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
})(window);
