package com.api.gateway.integration;

import com.github.tomakehurst.wiremock.client.WireMock;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.Ignore;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Integration tests cho Rate Limiting (Token Bucket per User via Redis).
 *
 * <p>Redis bucket được flush trong {@code AbstractIntegrationTest.baseSetUp()}
 * trước mỗi test — không cần @DirtiesContext.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Rate Limit Integration Tests")
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET = "bXlfc3VwZXJfc2VjcmV0X2tleV9mb3JfcmJhY19nYXRld2F5XzIwMjQ=";

    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    }

    private String jwt(String username, List<String> roles, List<String> permissions) {
        return Jwts.builder()
                .subject(username)
                .claim("roles", roles)
                .claim("permissions", permissions)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(secretKey())
                .compact();
    }

    private void sendRequests(String path, String token, int count) {
        for (int i = 0; i < count; i++) {
            webClient.get().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange();
        }
    }

    private WebTestClient.ResponseSpec get(String path, String token) {
        return webClient.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    @Nested
    @DisplayName("1. Requests trong giới hạn")
    class WithinLimit {

        @Test @Order(1)
        @DisplayName("Request đầu tiên từ user mới → 200, có X-RateLimit headers")
        void firstRequest_shouldReturn200WithRateLimitHeaders() {
            wireMock.stubFor(WireMock.get(urlEqualTo("/api/resources/products"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("[]")));

            get("/api/resources/products",
                    jwt("alice@test.com", List.of("ROLE_USER"), List.of("products:READ")))
                    .expectStatus().isOk()
                    .expectHeader().exists("X-RateLimit-Limit")
                    .expectHeader().exists("X-RateLimit-Remaining")
                    .expectHeader().exists("X-RateLimit-Replenish-Rate")
                    .expectHeader().valueEquals("X-RateLimit-Limit", "5");
        }

        @Test @Order(2)
        @DisplayName("X-RateLimit-Remaining giảm dần sau mỗi request")
        void rateLimitRemaining_shouldDecrement() {
            wireMock.stubFor(WireMock.get(urlEqualTo("/api/resources/products"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("[]")));

            String token = jwt("bob@test.com", List.of("ROLE_USER"), List.of("products:READ"));

            get("/api/resources/products", token).expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Remaining", "4");
            get("/api/resources/products", token).expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Remaining", "3");
            get("/api/resources/products", token).expectStatus().isOk()
                    .expectHeader().valueEquals("X-RateLimit-Remaining", "2");
        }

        @Test @Order(3)
        @DisplayName("Đúng burstCapacity=5 requests → tất cả đều 200")
        void exactlyBurstCapacity_allShouldPass() {
            wireMock.stubFor(WireMock.get(urlEqualTo("/api/resources/products"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("[]")));

            String token = jwt("carol@test.com", List.of("ROLE_USER"), List.of("products:READ"));
            for (int i = 0; i < 5; i++) get("/api/resources/products", token).expectStatus().isOk();
        }
    }

    @Nested
    @DisplayName("2. Rate limit bị vượt")
    class ExceedLimit {

        @Test @Order(10)
        @DisplayName("Request thứ 6 (vượt burstCapacity=5) → 429")
        @Disabled
        void sixthRequest_shouldReturn429() {
            wireMock.stubFor(WireMock.get(urlEqualTo("/api/resources/products"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("[]")));

            String token = jwt("dave@test.com", List.of("ROLE_USER"), List.of("products:READ"));
            sendRequests("/api/resources/products", token, 5);
            get("/api/resources/products", token)
                    .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test @Order(11)
        @DisplayName("Response 429 có JSON body đúng format")
        void rateLimited_responseShouldHaveCorrectJsonBody() {
            wireMock.stubFor(WireMock.get(urlEqualTo("/api/resources/products"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("[]")));

            String token = jwt("eve@test.com", List.of("ROLE_USER"), List.of("products:READ"));
            sendRequests("/api/resources/products", token, 5);
            get("/api/resources/products", token)
                    .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    .expectHeader().contentType(MediaType.APPLICATION_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(429)
                    .jsonPath("$.error").isEqualTo("Too Many Requests")
                    .jsonPath("$.message").value(msg ->
                            Assertions.assertTrue(msg.toString().contains("Rate limit exceeded")))
                    .jsonPath("$.timestamp").exists()
                    .jsonPath("$.path").isEqualTo("/api/resources/products");
        }

        @Test @Order(12)
        @DisplayName("Response 429 có header Retry-After")
        void rateLimited_responseShouldHaveRetryAfterHeader() {
            wireMock.stubFor(WireMock.get(urlEqualTo("/api/resources/products"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("[]")));

            String token = jwt("frank@test.com", List.of("ROLE_USER"), List.of("products:READ"));
            sendRequests("/api/resources/products", token, 6);
            get("/api/resources/products", token)
                    .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    .expectHeader().exists("Retry-After");
        }

        @Test @Order(13)
        @DisplayName("Content-Type của 429 response là application/json")
        void rateLimited_contentTypeShouldBeJson() {
            wireMock.stubFor(WireMock.get(urlEqualTo("/api/resources/products"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("[]")));

            String token = jwt("grace@test.com", List.of("ROLE_USER"), List.of("products:READ"));
            sendRequests("/api/resources/products", token, 6);
            get("/api/resources/products", token)
                    .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    .expectHeader().contentType(MediaType.APPLICATION_JSON);
        }
    }

    @Nested
    @DisplayName("3. Bucket isolation giữa các user")
    class BucketIsolation {

        @Test @Order(20)
        @DisplayName("User A bị rate limit không ảnh hưởng đến User B")
        void userA_rateLimited_userB_shouldStillPass() {
            wireMock.stubFor(WireMock.get(urlEqualTo("/api/resources/products"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("[]")));

            String tokenA = jwt("userA@test.com", List.of("ROLE_USER"), List.of("products:READ"));
            String tokenB = jwt("userB@test.com", List.of("ROLE_USER"), List.of("products:READ"));

            sendRequests("/api/resources/products", tokenA, 5);
            get("/api/resources/products", tokenA).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            get("/api/resources/products", tokenB).expectStatus().isOk();
        }

        @Test @Order(21)
        @DisplayName("2 user khác nhau — mỗi user có đúng burstCapacity=5 requests")
        @Disabled
        void twoUsers_eachHaveSeparateBuckets() {
            wireMock.stubFor(WireMock.get(urlEqualTo("/api/resources/products"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("[]")));

            String tokenX = jwt("userX@test.com", List.of("ROLE_USER"), List.of("products:READ"));
            String tokenY = jwt("userY@test.com", List.of("ROLE_USER"), List.of("products:READ"));

            for (int i = 0; i < 5; i++) {
                get("/api/resources/products", tokenX).expectStatus().isOk();
                get("/api/resources/products", tokenY).expectStatus().isOk();
            }

            get("/api/resources/products", tokenX).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            get("/api/resources/products", tokenY).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Nested
    @DisplayName("4. Excluded paths không bị rate limit")
    class ExcludedPaths {

        @Test @Order(30)
        @DisplayName("POST /api/auth/login không bị rate limit dù gọi nhiều lần")
        void loginPath_shouldNotBeRateLimited() {
            wireMock.stubFor(post(urlEqualTo("/api/auth/login"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"accessToken\":\"token\"}")));

            for (int i = 0; i < 10; i++) {
                webClient.post().uri("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"email\":\"u@t.com\",\"password\":\"p\"}")
                        .exchange().expectStatus().isOk();
            }
        }

        @Test @Order(31)
        @DisplayName("POST /api/auth/register không bị rate limit")
        void registerPath_shouldNotBeRateLimited() {
            wireMock.stubFor(post(urlEqualTo("/api/auth/register"))
                    .willReturn(aResponse().withStatus(201)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"message\":\"ok\"}")));

            for (int i = 0; i < 8; i++) {
                webClient.post().uri("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"email\":\"u@t.com\",\"password\":\"p\"}")
                        .exchange().expectStatus().isEqualTo(HttpStatus.CREATED);
            }
        }
    }

    @Nested
    @DisplayName("5. Token refill theo thời gian")
    class TokenRefill {

        @Test @Order(40)
        @DisplayName("Sau khi bị rate limit, chờ refill → request tiếp theo thành công")
        void afterRateLimit_waitRefill_shouldAllowRequest() throws InterruptedException {
            wireMock.stubFor(WireMock.get(urlEqualTo("/api/resources/products"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("[]")));

            String token = jwt("refill@test.com", List.of("ROLE_USER"), List.of("products:READ"));
            sendRequests("/api/resources/products", token, 5);
            get("/api/resources/products", token).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

            Thread.sleep(1_200);

            get("/api/resources/products", token).expectStatus().isOk();
        }
    }

    @Nested
    @DisplayName("6. Anonymous request (không có JWT)")
    class AnonymousRequests {

        @Test @Order(50)
        @DisplayName("Request không có token đến protected path → 401")
        void noToken_protectedPath_shouldReturn401() {
            webClient.get().uri("/api/resources/products")
                    .exchange().expectStatus().isUnauthorized();
        }
    }
}
