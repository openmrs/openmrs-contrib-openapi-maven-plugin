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
 * java -jar openapi-dev-server.jar --server=&lt;url&gt; [--port=9000] [--self-check] &lt;module-path&gt;...
 * </pre>
 *
 * <h2>URL layout</h2>
 * <pre>
 * /                              the UI
 * /index.json                    navigation index — every resource and controller, all modules
 * /slices/&lt;module&gt;/&lt;Name&gt;.json    one resource or controller as a self-contained document
 * /specs/&lt;module&gt;/openapi.json   a whole module, cross-module $refs resolved
 * /specs/all/openapi.json        every loaded module in one document
 * /proxy/*                       reverse proxy to --server, so "try it" avoids CORS
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
        String serverUrl = null;
        boolean selfCheck = false;
        List<String> modulePaths = new ArrayList<String>();

        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.startsWith("--server=")) {
                serverUrl = arg.substring("--server=".length());
            } else if (arg.equals("--self-check")) {
                selfCheck = true;
            } else {
                modulePaths.add(arg);
            }
        }

        if (modulePaths.isEmpty() || serverUrl == null) {
            System.err.println("Usage: java -jar openapi-dev-server.jar --server=<url> "
                + "[--port=9000] [--self-check] <module-path>...");
            System.exit(1);
        }

        String upstreamUrl = serverUrl.endsWith("/")
            ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;

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
        server.createContext("/", new Router(catalog, slicer, StaticAssets.locate(), upstreamUrl));
        server.start();

        System.out.println();
        System.out.println("Serving OpenMRS API docs at http://localhost:" + port + "/");
        System.out.println("Proxying API requests to " + upstreamUrl);
    }

    static class Router implements HttpHandler {

        private final SpecCatalog catalog;
        private final SpecSlicer slicer;
        private final StaticAssets assets;
        private final RendererAssets renderer = new RendererAssets();
        private final String upstreamUrl;
        /** Built once — parsing the specs is the expensive half and that already happened. */
        private final byte[] index;
        /** Whole-module and merged specs, serialised on first request. */
        private final Map<String, byte[]> fullSpecs =
            new java.util.concurrent.ConcurrentHashMap<String, byte[]>();

        Router(SpecCatalog catalog, SpecSlicer slicer, StaticAssets assets, String upstreamUrl) {
            this.catalog = catalog;
            this.slicer = slicer;
            this.assets = assets;
            this.upstreamUrl = upstreamUrl;
            this.index = DocIndex.build(catalog);
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
            if (RendererAssets.handles(name)) {
                try {
                    sendBytes(exchange, 200, StaticAssets.contentType(name), renderer.read(name));
                } catch (IOException e) {
                    send(exchange, 502, "text/plain; charset=utf-8",
                        "Could not fetch the renderer bundle (" + name + "): " + e
                            + "\nThe first run needs network access; after that it is cached.");
                }
                return;
            }
            byte[] bytes = assets.read(name);
            if (bytes == null) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found: " + name);
                return;
            }
            sendBytes(exchange, 200, StaticAssets.contentType(name), bytes);
        }

        private void proxy(HttpExchange exchange, String upstreamPath) throws IOException {
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
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            // Everything served here is derived from files on disk that change under the server,
            // so caching would mostly serve to hide a regenerate.
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
