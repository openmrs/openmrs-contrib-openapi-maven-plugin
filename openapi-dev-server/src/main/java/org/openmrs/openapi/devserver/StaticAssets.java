package org.openmrs.openapi.devserver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the UI's HTML, CSS and JavaScript.
 * <p>
 * Reads from {@code src/main/resources/web} when that directory is present next to the running
 * JAR, and falls back to the copy on the classpath. That means editing the UI and reloading the
 * browser is enough during development, while a JAR taken anywhere else still serves a complete
 * UI — which is also what makes these files liftable into the REST module as ordinary bundled
 * resources.
 */
final class StaticAssets {

    private static final String CLASSPATH_ROOT = "web/";

    private static final Map<String, String> CONTENT_TYPES = new HashMap<String, String>();
    static {
        CONTENT_TYPES.put("html", "text/html; charset=utf-8");
        CONTENT_TYPES.put("js", "application/javascript; charset=utf-8");
        CONTENT_TYPES.put("css", "text/css; charset=utf-8");
        CONTENT_TYPES.put("json", "application/json");
        CONTENT_TYPES.put("svg", "image/svg+xml");
        CONTENT_TYPES.put("png", "image/png");
        // /favicon.ico is answered with the SVG, so it must be typed as one.
        CONTENT_TYPES.put("ico", "image/svg+xml");
    }

    private final File devDir;

    StaticAssets(File devDir) {
        this.devDir = devDir;
    }

    /** Locates src/main/resources/web relative to the running JAR, or null when not present. */
    static StaticAssets locate() {
        File found = null;
        try {
            URI location = StaticAssets.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI();
            File codeSource = new File(location);
            // target/*.jar -> ../src/main/resources/web; target/classes -> ../../src/...
            File base = codeSource.isDirectory() ? codeSource.getParentFile() : codeSource;
            for (File dir = base; dir != null && found == null; dir = dir.getParentFile()) {
                File candidate = new File(dir, "src/main/resources/web");
                if (candidate.isDirectory()) {
                    found = candidate;
                }
            }
        } catch (Exception e) {
            // Not being able to locate the source tree is normal for a relocated JAR.
        }
        if (found != null) {
            System.out.println("UI assets: " + found + " (edit and reload; no rebuild needed)");
        } else {
            System.out.println("UI assets: bundled in the JAR");
        }
        return new StaticAssets(found);
    }

    /** The named asset's bytes, or null if there is no such asset. */
    byte[] read(String name) throws IOException {
        // Browsers probe /favicon.ico regardless of the page's <link rel="icon">, and Chrome is
        // happy to take SVG bytes for it. Answering keeps a 404 out of the console.
        if (name.equals("favicon.ico")) {
            name = "favicon.svg";
        }
        if (!isSafe(name)) {
            return null;
        }
        if (devDir != null) {
            File file = new File(devDir, name).getCanonicalFile();
            if (file.getPath().startsWith(devDir.getCanonicalPath()) && file.isFile()) {
                return Files.readAllBytes(file.toPath());
            }
        }
        InputStream in = StaticAssets.class.getClassLoader()
            .getResourceAsStream(CLASSPATH_ROOT + name);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        String type = CONTENT_TYPES.get(extension);
        return type != null ? type : "application/octet-stream";
    }

    /** Nothing absolute, no traversal, no hidden files. */
    private static boolean isSafe(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("..")) {
            return false;
        }
        return !Arrays.asList(name.split("/")).contains("");
    }
}
