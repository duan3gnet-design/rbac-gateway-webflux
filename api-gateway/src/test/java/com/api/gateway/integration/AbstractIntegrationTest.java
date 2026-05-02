package com.api.gateway.integration;

import com.api.gateway.config.PostgreSQLContainerConfig;
import com.api.gateway.route.DatabaseRouteDefinitionRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * Base class cho tất cả integration test.
 *
 * <h3>Chiến lược performance — một Spring context duy nhất cho toàn suite</h3>
 *
 * <p>Không dùng {@code @DirtiesContext} ở bất kỳ subclass nào. Thay vào đó,
 * {@code cleanDynamicDbState()} chạy trong {@code @BeforeEach} để reset DB/Redis
 * về trạng thái seed trước mỗi test.</p>
 *
 * <h3>Tại sao không dùng @DirtiesContext</h3>
 * <p>Mỗi lần {@code @DirtiesContext} trigger, Spring rebuild toàn bộ
 * ApplicationContext (reconnect R2DBC, Redis, reload routes). Với ~50 test
 * method, chi phí này tích lũy thành hàng chục phút. SQL DELETE/UPDATE
 * nhanh hơn hàng trăm lần.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgreSQLContainerConfig.class)
abstract class AbstractIntegrationTest {

    protected static final com.github.tomakehurst.wiremock.WireMockServer wireMock =
            PostgreSQLContainerConfig.WIRE_MOCK;

    @LocalServerPort
    protected int gatewayPort;

    protected WebTestClient webClient;

    @Autowired
    protected CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private DatabaseClient db;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Autowired
    private DatabaseRouteDefinitionRepository routeDefinitionRepository;

    @AfterAll
    static void stopWireMock() {
        // WireMock dùng chung toàn suite, JVM shutdown hook sẽ dọn
    }

    @BeforeEach
    void baseSetUp() {
        wireMock.resetAll();

        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(CircuitBreaker::reset);

        redisTemplate.keys("rate_limit:*")
                .flatMap(redisTemplate::delete)
                .blockLast(Duration.ofSeconds(5));

        cleanDynamicDbState();

        // Invalidate route cache sau khi reset DB trực tiếp bằng SQL.
        // cleanDynamicDbState() xóa/reset routes qua SQL — không đi qua AdminRouteService
        // nên không có RefreshRoutesEvent → cache KHÔNG tự động bị clear.
        // Nếu bỏ dòng này, Gateway dùng cache stale: routes đã xóa vẫn active,
        // routes mới được tạo trong test trước không bị dọn → test fail với 404/200 sai.
        routeDefinitionRepository.invalidateCache();

        webClient = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Reset DB về trạng thái seed trước mỗi test.
     *
     * <p>Thứ tự xóa theo FK constraint:
     * route_permissions → gateway_routes → rate_limit_config</p>
     *
     * <p><b>Lưu ý quan trọng:</b> SQL UPDATE reset seeded routes chỉ dùng một
     * parameter {@code :uri} duy nhất (tất cả seeded routes đều trỏ về cùng
     * WireMock URI). Không dùng subquery VALUES inline vì R2DBC {@code DatabaseClient}
     * không bind named parameter bên trong {@code VALUES(...)} subquery.</p>
     */
    private void cleanDynamicDbState() {
        // 1. Xóa route_permissions của dynamic routes (cascade FK)
        db.sql("""
                DELETE FROM route_permissions
                WHERE route_id NOT IN (
                    'auth-login','auth-register','auth-refresh',
                    'auth-logout','auth-logout-all','resource-service'
                )
                """)
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(5));

        // 2. Xóa dynamic gateway_routes
        db.sql("""
                DELETE FROM gateway_routes
                WHERE id NOT IN (
                    'auth-login','auth-register','auth-refresh',
                    'auth-logout','auth-logout-all','resource-service'
                )
                """)
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(5));

        // 3. Reset seeded routes về trạng thái gốc.
        //    Tất cả seeded routes đều trỏ về cùng WireMock URI nên dùng 1 UPDATE đơn.
        String wireMockUri = "http://localhost:" + PostgreSQLContainerConfig.WIRE_MOCK.port();
        db.sql("""
                UPDATE gateway_routes
                SET uri        = :uri,
                    enabled    = TRUE,
                    updated_at = now()
                WHERE id IN (
                    'auth-login','auth-register','auth-refresh',
                    'auth-logout','auth-logout-all','resource-service'
                )
                """)
                .bind("uri", wireMockUri)
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(5));

        // 4. Xóa per-user rate limit overrides
        db.sql("DELETE FROM rate_limit_config WHERE username IS NOT NULL")
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(5));

        // 5. Reset global default về giá trị ban đầu
        db.sql("""
                UPDATE rate_limit_config
                SET replenish_rate = 5,
                    burst_capacity = 5,
                    enabled        = TRUE,
                    description    = 'Test global default',
                    updated_at     = now()
                WHERE username IS NULL
                """)
                .fetch().rowsUpdated()
                .block(Duration.ofSeconds(5));
    }
}
