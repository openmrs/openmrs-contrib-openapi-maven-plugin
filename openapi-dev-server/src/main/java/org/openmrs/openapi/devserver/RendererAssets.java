package org.openmrs.openapi.devserver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * The renderer bundle, fetched from a CDN once and then served from localhost.
 * <p>
 * The UI does not link the CDN directly for two reasons. It means the docs keep working without
 * network after the first run — which matters for an OpenMRS instance behind a firewall, the
 * eventual deployment target. And it puts the version in exactly one place: when this UI moves
 * into the REST module the bundle becomes an ordinary packaged resource, and the only change is
 * where {@link #read} gets its bytes.
 * <p>
 * The version is pinned. Floating on {@code /swagger-ui-dist/} would silently change the renderer
 * under a measurement, and the type-expression plugin in {@code named-types.js} wraps a component
 * by name — a rename upstream would turn it into a no-op with nothing to show for it.
 */
final class RendererAssets {

    private static final String VERSION = "5.32.14";
    private static final String CDN = "https://unpkg.com/swagger-ui-dist@" + VERSION + "/";

    private static final List<String> FILES =
        Arrays.asList("swagger-ui.css", "swagger-ui-bundle.js");

    /** Version-scoped, so bumping the pin refetches rather than serving the old bytes. */
    private final File cacheDir = new File(System.getProperty("java.io.tmpdir"),
        "openmrs-openapi-devserver/swagger-ui-" + VERSION);

    static boolean handles(String name) {
        return FILES.contains(name);
    }

    /** The named bundle file, downloading and caching it if this is the first time. */
    synchronized byte[] read(String name) throws IOException {
        if (!FILES.contains(name)) {
            return null;
        }
        File cached = new File(cacheDir, name);
        if (cached.isFile() && cached.length() > 0) {
            return Files.readAllBytes(cached.toPath());
        }
        System.out.println("Fetching " + CDN + name + " …");
        byte[] bytes = download(CDN + name);
        cacheDir.mkdirs();
        // Write beside and rename, so an interrupted download cannot leave a truncated file that
        // every later run would happily serve.
        File partial = new File(cacheDir, name + ".part");
        Files.write(partial.toPath(), bytes);
        partial.renameTo(cached);
        System.out.println("  cached " + bytes.length / 1024 + " KB at " + cached);
        return bytes;
    }

    private static byte[] download(String url) throws IOException {
        InputStream in = new URL(url).openStream();
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }
}
