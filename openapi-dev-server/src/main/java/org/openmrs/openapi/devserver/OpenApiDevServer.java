package org.openmrs.openapi.devserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Dev server for the generated OpenAPI specs: a searchable tree of every resource, sub-resource
 * and controller across every loaded module, each rendered from its own small document.
 *
 * <pre>
 * java -jar openapi-dev-server.jar [--auth=&lt;file&gt;] [--port=9000] [--self-check] &lt;module-path&gt;...
 * </pre>
 *
 * <h2>The {@code --auth} file</h2>
 * A JSON file with {@code server}, {@code username} and {@code password} — e.g. {@code dev3.json}.
 * When given, the UI's "try it out" is enabled and every proxied request is authenticated with the
 * file's credentials server-side, so the user never types a password into the UI. When omitted, the
 * docs render read-only: there is no upstream to proxy to and "try it out" is disabled.
 *
 * <h2>URL layout</h2>
 * <pre>
 * /                              the UI
 * /config.json                   UI config — whether "try it out" is enabled
 * /index.json                    navigation index — every resource and controller, all modules
 * /slices/&lt;module&gt;/&lt;Name&gt;.json    one resource or controller as a self-contained document
 * /specs/&lt;module&gt;/openapi.json   a whole module, cross-module $refs resolved
 * /specs/all/openapi.json        every loaded module in one document
 * /proxy/*                       reverse proxy to the --auth file's server, so "try it" avoids CORS
 * </pre>
 *
 * <h2>Structure</h2>
 * Everything below this class is HTTP-free: {@link SpecCatalog} holds the parsed specs and owns
 * the one cross-module name-resolution rule, {@link DocIndex} builds the navigation index and
 * {@link SpecSlicer} cuts per-resource documents on demand. This class is only the shell — which
 * is deliberate, because the intended home for this UI is a Spring {@code @Controller} in the REST
 * module serving docs for whatever modules an OpenMRS instance has installed. That move should
 * rewrite the shell and nothing else.
 */
public class OpenApiDevServer {

    private static final int DEFAULT_PORT = 9000;
    private static final String PROXY_PATH = "/proxy";

