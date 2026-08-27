package org.openmrs.openapi.devserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight dev server that reads one or more modules' generated openapi directories
 * and serves them via Swagger UI with a per-module sidebar and an "All" merged view.
 *
 * Usage: java -jar openapi-dev-server.jar --server=<url> [--port=9000] <module-path>...
 *
 * URL layout:
 *   /                              Swagger UI with sidebar
 *   /specs/all/openapi.json        merged spec from all modules
 *   /specs/<module>/openapi.json   per-module spec
 *   /specs/<module>/resources/*.json
 *   /specs/<module>/controllers/*.json
 *   /proxy/*                       reverse proxy to --server URL
 */
public class OpenApiDevServer {

    private static final int DEFAULT_PORT = 9000;

    private static final Map<String, File> modules = new LinkedHashMap<String, File>();

    private static final List<String> HOP_BY_HOP = Arrays.asList(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade", "host"
    );

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String serverUrl = null;
        List<String> modulePaths = new ArrayList<String>();

        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring(7));
            } else if (arg.startsWith("--server=")) {
                serverUrl = arg.substring(9);
            } else {
                modulePaths.add(arg);
            }
        }

        if (modulePaths.isEmpty() || serverUrl == null) {
            System.err.println("Usage: java -jar openapi-dev-server.jar --server=<url> [--port=9000] <module-path>...");
            System.exit(1);
        }

        final String upstreamUrl = serverUrl.endsWith("/")
            ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;

        for (String path : modulePaths) {
            File moduleDir = new File(path).getCanonicalFile();
            // The plugin writes into target/classes/META-INF/openapi so the specs ship inside
            // the module JAR; target/openapi is where older builds put them.
            File openApiDir = null;
            for (String candidate : new String[] {
                    "omod/target/classes/META-INF/openapi", "target/classes/META-INF/openapi",
                    "omod/target/openapi", "target/openapi" }) {
                if (new File(moduleDir, candidate).isDirectory()) {
                    openApiDir = new File(moduleDir, candidate);
                    break;
                }
            }
            if (openApiDir != null) {
                modules.put(moduleDir.getName(), openApiDir);
                System.out.println("Loaded: " + moduleDir.getName() + " -> " + openApiDir);
            } else {
                System.err.println("Warning: no openapi output found for " + moduleDir.getName()
                    + " — run generate.sh first");
            }
        }

        if (modules.isEmpty()) {
            System.err.println("No modules with OpenAPI output found.");
            System.exit(1);
        }

        ObjectMapper mapper = new ObjectMapper();

        // Build per-module specs, enrich cross-module refs, then build the merged spec.
        Map<String, byte[]> moduleSpecs = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, File> entry : modules.entrySet()) {
            byte[] spec = buildModuleSpec(mapper, entry.getValue(), entry.getKey());
            moduleSpecs.put(entry.getKey(), spec);
        }
        moduleSpecs = enrichCrossModuleRefs(mapper, moduleSpecs);
        byte[] allSpec = buildMergedSpec(mapper, moduleSpecs);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new Handler(moduleSpecs, allSpec, upstreamUrl));
        server.start();

        System.out.println("\nServing OpenAPI docs at http://localhost:" + port);
        System.out.println("Proxying API requests to: " + upstreamUrl);
    }

    /** Reads a module's openapi.json and injects the proxy server URL + basic auth security. */
    private static byte[] buildModuleSpec(ObjectMapper mapper, File openApiDir, String moduleName)
            throws Exception {
        File specFile = new File(openApiDir, "openapi.json");
        if (!specFile.exists()) {
            throw new IllegalStateException("Missing openapi.json for " + moduleName);
        }
        ObjectNode spec = (ObjectNode) mapper.readTree(specFile);
        injectSecurityAndServer(mapper, spec, "/proxy");
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(spec);
    }

    /**
     * For each module spec, finds any "#/components/schemas/Foo" $refs that are not defined
     * in that module's own components/schemas and pulls them in from other loaded modules.
     * Iterates until stable so transitive dependencies are resolved too.
     */
    private static Map<String, byte[]> enrichCrossModuleRefs(
            ObjectMapper mapper, Map<String, byte[]> moduleSpecs) throws Exception {
        // Build a master pool of all schemas across all modules (first-wins for duplicates).
        Map<String, JsonNode> allSchemas = new LinkedHashMap<String, JsonNode>();
        for (byte[] specBytes : moduleSpecs.values()) {
            JsonNode spec = mapper.readTree(specBytes);
            JsonNode schemas = spec.path("components").path("schemas");
            if (schemas.isObject()) {
                schemas.fields().forEachRemaining(e -> {
                    if (!allSchemas.containsKey(e.getKey())) {
                        allSchemas.put(e.getKey(), e.getValue());
                    }
                });
            }
        }

        Map<String, byte[]> enriched = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : moduleSpecs.entrySet()) {
            ObjectNode spec = (ObjectNode) mapper.readTree(entry.getValue());
            ObjectNode components = spec.has("components") && spec.get("components").isObject()
                ? (ObjectNode) spec.get("components") : mapper.createObjectNode();
            ObjectNode schemas = components.has("schemas") && components.get("schemas").isObject()
                ? (ObjectNode) components.get("schemas").deepCopy() : mapper.createObjectNode();

            // Iteratively add missing schemas until no new ones are needed.
            boolean added = true;
            while (added) {
                added = false;
                for (String name : collectRefTargets(spec)) {
                    if (!schemas.has(name) && allSchemas.containsKey(name)) {
                        schemas.set(name, allSchemas.get(name));
                        added = true;
                    }
                }
            }

            components.set("schemas", schemas);
            spec.set("components", components);
            enriched.put(entry.getKey(),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(spec));
        }
        return enriched;
    }

    /** Recursively collects all "#/components/schemas/<name>" $ref targets in a JSON tree. */
    private static Set<String> collectRefTargets(JsonNode node) {
        Set<String> refs = new HashSet<String>();
        collectRefTargetsRecursive(node, refs);
        return refs;
    }

    private static void collectRefTargetsRecursive(JsonNode node, Set<String> refs) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                String val = ref.asText();
                if (val.startsWith("#/components/schemas/")) {
                    refs.add(val.substring("#/components/schemas/".length()));
                }
            }
            node.fields().forEachRemaining(e -> collectRefTargetsRecursive(e.getValue(), refs));
        } else if (node.isArray()) {
            node.forEach(child -> collectRefTargetsRecursive(child, refs));
        }
    }

    /** Merges all per-module specs into one combined spec (deduplicating schemas). */
    private static byte[] buildMergedSpec(ObjectMapper mapper, Map<String, byte[]> moduleSpecs)
            throws Exception {
        ObjectNode mergedPaths = mapper.createObjectNode();
        ObjectNode mergedSchemas = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode mergedTags = mapper.createArrayNode();

        for (Map.Entry<String, byte[]> entry : moduleSpecs.entrySet()) {
            JsonNode spec = mapper.readTree(entry.getValue());

            JsonNode tags = spec.path("tags");
            if (tags.isArray()) {
                tags.forEach(tag -> {
                    String tagName = tag.path("name").asText();
                    for (JsonNode t : mergedTags) {
                        if (tagName.equals(t.path("name").asText())) return;
                    }
                    mergedTags.add(tag);
                });
            }

            JsonNode paths = spec.path("paths");
            if (paths.isObject()) {
                paths.fields().forEachRemaining(e -> {
                    if (!mergedPaths.has(e.getKey())) mergedPaths.set(e.getKey(), e.getValue());
                });
            }

            JsonNode schemas = spec.path("components").path("schemas");
            if (schemas.isObject()) {
                schemas.fields().forEachRemaining(e -> {
                    if (!mergedSchemas.has(e.getKey())) mergedSchemas.set(e.getKey(), e.getValue());
                });
            }
        }

        ObjectNode components = mapper.createObjectNode();
        components.set("schemas", mergedSchemas);
        injectSecurityScheme(mapper, components);

        ObjectNode merged = mapper.createObjectNode();
        merged.put("openapi", "3.1.0");
        ObjectNode info = mapper.createObjectNode();
        info.put("title", "OpenMRS REST API — All Modules");
        info.put("version", "combined");
        merged.set("info", info);
        merged.set("servers", serverArray(mapper, "/proxy"));
        if (mergedTags.size() > 0) merged.set("tags", mergedTags);
        merged.set("paths", mergedPaths);
        merged.set("components", components);
        merged.set("security", securityRequirement(mapper));
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(merged);
    }

    private static void injectSecurityAndServer(ObjectMapper mapper, ObjectNode spec, String proxyUrl) {
        spec.set("servers", serverArray(mapper, proxyUrl));
        spec.set("security", securityRequirement(mapper));
        JsonNode components = spec.get("components");
        if (components instanceof ObjectNode) {
            injectSecurityScheme(mapper, (ObjectNode) components);
        } else {
            ObjectNode newComponents = mapper.createObjectNode();
            injectSecurityScheme(mapper, newComponents);
            spec.set("components", newComponents);
        }
    }

    private static void injectSecurityScheme(ObjectMapper mapper, ObjectNode components) {
        ObjectNode basicAuth = mapper.createObjectNode();
        basicAuth.put("type", "http");
        basicAuth.put("scheme", "basic");
        ObjectNode schemes = mapper.createObjectNode();
        schemes.set("basicAuth", basicAuth);
        components.set("securitySchemes", schemes);
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode serverArray(
            ObjectMapper mapper, String url) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
        arr.add(mapper.createObjectNode().put("url", url));
        return arr;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode securityRequirement(
            ObjectMapper mapper) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
        ObjectNode req = mapper.createObjectNode();
        req.set("basicAuth", mapper.createArrayNode());
        arr.add(req);
        return arr;
    }

    static class Handler implements com.sun.net.httpserver.HttpHandler {
        private final Map<String, byte[]> moduleSpecs;
        private final byte[] allSpec;
        private final String upstreamUrl;

        Handler(Map<String, byte[]> moduleSpecs, byte[] allSpec, String upstreamUrl) {
            this.moduleSpecs = moduleSpecs;
            this.allSpec = allSpec;
            this.upstreamUrl = upstreamUrl;
        }

        public void handle(HttpExchange exchange) throws IOException {
            try {
                dispatch(exchange);
            } catch (Exception e) {
                send(exchange, 500, "text/plain", "Server error: " + e.getMessage());
            }
        }

        private void dispatch(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            if (path.equals("/") || path.isEmpty()) {
                serveUi(exchange);
                return;
            }

            // /specs/all/openapi.json
            if (path.equals("/specs/all/openapi.json")) {
                serveJson(exchange, allSpec);
                return;
            }

            // /specs/<module>/openapi.json or /specs/<module>/resources/* or /specs/<module>/controllers/*
            if (path.startsWith("/specs/")) {
                String rest = path.substring("/specs/".length()); // e.g. "module/openapi.json"
                int slash = rest.indexOf('/');
                if (slash < 0) {
                    send(exchange, 404, "text/plain", "Not found: " + path);
                    return;
                }
                String moduleName = rest.substring(0, slash);
                String subPath = rest.substring(slash + 1); // e.g. "openapi.json" or "resources/X.json"

                if (subPath.equals("openapi.json")) {
                    byte[] spec = moduleSpecs.get(moduleName);
                    if (spec == null) {
                        send(exchange, 404, "text/plain", "Unknown module: " + moduleName);
                        return;
                    }
                    serveJson(exchange, spec);
                    return;
                }

                if (subPath.startsWith("resources/") || subPath.startsWith("controllers/")) {
                    File openApiDir = modules.get(moduleName);
                    if (openApiDir != null) {
                        File target = new File(openApiDir, subPath).getCanonicalFile();
                        if (target.getPath().startsWith(openApiDir.getCanonicalPath())
                                && target.exists() && target.isFile()) {
                            serveFile(exchange, target);
                            return;
                        }
                    }
                }

                send(exchange, 404, "text/plain", "Not found: " + path);
                return;
            }

            // /proxy/* — forward to upstream
            if (path.startsWith("/proxy/") || path.equals("/proxy")) {
                String upstreamPath = path.substring("/proxy".length());
                String query = exchange.getRequestURI().getRawQuery();
                String targetUrl = upstreamUrl + upstreamPath + (query != null ? "?" + query : "");
                System.out.println("[proxy] " + exchange.getRequestMethod() + " " + targetUrl);
                proxy(exchange, targetUrl);
                return;
            }

            send(exchange, 404, "text/plain", "Not found: " + path);
        }

        private void serveUi(HttpExchange exchange) throws IOException {
            StringBuilder sidebarItems = new StringBuilder();
            sidebarItems.append("<a href='#' class='sidebar-item' onclick='loadSpec(\"all\"); return false;'>All</a>\n");
            for (String name : moduleSpecs.keySet()) {
                sidebarItems.append("<a href='#' class='sidebar-item' onclick='loadSpec(\"")
                    .append(name).append("\"); return false;'>").append(name).append("</a>\n");
            }

            StringBuilder specUrls = new StringBuilder("[");
            specUrls.append("\"all\"");
            for (String name : moduleSpecs.keySet()) {
                specUrls.append(", \"").append(name).append("\"");
            }
            specUrls.append("]");

            String html = "<!DOCTYPE html>\n<html>\n<head>\n"
                + "  <title>OpenMRS REST API</title>\n"
                + "  <meta charset='utf-8'/>\n"
                + "  <link rel='stylesheet' href='https://unpkg.com/swagger-ui-dist/swagger-ui.css'>\n"
                + "  <style>\n"
                + "    body { margin: 0; display: flex; font-family: sans-serif; }\n"
                + "    #sidebar {\n"
                + "      width: 220px; min-width: 220px; height: 100vh; overflow-y: auto;\n"
                + "      background: #1b1b1b; padding: 16px 0; box-sizing: border-box;\n"
                + "      position: sticky; top: 0;\n"
                + "    }\n"
                + "    #sidebar h3 { color: #fff; margin: 0 16px 12px; font-size: 13px; text-transform: uppercase; letter-spacing: 1px; }\n"
                + "    .sidebar-item {\n"
                + "      display: block; padding: 8px 16px; color: #ccc; text-decoration: none;\n"
                + "      font-size: 13px; border-left: 3px solid transparent;\n"
                + "    }\n"
                + "    .sidebar-item:hover { background: #2a2a2a; color: #fff; }\n"
                + "    .sidebar-item.active { color: #fff; border-left-color: #89bf04; background: #2a2a2a; }\n"
                + "    #swagger-container { flex: 1; overflow-y: auto; height: 100vh; }\n"
                + "  </style>\n"
                + "</head>\n<body>\n"
                + "  <div id='sidebar'>\n"
                + "    <h3>Modules</h3>\n"
                + sidebarItems
                + "  </div>\n"
                + "  <div id='swagger-container'><div id='swagger-ui'></div></div>\n"
                + "  <script src='https://unpkg.com/swagger-ui-dist/swagger-ui-bundle.js'></script>\n"
                + "  <script>\n"
                + "    const SPECS = " + specUrls + ";\n"
                + "    let ui = null;\n"
                + "    function loadSpec(name) {\n"
                + "      document.querySelectorAll('.sidebar-item').forEach(el => el.classList.remove('active'));\n"
                + "      const link = [...document.querySelectorAll('.sidebar-item')].find(el => el.textContent === name || (name === 'all' && el.textContent === 'All'));\n"
                + "      if (link) link.classList.add('active');\n"
                + "      document.getElementById('swagger-ui').innerHTML = '';\n"
                + "      ui = SwaggerUIBundle({\n"
                + "        url: '/specs/' + name + '/openapi.json',\n"
                + "        dom_id: '#swagger-ui',\n"
                + "        presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],\n"
                + "        layout: 'BaseLayout',\n"
                + "        deepLinking: false,\n"
                + "        filter: true\n"
                + "      });\n"
                + "      history.replaceState(null, '', '?module=' + name);\n"
                + "    }\n"
                + "    const initial = new URLSearchParams(window.location.search).get('module') || 'all';\n"
                + "    loadSpec(SPECS.includes(initial) ? initial : 'all');\n"
                + "  </script>\n"
                + "</body>\n</html>";
            send(exchange, 200, "text/html; charset=utf-8", html);
        }

        private void serveJson(HttpExchange exchange, byte[] bytes) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }

        private void serveFile(HttpExchange exchange, File file) throws IOException {
            byte[] bytes = Files.readAllBytes(file.toPath());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }

        private void proxy(HttpExchange exchange, String targetUrl) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            conn.setRequestMethod(exchange.getRequestMethod());
            conn.setDoInput(true);
            conn.setInstanceFollowRedirects(true);

            for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                String name = header.getKey().toLowerCase();
                if (!HOP_BY_HOP.contains(name)) {
                    conn.setRequestProperty(header.getKey(), header.getValue().get(0));
                }
            }

            String method = exchange.getRequestMethod().toUpperCase();
            if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                conn.setDoOutput(true);
                byte[] body = readFully(exchange.getRequestBody());
                conn.getOutputStream().write(body);
                conn.getOutputStream().close();
            }

            int status = conn.getResponseCode();

            for (Map.Entry<String, List<String>> header : conn.getHeaderFields().entrySet()) {
                if (header.getKey() == null) continue;
                String name = header.getKey().toLowerCase();
                if (!HOP_BY_HOP.contains(name)) {
                    exchange.getResponseHeaders().set(header.getKey(), header.getValue().get(0));
                }
            }

            InputStream responseStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] responseBody = responseStream != null ? readFully(responseStream) : new byte[0];
            exchange.sendResponseHeaders(status, responseBody.length);
            if (responseBody.length > 0) exchange.getResponseBody().write(responseBody);
            exchange.getResponseBody().close();
        }

        private static byte[] readFully(InputStream in) throws IOException {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }

        private void send(HttpExchange exchange, int status, String contentType, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }
    }
}
