package site.vinoff.market.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import site.vinoff.market.core.MarketError;
import site.vinoff.market.core.MarketException;
import site.vinoff.market.core.MarketService;
import site.vinoff.market.core.model.Listing;
import site.vinoff.market.core.model.ListingItem;
import site.vinoff.market.core.model.MarketEventRecord;
import site.vinoff.market.core.model.PendingDelivery;
import site.vinoff.market.core.model.Trade;
import site.vinoff.market.storage.Database;
import site.vinoff.market.storage.DeliveryRepository;

/**
 * The marketplace as seen by the website's backend: read only views plus the few actions that do not need the player
 * to be in the game. It listens on the loopback address only, so nothing outside the machine can reach it, and every
 * request carries a shared token.
 *
 * <p>The website never learns where items are stored and can never move one: it can only ask the plugin to run an
 * operation the plugin itself validates.
 */
public final class ApiServer {

    public static final String BASE = "/api/v1/market";

    private final MarketService market;
    private final Database database;
    private final DeliveryRepository deliveries;
    private final String token;
    private final Logger log;
    private final int rateLimitPerMinute;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService workers;

    public ApiServer(
            MarketService market, Database database, DeliveryRepository deliveries, String token, int rateLimitPerMinute, Logger log) {
        this.market = market;
        this.database = database;
        this.deliveries = deliveries;
        this.token = token;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.log = log;
    }

    public void start(String bindAddress, int port) throws IOException {
        InetAddress address = "localhost".equalsIgnoreCase(bindAddress) || "127.0.0.1".equals(bindAddress)
                ? InetAddress.getLoopbackAddress()
                : InetAddress.getByName(bindAddress);
        server = HttpServer.create(new InetSocketAddress(address, port), 0);
        workers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "market-api");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext(BASE, this::route);
        server.start();
        log.info("Marketplace API is listening on " + address.getHostAddress() + ":" + port);
        if (!address.isLoopbackAddress()) {
            log.warning("The marketplace API is not bound to localhost; make sure a firewall keeps it off the internet");
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            try {
                workers.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            workers = null;
        }
    }

    // routing ------------------------------------------------------------------------------------------------------

