package org.dldyou.rovenfall.administration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/** Loopback-only HTTP host for the bundled Rovenfall administration console. */
public final class AdminHttpServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String ASSET_ROOT = "/assets/rovenfall/admin/";
    private static final int MAX_REQUEST_BODY_BYTES = 16 * 1_024;
    private static final int MAX_URI_LENGTH = 2_048;
    private static final long SERVER_OPERATION_TIMEOUT_SECONDS = 10L;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static HttpServer httpServer;
    private static ExecutorService executor;
    private static MinecraftServer minecraftServer;
    private static AdminTokenStore.Token token;

    private AdminHttpServer() {
    }

    public static void register(IEventBus eventBus) {
        eventBus.addListener(AdminHttpServer::onServerStarted);
        eventBus.addListener(AdminHttpServer::onServerStopping);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        if (!AdminBridgeConfig.enabled()) {
            LOGGER.info("Rovenfall administration console is disabled");
            return;
        }
        synchronized (LIFECYCLE_LOCK) {
            if (httpServer != null) {
                return;
            }
            try {
                AdminGateway.clearActionPreviews();
                if (AdminHttpServer.class.getResource(ASSET_ROOT + "index.html") == null) {
                    LOGGER.error("Rovenfall administration console assets are missing from the mod JAR");
                    return;
                }
                token = AdminTokenStore.load();
                minecraftServer = event.getServer();
                InetSocketAddress address = new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), AdminBridgeConfig.port());
                HttpServer created = HttpServer.create(address, 16);
                ExecutorService createdExecutor = Executors.newFixedThreadPool(3, runnable -> {
                    Thread thread = new Thread(runnable, "rovenfall-admin-http");
                    thread.setDaemon(true);
                    return thread;
                });
                created.setExecutor(createdExecutor);
                created.createContext("/", AdminHttpServer::handle);
                created.start();
                httpServer = created;
                executor = createdExecutor;
                LOGGER.info("Rovenfall administration console listening on http://127.0.0.1:{}/",
                        address.getPort());
                token.path().ifPresent(path -> LOGGER.info(
                        "Rovenfall administration token is stored at {}", path));
            } catch (IOException exception) {
                minecraftServer = null;
                token = null;
                LOGGER.error("Rovenfall administration console could not start", exception);
            }
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        synchronized (LIFECYCLE_LOCK) {
            HttpServer running = httpServer;
            ExecutorService runningExecutor = executor;
            httpServer = null;
            executor = null;
            minecraftServer = null;
            token = null;
            AdminGateway.clearActionPreviews();
            if (running != null) {
                running.stop(1);
            }
            if (runningExecutor != null) {
                runningExecutor.shutdownNow();
            }
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            addSecurityHeaders(exchange);
            String rawUri = exchange.getRequestURI().toASCIIString();
            if (rawUri.length() > MAX_URI_LENGTH) {
                sendJson(exchange, 414, errorBody("URI_TOO_LONG", "The request URI is too long."));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                handleApi(exchange, path);
            } else {
                handleAsset(exchange, path);
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.warn("Rovenfall administration request failed", exception);
            if (exchange.getResponseCode() == -1) {
                sendJson(exchange, 500, errorBody("INTERNAL_ERROR", "The request could not be completed."));
            }
        } finally {
            exchange.close();
        }
    }

    private static void handleApi(HttpExchange exchange, String path) throws IOException {
        if (!authorized(exchange)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"Rovenfall Admin\"");
            sendJson(exchange, 401, errorBody("UNAUTHORIZED", "A valid bearer token is required."));
            return;
        }
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!(method.equals("GET") || method.equals("POST"))) {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
            sendJson(exchange, 405, errorBody("METHOD_NOT_ALLOWED", "This API route does not support that method."));
            return;
        }
        JsonObject body = null;
        if (method.equals("POST")) {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                sendJson(exchange, 415, errorBody("UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json."));
                return;
            }
            byte[] bytes = readBounded(exchange.getRequestBody());
            if (bytes == null) {
                sendJson(exchange, 413, errorBody("BODY_TOO_LARGE", "The request body is too large."));
                return;
            }
            try {
                body = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            } catch (JsonParseException exception) {
                sendJson(exchange, 400, errorBody("INVALID_JSON", "The request body is not valid JSON."));
                return;
            }
        }

        MinecraftServer server = minecraftServer;
        if (server == null || server.isStopped()) {
            sendJson(exchange, 503, errorBody("SERVER_UNAVAILABLE", "The Minecraft server is stopping."));
            return;
        }
        Map<String, String> query;
        try {
            query = parseQuery(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, errorBody("INVALID_QUERY", "The query string is invalid."));
            return;
        }

        CompletableFuture<AdminGateway.Response> future = new CompletableFuture<>();
        JsonObject requestBody = body;
        server.execute(() -> {
            try {
                future.complete(AdminGateway.handle(server, method, path, query, requestBody));
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        });
        try {
            AdminGateway.Response response = future.get(
                    SERVER_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            sendJson(exchange, response.status(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 503, errorBody("REQUEST_INTERRUPTED", "The server operation was interrupted."));
        } catch (ExecutionException exception) {
            LOGGER.error("Rovenfall administration server operation failed", exception.getCause());
            sendJson(exchange, 500, errorBody("INTERNAL_ERROR", "The server operation failed."));
        } catch (TimeoutException exception) {
            sendJson(exchange, 504, errorBody("SERVER_TIMEOUT", "The Minecraft server did not respond in time."));
        }
    }

    private static void handleAsset(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (!(method.equals("GET") || method.equals("HEAD"))) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            sendJson(exchange, 405, errorBody("METHOD_NOT_ALLOWED", "Static content only supports GET and HEAD."));
            return;
        }
        String relative = path.equals("/") || path.isBlank() ? "index.html" : path.substring(1);
        if (relative.contains("..") || relative.contains("\\") || relative.startsWith("/")) {
            sendJson(exchange, 404, errorBody("NOT_FOUND", "The requested file does not exist."));
            return;
        }
        byte[] bytes = resource(relative);
        if (bytes == null && !relative.contains(".")) {
            relative = "index.html";
            bytes = resource(relative);
        }
        if (bytes == null) {
            sendJson(exchange, 404, errorBody("NOT_FOUND", "The requested file does not exist."));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", contentType(relative));
        exchange.getResponseHeaders().set("Cache-Control",
                relative.equals("index.html") ? "no-store"
                        : relative.startsWith("assets/") ? "public, max-age=31536000, immutable"
                        : "no-cache");
        if (method.equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static boolean authorized(HttpExchange exchange) {
        AdminTokenStore.Token currentToken = token;
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (currentToken == null || authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        return currentToken.matches(authorization.substring("Bearer ".length()));
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int total = 0;
        for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
            total += count;
            if (total > MAX_REQUEST_BODY_BYTES) {
                return null;
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&", -1)) {
            int separator = pair.indexOf('=');
            String rawKey = separator < 0 ? pair : pair.substring(0, separator);
            String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            if (!key.isBlank() && key.length() <= 64 && value.length() <= 512) {
                result.putIfAbsent(key, value);
            }
        }
        return Map.copyOf(result);
    }

    private static byte[] resource(String relative) throws IOException {
        try (InputStream input = AdminHttpServer.class.getResourceAsStream(ASSET_ROOT + relative)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private static String contentType(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (normalized.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (normalized.endsWith(".js") || normalized.endsWith(".mjs")) {
            return "text/javascript; charset=utf-8";
        }
        if (normalized.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (normalized.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (normalized.endsWith(".woff")) {
            return "font/woff";
        }
        if (normalized.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Robots-Tag", "noindex, nofollow");
        exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'; "
                        + "object-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                        + "font-src 'self'; img-src 'self' data:; connect-src 'self'");
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static Map<String, Object> errorBody(String code, String message) {
        return Map.of("ok", false, "error", Map.of("code", code, "message", message));
    }
}