    private static final List<String> HOP_BY_HOP = Arrays.asList(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length"
    );

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String authFile = null;
        boolean selfCheck = false;
        List<String> modulePaths = new ArrayList<String>();

        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.startsWith("--auth=")) {
                authFile = arg.substring("--auth=".length());
            } else if (arg.equals("--self-check")) {
                selfCheck = true;
            } else if (arg.startsWith("--server=")) {
                // --server=<url> was removed: the server and its credentials now come from a JSON
                // file. Say so explicitly, rather than letting the URL fall through to the module
                // list and fail later with a misleading "no modules generated".
                System.err.println("Error: --server=<url> was removed. "
                    + "Use --auth=<file> instead, with a JSON file holding server/username/password "
                    + "(see dev3.json).");
                System.exit(1);
            } else if (arg.startsWith("--")) {
                System.err.println("Error: unknown option " + arg);
                usageAndExit();
            } else {
                modulePaths.add(arg);
            }
        }

        if (modulePaths.isEmpty()) {
            usageAndExit();
        }

        // Absent --auth is the read-only case: no upstream, no credentials, "try it out" off.
        AuthConfig auth = authFile == null ? null : AuthConfig.load(authFile);
        String upstreamUrl = auth == null ? null : auth.server;
        String credentials = auth == null ? null : auth.basicAuthHeader();
        boolean tryItOut = auth != null;

        SpecCatalog catalog = SpecCatalog.load(modulePaths);
        for (String module : catalog.modules()) {
            System.out.println("Loaded: " + module + " -> " + catalog.directory(module));
        }
        for (String warning : catalog.warnings()) {
            System.err.println("Warning: " + warning);
        }
        if (catalog.modules().isEmpty()) {
            System.err.println("No modules with generated OpenAPI output. Run ./generate.sh first.");
            System.exit(1);
        }

        SpecSlicer slicer = new SpecSlicer(catalog, PROXY_PATH, upstreamUrl);

        if (selfCheck && !SelfCheck.run(catalog, slicer)) {
            System.err.println("Self-check failed: some slices do not resolve on their own.");
            System.exit(1);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/",
            new Router(catalog, slicer, StaticAssets.locate(), upstreamUrl, credentials, tryItOut));
        server.start();

        System.out.println();
        System.out.println("Serving OpenMRS API docs at http://localhost:" + port + "/");
        if (tryItOut) {
            System.out.println("\"Try it out\" enabled — proxying authenticated API requests to "
                + upstreamUrl);
        } else {
            System.out.println("Read-only: no --auth file, so \"try it out\" is disabled.");
        }
    }

    private static void usageAndExit() {
        System.err.println("Usage: java -jar openapi-dev-server.jar [--auth=<file>] "
            + "[--port=9000] [--self-check] <module-path>...");
        System.exit(1);
    }

    /**
     * The {@code --auth} file: which OpenMRS instance to proxy to and the credentials to reach it.
     * All three fields are required; a missing or blank one fails the run rather than serving docs
     * whose "try it out" would silently 401.
     */
    static final class AuthConfig {

        final String server;
        final String username;
        final String password;

        private AuthConfig(String server, String username, String password) {
            this.server = server;
            this.username = username;
            this.password = password;
        }

        static AuthConfig load(String path) {
            com.fasterxml.jackson.databind.JsonNode root;
            try {
                root = Json.MAPPER.readTree(new java.io.File(path));
            } catch (java.io.FileNotFoundException e) {
                System.err.println("Error: --auth file not found: " + path);
                System.exit(1);
                return null;
            } catch (IOException e) {
                System.err.println("Error: could not parse --auth file " + path + " as JSON: "
                    + e.getMessage());
                System.exit(1);
                return null;
            }

            List<String> missing = new ArrayList<String>();
            String server = text(root, "server", missing);
            String username = text(root, "username", missing);
            String password = text(root, "password", missing);
            if (!missing.isEmpty()) {
                System.err.println("Error: --auth file " + path
                    + " is missing required field(s): " + String.join(", ", missing)
                    + ". It must be a JSON object with server, username and password.");
                System.exit(1);
            }

            // The proxy computes upstreamUrl + requestPath, so a trailing slash would double up.
            String normalized = server.endsWith("/")
                ? server.substring(0, server.length() - 1) : server;
            return new AuthConfig(normalized, username, password);
        }

        /** Null if the field is absent or blank (a blank credential is a likelier typo than intent). */
        private static String text(com.fasterxml.jackson.databind.JsonNode root, String field,
                List<String> missing) {
            com.fasterxml.jackson.databind.JsonNode node = root.get(field);
            String value = node != null && node.isTextual() ? node.asText().trim() : "";
            if (value.isEmpty()) {
                missing.add(field);
                return null;
            }
            return value;
        }

        /** The {@code Authorization: Basic ...} header value the proxy attaches to every request. */
        String basicAuthHeader() {
            String token = java.util.Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            return "Basic " + token;
        }
    }

    static class Router implements HttpHandler {

        private final SpecCatalog catalog;
        private final SpecSlicer slicer;
        private final StaticAssets assets;
        private final String upstreamUrl;
        /** {@code Authorization: Basic ...} attached to every proxied request; null with no --auth. */
        private final String credentials;
        private final boolean tryItOut;
        /** Built once — parsing the specs is the expensive half and that already happened. */
        private final byte[] index;
        private final byte[] config;
        /** Whole-module and merged specs, serialised on first request. */
        private final Map<String, byte[]> fullSpecs =
            new java.util.concurrent.ConcurrentHashMap<String, byte[]>();

        Router(SpecCatalog catalog, SpecSlicer slicer, StaticAssets assets, String upstreamUrl,
                String credentials, boolean tryItOut) {
            this.catalog = catalog;
            this.slicer = slicer;
            this.assets = assets;
            this.upstreamUrl = upstreamUrl;
            this.credentials = credentials;
            this.tryItOut = tryItOut;
            this.index = DocIndex.build(catalog);
            this.config = Json.compact(Json.obj().put("tryItOut", tryItOut));
        }

        public void handle(HttpExchange exchange) throws IOException {
            try {
                dispatch(exchange);
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "text/plain; charset=utf-8", "Server error: " + e);
            } finally {
                exchange.close();
            }
        }

        private void dispatch(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            if (path.startsWith(PROXY_PATH + "/") || path.equals(PROXY_PATH)) {
                proxy(exchange, path.substring(PROXY_PATH.length()));
                return;
            }

            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
                return;
            }

            if (path.equals("/") || path.isEmpty()) {
                serveAsset(exchange, "index.html");
                return;
            }
            if (path.equals("/config.json")) {
                sendBytes(exchange, 200, "application/json", config);
                return;
            }
            if (path.equals("/index.json")) {
                sendBytes(exchange, 200, "application/json", index);
                return;
            }
            if (path.startsWith("/slices/")) {
                serveSlice(exchange, path.substring("/slices/".length()));
                return;
            }
            if (path.startsWith("/specs/")) {
                serveFullSpec(exchange, path.substring("/specs/".length()));
                return;
            }
            serveAsset(exchange, path.substring(1));
        }

        /** /slices/&lt;module&gt;/&lt;Name&gt;.json */
        private void serveSlice(HttpExchange exchange, String rest) throws IOException {
            int slash = rest.indexOf('/');
            if (slash < 0 || !rest.endsWith(".json")) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            String module = rest.substring(0, slash);
            String name = rest.substring(slash + 1, rest.length() - ".json".length());
            byte[] slice = slicer.slice(module, name);
            if (slice == null) {
                send(exchange, 404, "text/plain; charset=utf-8",
                    "No such resource or controller: " + module + "/" + name);
                return;
            }
            sendBytes(exchange, 200, "application/json", slice);
        }

        /** /specs/&lt;module&gt;/openapi.json, or /specs/all/openapi.json */
        private void serveFullSpec(HttpExchange exchange, String rest) throws IOException {
            if (!rest.endsWith("/openapi.json")) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found");
                return;
            }
            String module = rest.substring(0, rest.length() - "/openapi.json".length());
            byte[] cached = fullSpecs.get(module);
            if (cached == null) {
                if (module.equals("all")) {
                    cached = Json.pretty(Json.makePlayable(catalog.mergedAcrossModules(),
                        PROXY_PATH, upstreamUrl));
                } else if (catalog.modules().contains(module)) {
                    cached = Json.pretty(Json.makePlayable(catalog.enrichedSpec(module),
                        PROXY_PATH, upstreamUrl));
                } else {
                    send(exchange, 404, "text/plain; charset=utf-8", "Unknown module: " + module);
                    return;
                }
                fullSpecs.put(module, cached);
            }
            sendBytes(exchange, 200, "application/json", cached);
        }

        private void serveAsset(HttpExchange exchange, String name) throws IOException {
            byte[] bytes = assets.read(name);
            if (bytes == null) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found: " + name);
                return;
            }
            // The pinned renderer files are large (~1.7 MB together) and a fresh iframe re-requests
            // them on every resource click, yet never change for a given version — so cache them
            // hard. That stops the re-download and lets the browser reuse the parsed bytecode click
            // to click. Everything else is editable UI, kept no-store so a reload picks up edits.
            String cacheControl = RendererAssets.handles(name)
                ? "public, max-age=31536000, immutable" : "no-store";
            sendBytes(exchange, 200, StaticAssets.contentType(name), bytes, cacheControl);
        }

        private void proxy(HttpExchange exchange, String upstreamPath) throws IOException {
            if (upstreamUrl == null) {
                send(exchange, 503, "text/plain; charset=utf-8",
                    "No upstream configured: start the server with --auth=<file> to enable "
                        + "\"try it out\".");
                return;
            }

            String query = exchange.getRequestURI().getRawQuery();
            String target = upstreamUrl + upstreamPath + (query != null ? "?" + query : "");
            System.out.println("[proxy] " + exchange.getRequestMethod() + " " + target);

            HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setRequestMethod(exchange.getRequestMethod());
            connection.setInstanceFollowRedirects(true);
            for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
                if (!HOP_BY_HOP.contains(header.getKey().toLowerCase())) {
                    connection.setRequestProperty(header.getKey(), header.getValue().get(0));
                }
            }
            // Set after the copy loop so it wins: the credentials come from the --auth file, and the
            // browser sends none (the spec declares no security scheme), so this is the only source.
            connection.setRequestProperty("Authorization", credentials);

            String method = exchange.getRequestMethod().toUpperCase();
            if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                connection.setDoOutput(true);
                OutputStream upstream = connection.getOutputStream();
                upstream.write(readFully(exchange.getRequestBody()));
                upstream.close();
            }

            int status = connection.getResponseCode();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() != null
                        && !HOP_BY_HOP.contains(header.getKey().toLowerCase())) {
                    exchange.getResponseHeaders().set(header.getKey(), header.getValue().get(0));
                }
            }

            InputStream body = status >= 400 ? connection.getErrorStream()
                : connection.getInputStream();
            byte[] bytes = body != null ? readFully(body) : new byte[0];
            exchange.sendResponseHeaders(status, bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        }

        private static byte[] readFully(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }

        private void send(HttpExchange exchange, int status, String contentType, String body)
                throws IOException {
            sendBytes(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
        }

        private void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes)
                throws IOException {
            // Default: no-store. The specs, index and slices are cheap to reserve and are
            // regenerated out from under a running server, so caching them would mostly hide a
            // regenerate. The one exception is the renderer bundle — see serveAsset.
            sendBytes(exchange, status, contentType, bytes, "no-store");
        }

        private void sendBytes(HttpExchange exchange, int status, String contentType, byte[] bytes,
                String cacheControl) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Cache-Control", cacheControl);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
