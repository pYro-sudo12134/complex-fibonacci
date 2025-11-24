package org.example;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisOptions;
import org.apache.commons.math3.complex.Complex;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;

public class Fibonacci extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(Fibonacci.class);
    static private final Complex INV_SQRT_5 = new Complex(1 / Math.sqrt(5), 0);
    static private final Complex LOG_PHI = new Complex((1 + Math.sqrt(5)) / 2, 0).log();
    static private final Complex LOG_PSI = new Complex((1 - Math.sqrt(5)) / 2, 0).log();

    private static final int RATE_LIMIT_REQUESTS = 100;
    private static final int CIRCUIT_BREAKER_FAILURES = 5;
    private static final long CIRCUIT_BREAKER_TIMEOUT = 5000;
    private static final long CIRCUIT_BREAKER_RESET_TIMEOUT = 30000;

    private RedisAPI redisAPI;
    private ExecutorService executorService;
    private HttpServer server;
    private CircuitBreaker circuitBreaker;
    private RateLimiter globalRateLimiter;
    private final Map<String, RateLimiter> clientRateLimiters = new ConcurrentHashMap<>();
    private PrometheusMeterRegistry prometheusRegistry;

    @Override
    public void start(Promise<Void> startPromise) {
        logger.info("Starting Fibonacci verticle with Prometheus metrics");

        prometheusRegistry = new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT);

        executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

        initializeCircuitBreaker();
        initializeRateLimiters();

        RedisOptions redisOptions = new RedisOptions()
                .setConnectionString(System.getenv().getOrDefault("REDIS_URL", "redis://redis:6379"))
                .setMaxPoolSize(10)
                .setMaxWaitingHandlers(100);

        Redis.createClient(vertx, redisOptions)
                .connect()
                .onSuccess(conn -> {
                    redisAPI = RedisAPI.api(conn);
                    logger.info("Redis connected successfully");
                    setupHttpServer(startPromise);
                })
                .onFailure(err -> {
                    logger.warn("Redis connection failed: {}", err.getMessage());
                    redisAPI = null;
                    setupHttpServer(startPromise);
                });
    }

    private void initializeCircuitBreaker() {
        circuitBreaker = CircuitBreaker.create("fibonacci-circuit-breaker", vertx,
                new CircuitBreakerOptions()
                        .setMaxFailures(CIRCUIT_BREAKER_FAILURES)
                        .setTimeout(CIRCUIT_BREAKER_TIMEOUT)
                        .setResetTimeout(CIRCUIT_BREAKER_RESET_TIMEOUT)
                        .setNotificationPeriod(0)
        );

        circuitBreaker.openHandler(v -> {
            logger.warn("Circuit breaker opened - too many failures");
            prometheusRegistry.counter("circuit_breaker_state_changes", "state", "open").increment();
        });
        circuitBreaker.closeHandler(v -> {
            logger.info("Circuit breaker closed - service recovered");
            prometheusRegistry.counter("circuit_breaker_state_changes", "state", "closed").increment();
        });
        circuitBreaker.halfOpenHandler(v -> {
            logger.info("Circuit breaker half-open - testing if service recovered");
            prometheusRegistry.counter("circuit_breaker_state_changes", "state", "half_open").increment();
        });
    }

    private void initializeRateLimiters() {
        RateLimiterConfig globalConfig = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .limitForPeriod(RATE_LIMIT_REQUESTS)
                .timeoutDuration(Duration.ofMillis(100))
                .build();

        globalRateLimiter = RateLimiter.of("global-rate-limiter", globalConfig);
    }

    private RateLimiter getClientRateLimiter(String clientId) {
        return clientRateLimiters.computeIfAbsent(clientId, id -> {
            RateLimiterConfig clientConfig = RateLimiterConfig.custom()
                    .limitRefreshPeriod(Duration.ofMinutes(1))
                    .limitForPeriod(RATE_LIMIT_REQUESTS / 2)
                    .timeoutDuration(Duration.ofMillis(100))
                    .build();
            return RateLimiter.of("client-" + id, clientConfig);
        });
    }

    private void cleanupOldRateLimiters() {
        clientRateLimiters.entrySet().removeIf(entry -> {
            RateLimiter limiter = entry.getValue();
            return limiter.getMetrics().getNumberOfWaitingThreads() == 0;
        });
    }

    private void setupHttpServer(Promise<Void> startPromise) {
        logger.info("Setting up HTTP server on port 8080 with Prometheus metrics endpoint");
        server = vertx.createHttpServer(new HttpServerOptions().setPort(8080));

        server.requestHandler(request -> {
            prometheusRegistry.counter("http_requests_total",
                            "path", request.path(),
                            "method", request.method().toString(),
                            "client", request.remoteAddress().host())
                    .increment();

            if (!checkRateLimit(request)) {
                prometheusRegistry.counter("rate_limit_exceeded_total").increment();
                request.response()
                        .setStatusCode(429)
                        .end(new JsonObject()
                                .put("error", "Rate limit exceeded")
                                .put("retry_after", "60 seconds")
                                .encode());
                return;
            }

            if (request.method().name().equals("POST") && request.path().equals("/fibonacci")) {
                handlePostRequest(request);
            } else if (request.method().name().equals("GET") && request.path().equals("/fibonacci")) {
                handleGetRequest(request);
            } else if (request.method().name().equals("GET") && request.path().equals("/metrics")) {
                request.response().putHeader("Content-Type", "text/plain; version=0.0.4").end(prometheusRegistry.scrape());
            } else {
                prometheusRegistry.counter("http_errors_total", "type", "not_found").increment();
                request.response()
                        .setStatusCode(404)
                        .end(new JsonObject()
                                .put("error", "Not found")
                                .encode());
            }
        });

        server.listen(8080, result -> {
            if (result.succeeded()) {
                logger.info("HTTP server started successfully on port 8080");
                startPromise.complete();
            } else {
                logger.error("HTTP server failed to start", result.cause());
                startPromise.fail(result.cause());
            }
        });

        vertx.setPeriodic(300000, id -> cleanupOldRateLimiters());
    }

    private boolean checkRateLimit(@NotNull HttpServerRequest request) {
        String clientId = request.remoteAddress().host();

        try {
            RateLimiter.waitForPermission(globalRateLimiter);
            RateLimiter clientLimiter = getClientRateLimiter(clientId);
            RateLimiter.waitForPermission(clientLimiter);
            return true;
        } catch (Exception e) {
            logger.debug("Rate limit exceeded for client: {}", clientId);
            return false;
        }
    }

    private void handlePostRequest(@NotNull HttpServerRequest request) {
        request.bodyHandler(body -> {
            try {
                JsonObject json = body.toJsonObject();
                String input = json.getString("number");
                if (input == null || input.trim().isEmpty()) {
                    prometheusRegistry.counter("http_errors_total", "type", "bad_request").increment();
                    request.response()
                            .setStatusCode(400)
                            .end(new JsonObject()
                                    .put("error", "Missing 'number' field")
                                    .encode());
                    return;
                }
                processWithCircuitBreaker(request, input);
            } catch (Exception e) {
                prometheusRegistry.counter("http_errors_total", "type", "bad_request").increment();
                request.response()
                        .setStatusCode(400)
                        .end(new JsonObject()
                                .put("error", "Invalid JSON format")
                                .encode());
            }
        });
    }

    private void handleGetRequest(@NotNull HttpServerRequest request) {
        String input = request.getParam("number");
        if (input != null) {
            processWithCircuitBreaker(request, input);
        } else {
            prometheusRegistry.counter("http_errors_total", "type", "bad_request").increment();
            request.response()
                    .setStatusCode(400)
                    .end(new JsonObject()
                            .put("error", "Missing 'number' parameter")
                            .encode());
        }
    }

    private void processWithCircuitBreaker(HttpServerRequest request, String input) {
        circuitBreaker.executeWithFallback(promise ->
                processRequest(input)
                        .thenAccept(result -> {
                            JsonObject response = new JsonObject()
                                    .put("input", input)
                                    .put("result", formatComplex(result));
                            request.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(response.encode());
                            promise.complete();
                        })
                        .exceptionally(ex -> {
                            prometheusRegistry.counter("http_errors_total", "type", "internal_error").increment();
                            promise.fail(ex.getCause());
                            return null;
                        }), fallback -> {
            prometheusRegistry.counter("circuit_breaker_rejections").increment();
            request.response()
                    .setStatusCode(503)
                    .end(new JsonObject()
                            .put("error", "Service temporarily unavailable")
                            .put("message", "Circuit breaker open - too many failures")
                            .encode());
            return null;
        });
    }

    @Contract("_ -> new")
    private @NotNull CompletableFuture<Complex> processRequest(String input) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();
            try {
                Complex z = parseComplex(input);
                Complex result = execute(z).join();

                long duration = System.nanoTime() - startTime;
                prometheusRegistry.timer("fibonacci_calculation_time_nanoseconds")
                        .record(duration, TimeUnit.NANOSECONDS);

                prometheusRegistry.counter("fibonacci_calculations_total").increment();
                return result;
            } catch (NumberFormatException e) {
                prometheusRegistry.counter("calculation_errors_total", "type", "parse_error").increment();
                throw new RuntimeException("Invalid number format: " + e.getMessage());
            } catch (Exception e) {
                prometheusRegistry.counter("calculation_errors_total", "type", "computation_error").increment();
                throw new RuntimeException("Computation failed: " + e.getMessage());
            }
        }, executorService);
    }

    private CompletableFuture<Complex> execute(Complex z) {
        if (redisAPI == null) {
            return computeFibonacci(z);
        }

        String key = "fib:" + formatComplexForCache(z);

        return getFromCacheAsync(key)
                .thenCompose(cachedResult -> {
                    if (cachedResult != null) {
                        prometheusRegistry.counter("redis_cache_total", "type", "hit").increment();
                        return CompletableFuture.completedFuture(cachedResult);
                    }

                    prometheusRegistry.counter("redis_cache_total", "type", "miss").increment();
                    return computeFibonacci(z)
                            .thenCompose(result -> saveToCacheAsync(key, result)
                                    .thenApply(__ -> result)
                                    .exceptionally(ex -> {
                                        prometheusRegistry.counter("redis_errors_total").increment();
                                        return result;
                                    }));
                })
                .exceptionally(ex -> {
                    prometheusRegistry.counter("redis_errors_total").increment();
                    return computeFibonacci(z).join();
                });
    }

    @Contract("_ -> new")
    private @NotNull CompletableFuture<Complex> computeFibonacci(Complex z) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Complex result = z.multiply(LOG_PHI).exp()
                        .subtract(z.multiply(LOG_PSI).exp())
                        .multiply(INV_SQRT_5);

                if (result.isNaN() || result.isInfinite()) {
                    throw new ArithmeticException("Numerical instability in Fibonacci computation");
                }

                return result;
            } catch (Exception e) {
                throw new RuntimeException("Fibonacci computation via logarithms failed", e);
            }
        }, executorService);
    }

    private @NotNull CompletableFuture<Complex> getFromCacheAsync(String key) {
        CompletableFuture<Complex> future = new CompletableFuture<>();

        redisAPI.get(key)
                .onSuccess(response -> {
                    if (response != null) {
                        try {
                            Complex result = parseComplexFromCache(response.toString());
                            future.complete(result);
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                        }
                    } else {
                        future.complete(null);
                    }
                })
                .onFailure(future::completeExceptionally);

        return future;
    }

    private @NotNull CompletableFuture<Void> saveToCacheAsync(String key, Complex result) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        String value = formatComplexForCache(result);
        redisAPI.setex(key, "3600", value)
                .onSuccess(__ -> future.complete(null))
                .onFailure(future::completeExceptionally);

        return future;
    }

    private Complex parseComplex(@NotNull String input) {
        double[] values = Arrays.stream(
                        input.replace(',', '.')
                                .replace('+', ' ')
                                .split(" "))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .mapToDouble(Double::parseDouble)
                .toArray();

        return switch (values.length) {
            case 1 -> new Complex(values[0], 0);
            case 2 -> new Complex(values[0], values[1]);
            default -> throw new NumberFormatException("Not supported amount of variables");
        };
    }

    private @NotNull String formatComplex(@NotNull Complex z) {
        double re = z.getReal();
        double im = z.getImaginary();

        return (Double.compare(re, -0.0) == 0 ? "-0.0000000000000000" :
                String.format(java.util.Locale.US, "%.16f", re)) +
                (Double.compare(im, 0.0) >= 0 ? "+" : "-") +
                (Double.compare(Math.abs(im), -0.0) == 0 ? "0.0000000000000000" :
                        String.format(java.util.Locale.US, "%.16f", Math.abs(im))) +
                "i";
    }

    private @NotNull String formatComplexForCache(@NotNull Complex z) {
        double re = z.getReal();
        double im = z.getImaginary();

        return (Double.compare(re, -0.0) == 0 ? "-0.0000000000000000" :
                String.format(java.util.Locale.US, "%.16f", re)) +
                " " +
                (Double.compare(im, -0.0) == 0 ? "-0.0000000000000000" :
                        String.format(java.util.Locale.US, "%.16f", im));
    }

    @Contract("_ -> new")
    private @NotNull Complex parseComplexFromCache(@NotNull String cached) {
        String[] parts = cached.split(" ");
        if (parts.length != 2) {
            throw new RuntimeException("Invalid cache format: " + cached);
        }

        return new Complex(
                parts[0].equals("-0.0000000000000000") ? -0.0 : Double.parseDouble(parts[0]),
                parts[1].equals("-0.0000000000000000") ? -0.0 : Double.parseDouble(parts[1])
        );
    }

    @Override
    public void stop() {
        logger.info("Stopping Fibonacci verticle");
        if (server != null) {
            server.close();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        if (circuitBreaker != null) {
            circuitBreaker.close();
        }
        if (prometheusRegistry != null) {
            prometheusRegistry.close();
        }
    }

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(Fibonacci.class.getName(), new DeploymentOptions().setHa(true).setInstances(10))
                .onSuccess(id -> logger.info("Verticles deployed successfully: {}", id))
                .onFailure(err -> logger.error("Deployment failed: {}", err.getMessage()));
    }
}