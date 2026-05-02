package com.api.gateway.route;

import com.api.gateway.entity.GatewayRouteEntity;
import com.api.gateway.repository.GatewayRouteR2dbcRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Load routes từ PostgreSQL và cache in-memory.
 *
 * <p><b>save():</b> Gateway gọi method này trong refresh cycle —
 * delegate về DB thực sự thay vì ném exception để tránh làm gián đoạn request.</p>
 *
 * <p><b>Cache:</b> Invalidate khi nhận {@link RefreshRoutesEvent} từ AdminRouteService.</p>
 *
 * <p><b>invalidateCache():</b> Public method cho phép test (và @BeforeEach) force
 * invalidate cache sau khi reset DB trực tiếp — đảm bảo Gateway luôn load
 * routes fresh từ DB thay vì dùng cache stale.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseRouteDefinitionRepository implements RouteDefinitionRepository {

    private final GatewayRouteR2dbcRepository routeRepository;
    private final ObjectMapper objectMapper;

    private static final TypeReference<List<PredicateDefinition>> PREDICATE_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<FilterDefinition>>    FILTER_TYPE    = new TypeReference<>() {};

    // null = cache chưa có / đã invalidate, non-null = đang dùng cache
    private final AtomicReference<List<RouteDefinition>> routeCache = new AtomicReference<>(null);

    // ─── RouteDefinitionRepository ───────────────────────────────────────────

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        List<RouteDefinition> cached = routeCache.get();
        if (cached != null) {
            return Flux.fromIterable(cached);
        }

        return loadFromDb()
                .collectList()
                .doOnSuccess(routes -> {
                    routeCache.set(routes);
                    log.info("Gateway routes cached: {} routes loaded from database", routes.size());
                })
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * Spring Cloud Gateway gọi save() trong refresh/startup cycle.
     * Upsert vào DB để không làm gián đoạn request pipeline.
     * Không publish RefreshRoutesEvent ở đây để tránh loop vô tận.
     */
    @Override
    public Mono<Void> save(Mono<RouteDefinition> route) {
        return route.flatMap(def ->
                routeRepository.existsById(def.getId())
                        .flatMap(exists -> {
                            if (exists) {
                                log.debug("Route [{}] already exists in DB — skipping save", def.getId());
                                return Mono.empty();
                            }
                            GatewayRouteEntity entity = toEntity(def);
                            return routeRepository.save(entity)
                                    .doOnSuccess(e -> log.debug("Route [{}] saved to DB via gateway lifecycle", e.id()))
                                    .then();
                        })
        );
    }

    /**
     * Gateway gọi delete() khi remove route qua API.
     * AdminRouteService.deleteRoute() cũng delete trực tiếp qua repo — method này là fallback.
     */
    @Override
    public @NonNull Mono<Void> delete(Mono<String> routeId) {
        return routeId.flatMap(id ->
                routeRepository.findById(id)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                "Route not found: " + id)))
                        .flatMap(routeRepository::delete)
                        .doOnSuccess(v -> log.debug("Route [{}] deleted via gateway lifecycle", id))
        );
    }

    // ─── Cache management ────────────────────────────────────────────────────

    @EventListener(RefreshRoutesEvent.class)
    public void onRefreshRoutes(RefreshRoutesEvent event) {
        if (!(event.getSource() instanceof DatabaseRouteDefinitionRepository)) {
            invalidateCache();
        }
    }

    /**
     * Force invalidate route cache — load lại từ DB trong request tiếp theo.
     *
     * <p>Được gọi bởi {@code @BeforeEach} trong integration test sau khi reset DB
     * trực tiếp bằng SQL (không đi qua AdminRouteService nên không có
     * RefreshRoutesEvent). Nếu không gọi method này, Gateway sẽ tiếp tục dùng
     * cache stale và trả 404 cho routes đã bị xóa hoặc không thấy routes mới.</p>
     */
    public void invalidateCache() {
        routeCache.set(null);
        log.info("Route cache invalidated — will reload from database on next request");
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Flux<RouteDefinition> loadFromDb() {
        return routeRepository.findByEnabledTrueOrderByRouteOrderAsc()
                .flatMap(entity -> Mono.fromCallable(() -> toRouteDefinition(entity))
                        .doOnError(e -> log.error("Failed to parse route [{}]: {}", entity.id(), e.getMessage()))
                        .onErrorResume(e -> Mono.empty()))
                .doOnSubscribe(s -> log.debug("Loading gateway routes from database..."));
    }

    private RouteDefinition toRouteDefinition(GatewayRouteEntity entity) throws Exception {
        RouteDefinition def = new RouteDefinition();
        def.setId(entity.id());
        def.setUri(URI.create(entity.uri()));
        def.setOrder(entity.routeOrder());
        def.setPredicates(parsePredicates(entity.predicates()));
        def.setFilters(parseFilters(entity.filters()));
        return def;
    }

    private GatewayRouteEntity toEntity(RouteDefinition def) {
        String predicatesJson = serializeSilently(def.getPredicates());
        String filtersJson    = serializeSilently(def.getFilters());
        return new GatewayRouteEntity(
                def.getId(),
                def.getUri() != null ? def.getUri().toString() : null,
                predicatesJson,
                filtersJson,
                def.getOrder(),
                true,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private List<PredicateDefinition> parsePredicates(String json) throws Exception {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return List.of();
        var node = objectMapper.readTree(json);
        if (node.isArray() && !node.isEmpty() && node.get(0).isTextual()) {
            return objectMapper.convertValue(node, new TypeReference<List<String>>() {})
                    .stream()
                    .map(s -> new PredicateDefinition(toNamedShortcut(s)))
                    .toList();
        }
        return objectMapper.readValue(json, PREDICATE_TYPE);
    }

    /**
     * Chuyển shortcut path string (vd: "/api/**") thành dạng có tên predicate
     * mà {@link PredicateDefinition} có thể parse được (vd: "Path=/api/**").
     *
     * <p>Nếu string đã có dạng "Name=..." (ví dụ "Host=example.com") thì giữ nguyên.</p>
     */
    private String toNamedShortcut(String s) {
        if (s.contains("=")) return s;   // đã có tên, ví dụ "Host=example.com"
        return "Path=" + s;              // thuần path, ví dụ "/api/**" → "Path=/api/**"
    }

    private List<FilterDefinition> parseFilters(String json) throws Exception {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return List.of();
        var node = objectMapper.readTree(json);
        if (node.isArray() && !node.isEmpty() && node.get(0).isTextual()) {
            return objectMapper.convertValue(node, new TypeReference<List<String>>() {})
                    .stream().map(FilterDefinition::new).toList();
        }
        return objectMapper.readValue(json, FILTER_TYPE);
    }

    private String serializeSilently(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize route definition field: {}", e.getMessage());
            return "[]";
        }
    }
}