    private void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath().substring(BASE.length());
            String method = exchange.getRequestMethod();
            if (!authorised(exchange)) {
                log.warning("Rejected a marketplace API request with a wrong token from " + exchange.getRemoteAddress().getAddress());
                send(exchange, 401, Json.error("UNAUTHORIZED", "A valid API token is required"));
                return;
            }
            if (!withinRateLimit(exchange)) {
                send(exchange, 429, Json.error("RATE_LIMITED", "Too many requests"));
                return;
            }
            if ("GET".equals(method)) {
                handleGet(exchange, path);
            } else if ("POST".equals(method)) {
                handlePost(exchange, path);
            } else {
                send(exchange, 405, Json.error("METHOD_NOT_ALLOWED", "Use GET or POST"));
            }
        } catch (MarketException refused) {
            send(exchange, refused.error().httpStatus(), Json.error(refused.error().name(), refused.getMessage()));
        } catch (IllegalArgumentException badInput) {
            send(exchange, 400, Json.error("INVALID_REQUEST", badInput.getMessage()));
        } catch (RuntimeException failure) {
            log.warning("Marketplace API request failed: " + failure);
            send(exchange, 500, Json.error("INTERNAL_ERROR", "The marketplace could not answer"));
        } finally {
            exchange.close();
        }
    }

    private void handleGet(HttpExchange exchange, String path) throws IOException {
        Map<String, String> query = query(exchange);
        if (path.equals("/health")) {
            send(exchange, 200, Json.object().field("ok", true).field("schema", database.schemaVersion()).done());
            return;
        }
        if (path.equals("/listings")) {
            int limit = clamp(number(query.get("limit"), 25), 1, 100);
            int offset = Math.max(0, number(query.get("offset"), 0));
            site.vinoff.market.core.ListingType type =
                    query.get("type") == null ? null : site.vinoff.market.core.ListingType.valueOf(query.get("type").toUpperCase(Locale.ROOT));
            Json array = Json.array();
            market.activeListings(type, limit, offset).forEach(listing -> array.add(listingJson(listing)));
            send(exchange, 200, Json.object().field("listings", array).done());
            return;
        }
        if (path.startsWith("/listings/")) {
            long id = pathId(path, "/listings/");
            Listing listing = market.listing(id).orElseThrow(() -> new MarketException(MarketError.LISTING_NOT_FOUND, "No such listing"));
            send(exchange, 200, listingJson(listing).done());
            return;
        }
        if (path.startsWith("/players/")) {
            String[] parts = path.substring("/players/".length()).split("/");
            UUID player = uuid(parts[0]);
            String what = parts.length > 1 ? parts[1] : "";
            switch (what) {
                case "listings" -> {
                    Json array = Json.array();
                    market.listingsOf(player, true).forEach(listing -> array.add(listingJson(listing)));
                    send(exchange, 200, Json.object().field("listings", array).done());
                }
                case "trades" -> {
                    Json array = Json.array();
                    market.tradesOf(player, true).forEach(trade -> array.add(tradeJson(trade)));
                    send(exchange, 200, Json.object().field("trades", array).done());
                }
                case "deliveries" -> {
                    Json array = Json.array();
                    for (PendingDelivery delivery : market.pendingDeliveries(player)) {
                        array.add(Json.object()
                                .field("id", delivery.id())
                                .field("item", delivery.item().summary())
                                .field("amount", delivery.item().amount())
                                .field("reason", delivery.reason().name())
                                .field("createdAt", delivery.createdAt().toString()));
                    }
                    send(exchange, 200, Json.object().field("deliveries", array).done());
                }
                default -> send(exchange, 404, Json.error("NOT_FOUND", "Unknown player view"));
            }
            return;
        }
        if (path.equals("/events")) {
            long since = number(query.get("since"), 0);
            int limit = clamp(number(query.get("limit"), 100), 1, 500);
            Json array = Json.array();
            for (MarketEventRecord event : market.events(since, limit)) {
                array.add(Json.object()
                        .field("id", event.id())
                        .field("at", event.at().toString())
                        .field("type", event.type())
                        .field("listingId", event.listingId() == null ? 0 : event.listingId())
                        .field("tradeId", event.tradeId() == null ? 0 : event.tradeId()));
            }
            send(exchange, 200, Json.object().field("events", array).done());
            return;
        }
        send(exchange, 404, Json.error("NOT_FOUND", "Unknown endpoint"));
    }

    private void handlePost(HttpExchange exchange, String path) throws IOException {
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            send(exchange, 400, Json.error("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header"));
            return;
        }
        String endpoint = "POST " + path;
        Optional<DeliveryRepository.StoredResponse> already =
                database.read(connection -> deliveries.apiResponse(connection, idempotencyKey, endpoint));
        if (already.isPresent()) {
            // the website repeated itself, for example after a timeout: give the same answer, do nothing again
            send(exchange, already.get().status(), already.get().body());
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        UUID player = uuid(Json.readString(body, "minecraftUuid"));
        int status;
        String answer;
        try {
            answer = perform(path, player);
            status = 200;
        } catch (MarketException refused) {
            status = refused.error().httpStatus();
            answer = Json.error(refused.error().name(), refused.getMessage());
        }
        Instant now = Instant.now();
        int finalStatus = status;
        String finalAnswer = answer;
        database.inTransaction((Connection connection) -> {
            deliveries.storeApiResponse(connection, idempotencyKey, endpoint, finalStatus, finalAnswer, now);
            return null;
        });
        send(exchange, status, answer);
    }

    /** The actions the website may ask for. Every one of them checks ownership inside the core. */
    private String perform(String path, UUID player) {
        if (path.startsWith("/listings/") && path.endsWith("/cancel")) {
            long id = pathId(path.substring(0, path.length() - "/cancel".length()), "/listings/");
            market.cancel(player, id, false);
            return Json.object().field("ok", true).field("listingId", id).done();
        }
        if (path.startsWith("/trades/")) {
            String rest = path.substring("/trades/".length());
            String[] parts = rest.split("/");
            long id = Long.parseLong(parts[0]);
            String action = parts.length > 1 ? parts[1] : "";
            switch (action) {
                case "accept" -> market.acceptTrade(player, id);
                case "decline" -> market.declineTrade(player, id, false);
                case "confirm" -> {
                    boolean done = market.confirmTrade(player, id);
                    return Json.object().field("ok", true).field("tradeId", id).field("completed", done).done();
                }
                default -> throw new IllegalArgumentException("Unknown trade action");
            }
            return Json.object().field("ok", true).field("tradeId", id).done();
        }
        throw new IllegalArgumentException("Unknown endpoint");
    }

    // shapes -------------------------------------------------------------------------------------------------------

    private Json listingJson(Listing listing) {
        Json offered = Json.array();
        for (ListingItem item : listing.offered()) {
            offered.add(itemJson(item));
        }
        Json wanted = Json.array();
        for (ListingItem item : listing.wanted()) {
            wanted.add(itemJson(item));
        }
        return Json.object()
                .field("id", listing.id())
                .field("type", listing.type().name())
                .field("state", listing.state().name())
                .field("ownerUuid", listing.ownerUuid().toString())
                .field("recipientUuid", listing.recipient().map(UUID::toString).orElse(null))
                .field("summary", MarketService.describe(listing))
                .field("createdAt", listing.createdAt().toString())
                .field("offered", offered)
                .field("wanted", wanted);
    }

    private Json itemJson(ListingItem item) {
        return Json.object()
                .field("summary", item.item().summary())
                .field("amount", item.item().amount())
                .field("sha256", item.item().sha256());
    }

    private Json tradeJson(Trade trade) {
        Json confirmations = Json.array();
        trade.confirmations().forEach(party -> confirmations.add(party.name()));
        return Json.object()
                .field("id", trade.id())
                .field("listingId", trade.listingId())
                .field("ownerUuid", trade.ownerUuid().toString())
                .field("buyerUuid", trade.buyerUuid().toString())
                .field("state", trade.state().name())
                .field("confirmations", confirmations)
                .field("createdAt", trade.createdAt().toString());
    }

    // plumbing -----------------------------------------------------------------------------------------------------

    private boolean authorised(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String provided = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : "";
        byte[] left = provided.getBytes(StandardCharsets.UTF_8);
        byte[] right = token.getBytes(StandardCharsets.UTF_8);
        int size = Math.max(Math.max(left.length, right.length), 64);
        byte[] paddedLeft = new byte[size];
        byte[] paddedRight = new byte[size];
        System.arraycopy(left, 0, paddedLeft, 0, left.length);
        System.arraycopy(right, 0, paddedRight, 0, right.length);
        return java.security.MessageDigest.isEqual(paddedLeft, paddedRight) && left.length == right.length;
    }

    private boolean withinRateLimit(HttpExchange exchange) {
        String key = String.valueOf(exchange.getRemoteAddress().getAddress());
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(key, (ignored, current) ->
                current == null || current.minute != minute ? new Window(minute) : current);
        return window.hits.incrementAndGet() <= rateLimitPerMinute;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        Map<String, String> values = new java.util.HashMap<>();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                values.put(
                        java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private static long pathId(String path, String prefix) {
        try {
            return Long.parseLong(path.substring(prefix.length()).split("/")[0]);
        } catch (RuntimeException notANumber) {
            throw new IllegalArgumentException("The id in the path is not a number");
        }
    }

    private static UUID uuid(String value) {
        if (value == null) {
            throw new IllegalArgumentException("minecraftUuid is required");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            throw new IllegalArgumentException("minecraftUuid is not a UUID");
        }
    }

    private static int number(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Requests seen from one address inside one minute. */
    private static final class Window {
        private final long minute;
        private final AtomicInteger hits = new AtomicInteger();

        private Window(long minute) {
            this.minute = minute;
        }
    }

    /** Used by the plugin to tell an administrator what the API is doing. */
    public List<String> describe() {
        return List.of(BASE + "/health", BASE + "/listings", BASE + "/events?since=0");
    }
}
