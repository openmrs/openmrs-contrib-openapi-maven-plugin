package org.openmrs.plugin.openapi;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Names the resource operations after the methods of the controllers that actually serve them.
 *
 * <h2>Why not a table in this plugin</h2>
 *
 * Every REST resource route is dispatched by {@code MainResourceController} or
 * {@code MainSubResourceController}, and each of their mapped methods already has a name that says
 * what the operation does — {@code retrieve}, {@code create}, {@code update}, {@code delete},
 * {@code searchByHandler}. Hardcoding those names here would mean the REST module could rename an
 * operation and this plugin would keep publishing the old name, silently. So they are read from the
 * controllers, and the REST module stays the authority on its own vocabulary.
 *
 * <h2>Three tiers</h2>
 *
 * <ol>
 * <li>{@code @Operation(operationId = "…")} on the controller method — the module's explicit
 *     choice, and the only way to give an operation a name its Java method does not have.
 *     {@code MainResourceController.get} uses it to say {@code getAll}, which is what the method
 *     does but not what it is called.</li>
 * <li>Otherwise the Java method name, which is already right for everything else.</li>
 * </ol>
 *
 * There is deliberately no third tier. {@code webservices.rest-omod-common} is listed in
 * {@code GenerateMojo.isGeneratorJar()}, so the plugin's own copy of both controllers is always on
 * the generator classloader and {@code Class.forName} cannot miss. A built-in table of names for
 * the case that cannot happen would be worse than useless: if these classes ever genuinely failed
 * to load, publishing invented names is not the behaviour wanted — {@code operationId()} throwing
 * is.
 *
 * <h2>Matching is by route template and verb</h2>
 *
 * Not by name, so the correspondence this plugin hardcodes is to a <em>route shape it already
 * computes</em> rather than to a string. If a controller's {@code @RequestMapping} ever changes,
 * the lookup misses and {@link #nameFor} says so, instead of emitting a stale name.
 *
 * <h2>Where several methods share a route</h2>
 *
 * Spring separates some operations by header or request parameter, and this plugin merges them into
 * one documented operation:
 * <ul>
 * <li>{@code POST /{resource}} is {@code create} ({@code @RequestBody}) and {@code upload}
 *     ({@code headers = "Content-Type=multipart/form-data"})</li>
 * <li>{@code DELETE /{resource}/{uuid}} is {@code delete} ({@code params = "!purge"}) and
 *     {@code purge} ({@code params = "purge=true"})</li>
 * </ul>
 * The merged operation documents the unconstrained default branch, so that is the one whose name it
 * takes: fewest mapping constraints wins, and a negated parameter counts as no constraint because
 * {@code params = "!purge"} <em>is</em> the default case. That yields {@code create} and
 * {@code delete}. A caller that knows better passes an explicit preference to {@link #nameFor}.
 */
final class DispatchControllerNames {

    private static final String CONTROLLER_PACKAGE =
        "org.openmrs.module.webservices.rest.web.v1_0.controller.";

    /** "VERB /template" -> every name mapping it, least-constrained first. */
    private final Map<String, List<String>> byRoute = new LinkedHashMap<>();

    /** Counted for the published name of each route only, not for the tiebreak losers. */
    private int fromAnnotation;
    private int fromMethodName;

    DispatchControllerNames() {
        scan("MainResourceController");
        scan("MainSubResourceController");
    }

    /**
     * The operation name for a route, or null if neither controller maps it.
     *
     * @param prefer when several methods map the route, the Java method name to take instead of the
     *               least-constrained one — {@code upload} for a resource that is
     *               {@code Uploadable} but not {@code Creatable}. Ignored if nothing maps it.
     */
    String nameFor(String verb, String template, String prefer) {
        List<String> names = byRoute.get(verb + " " + template);
        if (names == null || names.isEmpty()) {
            return null;
        }
        return prefer != null && names.contains(prefer) ? prefer : names.get(0);
    }

    /** For the run report, so a missing annotation is visible rather than silently absent. */
    String summary() {
        if (byRoute.isEmpty()) {
            return "nothing — neither MainResourceController nor MainSubResourceController is on"
                + " the classpath, so no resource route can be named";
        }
        // The two tiers sum to the route count: only the name actually published for each route is
        // counted, not the tiebreak losers (upload, purge, and the sub-resource's second
        // delete/purge pair), which nameFor() reaches only when asked for them by name.
        return byRoute.size() + " route name(s) from the dispatch controllers ("
            + fromAnnotation + " from @Operation(operationId), "
            + fromMethodName + " from the Java method name)";
    }

    private void scan(String simpleName) {
        Class<?> controller;
        try {
            controller = Class.forName(CONTROLLER_PACKAGE + simpleName, false,
                DispatchControllerNames.class.getClassLoader());
        } catch (Throwable notPresent) {
            return;
        }
        // getDeclaredMethods() has no defined order, and several methods can map one route — so
        // without this the tiebreak below would pick a different winner between runs.
        Method[] methods = controller.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::toString));

        Map<String, List<Candidate>> found = new LinkedHashMap<>();
        for (Method method : methods) {
            Mapping mapping = mappingOf(method);
            if (mapping == null) {
                continue;
            }
            for (String verb : mapping.verbs) {
                found.computeIfAbsent(verb + " " + mapping.template, k -> new ArrayList<>())
                    .add(new Candidate(method, mapping.constraints));
            }
        }
        for (Map.Entry<String, List<Candidate>> entry : found.entrySet()) {
            if (byRoute.containsKey(entry.getKey())) {
                continue;
            }
            List<Candidate> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(Comparator.comparingInt((Candidate c) -> c.constraints)
                .thenComparing(c -> c.method.toString()));
            List<String> names = new ArrayList<>();
            for (Candidate candidate : sorted) {
                String declared = ControllerDocumenter.declaredOperationId(candidate.method);
                names.add(declared != null ? declared : candidate.method.getName());
                if (names.size() == 1) {
                    // The first is the one this route publishes; the rest are only reachable
                    // through nameFor(..., prefer).
                    if (declared != null) {
                        fromAnnotation++;
                    } else {
                        fromMethodName++;
                    }
                }
            }
            byRoute.put(entry.getKey(), names);
        }
    }

    private static final class Candidate {
        final Method method;
        final int constraints;

        Candidate(Method method, int constraints) {
            this.method = method;
            this.constraints = constraints;
        }
    }

    private static final class Mapping {
        final String template;
        final List<String> verbs;
        final int constraints;

        Mapping(String template, List<String> verbs, int constraints) {
            this.template = template;
            this.verbs = verbs;
            this.constraints = constraints;
        }
    }

    /**
     * The method's {@code @RequestMapping}, reduced to what identifies a route here.
     * <p>
     * A {@code params} entry beginning with {@code !} is a negation — {@code params = "!purge"} is
     * the branch taken when the parameter is <em>absent</em>, i.e. the default one — so it does not
     * count towards the constraint tally the tiebreak minimises.
     */
    private static Mapping mappingOf(Method method) {
        org.springframework.web.bind.annotation.RequestMapping mapping;
        try {
            mapping = method.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class);
        } catch (Throwable ignored) {
            return null;
        }
        if (mapping == null || mapping.value().length == 0 || mapping.method().length == 0) {
            return null;
        }
        List<String> verbs = new ArrayList<>();
        for (org.springframework.web.bind.annotation.RequestMethod verb : mapping.method()) {
            verbs.add(verb.name());
        }
        int constraints = mapping.headers().length;
        for (String param : mapping.params()) {
            if (!param.startsWith("!")) {
                constraints++;
            }
        }
        return new Mapping(mapping.value()[0], verbs, constraints);
    }
}
