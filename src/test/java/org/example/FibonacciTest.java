package org.example;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class FibonacciTest {

    private WebClient webClient;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext) {
        webClient = WebClient.create(vertx, new WebClientOptions().setDefaultPort(8080));

        Fibonacci fibonacci = new Fibonacci();
        vertx.deployVerticle(fibonacci)
                .onSuccess(id -> testContext.completeNow())
                .onFailure(testContext::failNow);
    }

    @AfterEach
    void tearDown(VertxTestContext testContext) {
        if (webClient != null) {
            webClient.close();
        }
        testContext.completeNow();
    }

    @Test
    void testGetFibonacciValidNumber(VertxTestContext testContext) {
        webClient.get("/fibonacci")
                .addQueryParam("number", "5")
                .send()
                .onComplete(testContext.succeeding(response -> {
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body);
                    assertTrue(body.containsKey("input"));
                    assertTrue(body.containsKey("result"));
                    assertEquals("5", body.getString("input"));
                    assertNotNull(body.getString("result"));
                    testContext.completeNow();
                }));
    }

    @Test
    void testGetFibonacciComplexNumber(VertxTestContext testContext) {
        webClient.get("/fibonacci")
                .addQueryParam("number", "3.45 8")
                .send()
                .onComplete(testContext.succeeding(response -> {
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body);
                    assertEquals("3.45 8", body.getString("input"));
                    assertTrue(body.getString("result").contains("-1.7869237112563163"));
                    assertTrue(body.getString("result").contains("-1.5300243399685793"));
                    assertTrue(body.getString("result").contains("i"));
                    testContext.completeNow();
                }));
    }

    @Test
    void testPostFibonacciValidNumber(VertxTestContext testContext) {
        JsonObject requestBody = new JsonObject().put("number", "5");

        webClient.post("/fibonacci")
                .sendJson(requestBody)
                .onComplete(testContext.succeeding(response -> {
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body);
                    assertTrue(body.containsKey("input"));
                    assertTrue(body.containsKey("result"));
                    assertEquals("5", body.getString("input"));
                    assertNotNull(body.getString("result"));
                    testContext.completeNow();
                }));
    }

    @Test
    void testPostFibonacciComplexNumber(VertxTestContext testContext) {
        JsonObject requestBody = new JsonObject().put("number", "3.45 8");

        webClient.post("/fibonacci")
                .sendJson(requestBody)
                .onComplete(testContext.succeeding(response -> {
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body);
                    assertEquals("3.45 8", body.getString("input"));
                    assertTrue(body.getString("result").contains("-1.7869237112563163"));
                    assertTrue(body.getString("result").contains("-1.5300243399685793"));
                    assertTrue(body.getString("result").contains("i"));
                    testContext.completeNow();
                }));
    }

    @Test
    void testGetFibonacciWithComma(VertxTestContext testContext) {
        webClient.get("/fibonacci")
                .addQueryParam("number", "3,45 8")
                .send()
                .onComplete(testContext.succeeding(response -> {
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body);
                    assertEquals("3,45 8", body.getString("input"));
                    assertTrue(body.getString("result").contains("-1.7869237112563163"));
                    assertTrue(body.getString("result").contains("-1.5300243399685793"));
                    assertTrue(body.getString("result").contains("i"));
                    testContext.completeNow();
                }));
    }

    @Test
    void testPostFibonacciWithComma(VertxTestContext testContext) {
        JsonObject requestBody = new JsonObject().put("number", "3,45 8");

        webClient.post("/fibonacci")
                .sendJson(requestBody)
                .onComplete(testContext.succeeding(response -> {
                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body);
                    assertEquals("3,45 8", body.getString("input"));
                    assertTrue(body.getString("result").contains("-1.7869237112563163"));
                    assertTrue(body.getString("result").contains("-1.5300243399685793"));
                    assertTrue(body.getString("result").contains("i"));
                    testContext.completeNow();
                }));
    }

    @Test
    void testMultipleRequests(VertxTestContext testContext) throws InterruptedException {
        VertxTestContext multiTestContext = new VertxTestContext();
        int requestsCount = 10;
        int[] completed = {0};

        for (int i = 0; i < requestsCount; i++) {
            webClient.get("/fibonacci")
                    .addQueryParam("number", String.valueOf(i))
                    .send()
                    .onComplete(result -> {
                        if (result.succeeded()) {
                            JsonObject body = result.result().bodyAsJsonObject();
                            if (body != null && body.containsKey("result")) {
                                completed[0]++;
                                if (completed[0] == requestsCount) {
                                    multiTestContext.completeNow();
                                }
                            }
                        }
                    });
        }

        assertTrue(multiTestContext.awaitCompletion(10, TimeUnit.SECONDS));
        testContext.completeNow();
    }
}