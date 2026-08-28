package org.openmrs.openapi.devserver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One documented thing — a REST resource, a sub-resource, or a {@code @Controller} — as the
 * navigation index sees it.
 * <p>
 * Deliberately holds names, not shapes: routes, summaries and property names, which is what a
 * search box needs. The shapes live in the slice, fetched only when the thing is selected. Across
 * the four modules under test this keeps the whole index at ~100&nbsp;KB against 1.6&nbsp;MB of
 * specs.
 */
final class ResourceEntry {

    static final class Operation {
        final String path;
        final String verb;
        final String summary;

        Operation(String path, String verb, String summary) {
            this.path = path;
            this.verb = verb;
            this.summary = summary;
        }
    }

    final String module;
    /** "resource" or "controller" — a sub-resource is a resource with {@link #parent} set. */
    final String kind;
    /** The name of the generated file, without ".json" — e.g. "Patient", "FormResource". */
    final String name;
    final List<Operation> operations;
    final Set<String> fields = new LinkedHashSet<String>();

    /**
     * For a sub-resource: initially the parent's REST path segment as it appears in the route,
     * then rewritten to the parent entry's name by
     * {@link SpecCatalog#resolveParents}. Null for everything else.
     */
    String parent;

    ResourceEntry(String module, String kind, String name, List<Operation> operations) {
        this.module = module;
        this.kind = kind;
        this.name = name;
        this.operations = operations;
    }
}
