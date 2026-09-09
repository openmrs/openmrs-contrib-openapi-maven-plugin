package org.openmrs.openapi.devserver;

import java.util.Arrays;
import java.util.List;

/**
 * Identifies the pinned Swagger UI renderer files, which are vendored into the repo alongside the
 * rest of the UI ({@code src/main/resources/web}) and served like any other static asset — see
 * {@link StaticAssets}. This class only marks them, so the shell can serve them with a long
 * {@code immutable} cache: they are large (~1.7 MB together) and a fresh iframe re-requests them on
 * every resource click, yet never change for a given pinned version.
 * <p>
 * <b>Pinned, and not fetched at runtime.</b> The bytes are committed rather than pulled from a CDN,
 * so the docs work with no network at all — which matters for an OpenMRS instance behind a firewall,
 * the eventual deployment target — and so nothing changes the renderer under a measurement. The
 * version is recorded here as the single source of truth.
 * <p>
 * <b>To bump the version:</b> change {@link #VERSION}, then replace the two files under
 * {@code src/main/resources/web} with the matching build, e.g.
 * <pre>
 *   base=https://unpkg.com/swagger-ui-dist@&lt;version&gt;
 *   curl -fsSL "$base/swagger-ui-bundle.js" -o openapi-dev-server/src/main/resources/web/swagger-ui-bundle.js
 *   curl -fsSL "$base/swagger-ui.css"       -o openapi-dev-server/src/main/resources/web/swagger-ui.css
 * </pre>
 * Keep the pin: floating on {@code /swagger-ui-dist/} would silently change the renderer, and the
 * type-expression plugin in {@code named-types.js} wraps a Swagger UI component by name — a rename
 * upstream would turn it into a no-op with nothing to show for it.
 */
final class RendererAssets {

    static final String VERSION = "5.32.14";

    private static final List<String> FILES =
        Arrays.asList("swagger-ui.css", "swagger-ui-bundle.js");

    private RendererAssets() {
    }

    /** Whether {@code name} is one of the pinned renderer files (and so cacheable as immutable). */
    static boolean handles(String name) {
        return FILES.contains(name);
    }
}
