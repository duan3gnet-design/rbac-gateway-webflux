package com.api.gateway.integration;

import com.api.gateway.route.DatabaseRouteDefinitionRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * Integration tests cho {@link DatabaseRouteDefinitionRepository}.
 *
 * <p>DB state được reset về seed trong {@code AbstractIntegrationTest.baseSetUp()}
 * trước mỗi test — không cần @DirtiesContext.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DatabaseRouteDefinitionRepository Integration Tests")
class DatabaseRouteDefinitionRepositoryTest extends AbstractIntegrationTest {

    private static final String SECRET = "bXlfc3VwZXJfc2VjcmV0X2tleV9mb3JfcmJhY19nYXRld2F5XzIwMjQ=";

    @Autowired
    private DatabaseRouteDefinitionRepository routeDefinitionRepository;

    @Autowired
    private RouteDefinitionRepository routeDefinitionRepositoryAsInterface;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private DatabaseClient db;

    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    }

    private String userJwt(String username, List<String> permissions) {
        return Jwts.builder()
                .subject(username)
                .claim("roles", List.of("ROLE_USER"))
                .claim("permissions", permissions)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 300_000))
                .signWith(secretKey())
                .compact();
    }

    private void insertRoute(String id, String uri, String predicates, String filters,
                             int routeOrder, boolean enabled) {
        db.sql("""
                INSERT INTO gateway_routes (id, uri, predicates, filters, route_order, enabled, created_at, updated_at)
                VALUES (:id, :uri, :predicates, :filters, :routeOrder, :enabled, :now, :now)
                ON CONFLICT DO NOTHING
                """)
                .bind("id", id).bind("uri", uri)
                .bind("predicates", predicates).bind("filters", filters)
                .bind("routeOrder", routeOrder).bind("enabled", enabled)
                .bind("now", OffsetDateTime.now())
                .fetch().rowsUpdated().block();
    }

    private void refreshRoutes() {
        webClient.post().uri("/actuator/gateway/refresh")
                .exchange().expectStatus().isOk();
    }

    /**
     * Invalidate route cache rồi publish RefreshRoutesEvent đồng bộ —
     * đảm bảo Gateway rebuild routing table trước khi test request tiếp theo.
     *
     * <p>Khác với {@link #refreshRoutes()} (POST actuator) vốn xử lý async và
     * gây race condition, method này:
     * <ol>
     *   <li>Invalidate cache của DatabaseRouteDefinitionRepository</li>
     *   <li>Eager-load routes từ DB vào cache (blocking)</li>
     *   <li>Publish RefreshRoutesEvent để Gateway rebuild routing table</li>
     *   <li>Sleep ngắn để Gateway hoàn tất rebuild trên Netty event loop</li>
     * </ol>
     * </p>
     */
    private void forceRefreshRoutes() {
        routeDefinitionRepository.invalidateCache();
        // Eager-load vào cache ngay — đảm bảo DB query xong trước khi event được xử lý
        routeDefinitionRepository.getRouteDefinitions().collectList().block();
        // Publish với source là test instance → onRefreshRoutes không filter → Gateway rebuild
        eventPublisher.publishEvent(new RefreshRoutesEvent(this));
        // Gateway rebuild routing table trên Netty event loop — chờ ngắn để hoàn tất
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. getRouteDefinitions()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. getRouteDefinitions()")
    class GetRouteDefinitions {

        @Test @Order(1)
        @DisplayName("Chỉ load routes có enabled=true, bỏ qua enabled=false")
        void getRouteDefinitions_shouldOnlyLoadEnabledRoutes() {
            insertRoute("disabled-route", "http://localhost:" + wireMock.port(),
                    "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/disabled/**\"}}]", "[]", 50, false);

            StepVerifier.create(routeDefinitionRepository.getRouteDefinitions()
                    .filter(rd -> "disabled-route".equals(rd.getId())))
                    .expectNextCount(0).verifyComplete();
        }

        @Test @Order(2)
        @DisplayName("Route enabled=true được load đúng id, uri, order")
        void getRouteDefinitions_enabledRoute_shouldHaveCorrectFields() {
            String uri = "http://localhost:" + wireMock.port();
            insertRoute("enabled-check", uri,
                    "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/enabled-check/**\"}}]", "[]", 77, true);

            StepVerifier.create(routeDefinitionRepository.getRouteDefinitions()
                    .filter(rd -> "enabled-check".equals(rd.getId())).next())
                    .assertNext(rd -> {
                        Assertions.assertEquals("enabled-check", rd.getId());
                        Assertions.assertEquals(uri, rd.getUri().toString());
                        Assertions.assertEquals(77, rd.getOrder());
                        Assertions.assertFalse(rd.getPredicates().isEmpty());
                    }).verifyComplete();
        }

        @Test @Order(3)
        @DisplayName("Seeded routes được load theo đúng route_order asc")
        void getRouteDefinitions_shouldReturnRoutesOrderedByRouteOrder() {
            List<Integer> orders = routeDefinitionRepository.getRouteDefinitions()
                    .map(RouteDefinition::getOrder).collectList().block();

            Assertions.assertNotNull(orders);
            Assertions.assertFalse(orders.isEmpty());
            for (int i = 0; i < orders.size() - 1; i++) {
                Assertions.assertTrue(orders.get(i) <= orders.get(i + 1));
            }
        }

        @Test @Order(4)
        @DisplayName("Object JSON format → parse đúng PredicateDefinition và FilterDefinition")
        void getRouteDefinitions_objectJsonFormat_shouldParseCorrectly() {
            insertRoute("object-format", "http://localhost:" + wireMock.port(),
                    "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/object/**\"}}]",
                    "[{\"name\":\"AddRequestHeader\",\"args\":{\"name\":\"X-Test\",\"value\":\"hello\"}}]",
                    88, true);

            StepVerifier.create(routeDefinitionRepository.getRouteDefinitions()
                    .filter(rd -> "object-format".equals(rd.getId())).next())
                    .assertNext(rd -> {
                        Assertions.assertEquals("Path", rd.getPredicates().get(0).getName());
                        Assertions.assertEquals("AddRequestHeader", rd.getFilters().get(0).getName());
                    }).verifyComplete();
        }

        @Test @Order(5)
        @DisplayName("Shortcut string predicate [\"/api/**\"] → parse không rỗng")
        void getRouteDefinitions_shortcutStringPredicates_shouldParse() {
            insertRoute("shortcut-pred", "http://localhost:" + wireMock.port(),
                    "[\"/api/shortcut/**\"]", "[]", 89, true);

            StepVerifier.create(routeDefinitionRepository.getRouteDefinitions()
                    .filter(rd -> "shortcut-pred".equals(rd.getId())).next())
                    .assertNext(rd -> Assertions.assertFalse(rd.getPredicates().isEmpty()))
                    .verifyComplete();
        }

        @Test @Order(6)
        @DisplayName("Shortcut string filter [\"AddRequestHeader=X-Foo, bar\"] → parse đúng tên")
        void getRouteDefinitions_shortcutStringFilters_shouldParse() {
            insertRoute("shortcut-filter", "http://localhost:" + wireMock.port(),
                    "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/sf/**\"}}]",
                    "[\"AddRequestHeader=X-Foo, bar\"]", 90, true);

            StepVerifier.create(routeDefinitionRepository.getRouteDefinitions()
                    .filter(rd -> "shortcut-filter".equals(rd.getId())).next())
                    .assertNext(rd -> {
                        Assertions.assertFalse(rd.getFilters().isEmpty());
                        Assertions.assertEquals("AddRequestHeader", rd.getFilters().get(0).getName());
                    }).verifyComplete();
        }

        @Test @Order(7)
        @DisplayName("predicates='[]' → List.of()")
        void getRouteDefinitions_emptyPredicates_shouldReturnEmptyList() {
            insertRoute("empty-pred", "http://localhost:" + wireMock.port(),
                    "[]", "[]", 91, true);

            StepVerifier.create(routeDefinitionRepository.getRouteDefinitions()
                    .filter(rd -> "empty-pred".equals(rd.getId())).next())
                    .assertNext(rd -> {
                        Assertions.assertTrue(rd.getPredicates().isEmpty());
                        Assertions.assertTrue(rd.getFilters().isEmpty());
                    }).verifyComplete();
        }

        @Test @Order(8)
        @DisplayName("predicates blank/whitespace → List.of() (không throw)")
        void getRouteDefinitions_blankPredicates_shouldReturnEmptyList() {
            db.sql("""
                    INSERT INTO gateway_routes (id, uri, predicates, filters, route_order, enabled, created_at, updated_at)
                    VALUES ('null-pred', :uri, '   ', '   ', 92, true, :now, :now) ON CONFLICT DO NOTHING
                    """)
                    .bind("uri", "http://localhost:" + wireMock.port())
                    .bind("now", OffsetDateTime.now())
                    .fetch().rowsUpdated().block();

            StepVerifier.create(routeDefinitionRepository.getRouteDefinitions()
                    .filter(rd -> "null-pred".equals(rd.getId())).next())
                    .assertNext(rd -> {
                        Assertions.assertTrue(rd.getPredicates().isEmpty());
                        Assertions.assertTrue(rd.getFilters().isEmpty());
                    }).verifyComplete();
        }

        @Test @Order(9)
        @DisplayName("Route có JSON không hợp lệ → bị skip, route tiếp theo vẫn load")
        void getRouteDefinitions_invalidJson_shouldSkipAndContinue() {
            String uri = "http://localhost:" + wireMock.port();
            insertRoute("invalid-json-route", uri, "THIS IS NOT JSON {{{{", "[]", 93, true);
            insertRoute("valid-after-invalid", uri,
                    "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/valid-after/**\"}}]", "[]", 94, true);

            List<String> ids = routeDefinitionRepository.getRouteDefinitions()
                    .map(RouteDefinition::getId).collectList().block();

            Assertions.assertNotNull(ids);
            Assertions.assertFalse(ids.contains("invalid-json-route"), "Route lỗi phải bị skip");
            Assertions.assertTrue(ids.contains("valid-after-invalid"), "Route hợp lệ phải được load");
        }

        @Test @Order(10)
        @DisplayName("Flux không rỗng — có ít nhất 6 seeded routes")
        void getRouteDefinitions_shouldReturnNonEmptyFlux() {
            StepVerifier.create(routeDefinitionRepository.getRouteDefinitions().count())
                    .assertNext(count -> Assertions.assertTrue(count >= 6))
                    .verifyComplete();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. save() — UnsupportedOperationException
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. save() — không hỗ trợ")
    class SaveOperation {

        @Test @Order(20)
        @DisplayName("save() luôn ném UnsupportedOperationException với message rõ ràng")
        @Disabled
        void save_shouldThrowUnsupportedOperationException() {
            RouteDefinition dummy = new RouteDefinition();
            dummy.setId("dummy");

            StepVerifier.create(routeDefinitionRepository.save(Mono.just(dummy)))
                    .expectErrorSatisfies(ex -> {
                        Assertions.assertInstanceOf(UnsupportedOperationException.class, ex);
                        Assertions.assertTrue(ex.getMessage().contains("Dynamic route save not supported"));
                    }).verify();
        }

        @Test @Order(21)
        @DisplayName("save() qua interface cũng ném UnsupportedOperationException")
        @Disabled
        void save_viaInterface_shouldThrowUnsupportedOperationException() {
            RouteDefinition dummy = new RouteDefinition();
            dummy.setId("via-interface");

            StepVerifier.create(routeDefinitionRepositoryAsInterface.save(Mono.just(dummy)))
                    .expectError(UnsupportedOperationException.class).verify();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. delete()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. delete()")
    class DeleteOperation {

        @Test @Order(30)
        @DisplayName("delete() route tồn tại → Mono<Void> complete, không còn trong getRouteDefinitions()")
        void delete_existingRoute_shouldCompleteSuccessfully() {
            insertRoute("to-delete-repo", "http://localhost:" + wireMock.port(),
                    "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/del/**\"}}]", "[]", 95, true);

            StepVerifier.create(routeDefinitionRepository.delete(Mono.just("to-delete-repo")))
                    .verifyComplete();

            StepVerifier.create(routeDefinitionRepository.getRouteDefinitions()
                    .filter(rd -> "to-delete-repo".equals(rd.getId())))
                    .expectNextCount(0).verifyComplete();
        }

        @Test @Order(31)
        @DisplayName("delete() route không tồn tại → IllegalArgumentException chứa route id")
        void delete_nonExistentRoute_shouldThrowIllegalArgumentException() {
            StepVerifier.create(routeDefinitionRepository.delete(Mono.just("ghost-route-xyz")))
                    .expectErrorSatisfies(ex -> {
                        Assertions.assertInstanceOf(IllegalArgumentException.class, ex);
                        Assertions.assertTrue(ex.getMessage().contains("ghost-route-xyz"));
                    }).verify();
        }

        @Test @Order(32)
        @DisplayName("delete() seeded route → biến mất khỏi getRouteDefinitions()")
        void delete_seededRoute_shouldRemoveFromDefinitions() {
            StepVerifier.create(routeDefinitionRepository.delete(Mono.just("auth-login")))
                    .verifyComplete();

            StepVerifier.create(routeDefinitionRepository.getRouteDefinitions()
                    .filter(rd -> "auth-login".equals(rd.getId())))
                    .expectNextCount(0).verifyComplete();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. Gateway routing behavior
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. Gateway routing behavior")
    class GatewayRoutingBehavior {

        @Test @Order(40)
        @DisplayName("Route disabled → gateway trả 404 (không forward)")
        void disabledRoute_shouldNotBeForwarded() {
            insertRoute("behav-disabled", "http://localhost:" + wireMock.port(),
                    "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/behav-disabled/**\"}}]", "[]", 96, false);
            refreshRoutes();

            wireMock.stubFor(get(urlPathMatching("/api/behav-disabled/.*"))
                    .willReturn(aResponse().withStatus(200)));

            webClient.get().uri("/api/behav-disabled/test")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt("u@t.com", List.of("products:READ")))
                    .exchange().expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test @Order(41)
        @DisplayName("Route insert động → sau refresh được gateway forward (không phải 404)")
        void newEnabledRoute_afterRefresh_shouldBeForwarded() {
            wireMock.stubFor(get(urlPathMatching("/api/dynamic/.*"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"ok\":true}")));

            insertRoute("dynamic-route", "http://localhost:" + wireMock.port(),
                    "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/dynamic/**\"}}]", "[]", 97, true);

            // forceRefreshRoutes(): invalidate cache + eager-load DB + publish event đồng bộ
            // đảm bảo Gateway rebuild routing table trước khi request bên dưới được gửi đi.
            forceRefreshRoutes();

            webClient.get().uri("/api/dynamic/hello")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt("u@t.com", List.of("products:READ")))
                    .exchange()
                    .expectStatus().value(status ->
                            Assertions.assertNotEquals(404, status, "Phải được routing, không phải 404"));
        }

        @Test @Order(42)
        @DisplayName("GET /actuator/gateway/routes → list routes đang active")
        void actuatorGatewayRoutes_shouldReturnCurrentRoutes() {
            webClient.get().uri("/actuator/gateway/routes")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.APPLICATION_JSON)
                    .expectBody()
                    .jsonPath("$").isArray()
                    .jsonPath("$.length()").value(len ->
                            Assertions.assertTrue((Integer) len >= 6));
        }

        @Test @Order(43)
        @DisplayName("GET /actuator/gateway/routes — mỗi entry có route_id và uri")
        void actuatorGatewayRoutes_eachRouteShouldHaveRequiredFields() {
            webClient.get().uri("/actuator/gateway/routes")
                    .exchange().expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$[0].route_id").exists()
                    .jsonPath("$[0].uri").exists();
        }
    }
}
