package com.api.gateway.admin.route;

import com.api.gateway.entity.GatewayRouteEntity;
import com.api.gateway.repository.GatewayRouteR2dbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Business logic cho Admin Route Management.
 *
 * <p>Sau mỗi thao tác write, publish {@link RefreshRoutesEvent} để Gateway
 * tự động reload route definitions mà không cần gọi actuator thủ công.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRouteService {

    private final GatewayRouteR2dbcRepository routeRepo;
    private final RoutePermissionRepository   permissionRepo;
    private final PermissionQueryRepository   permQueryRepo;
    private final ApplicationEventPublisher   eventPublisher;

    // ─── Queries ────────────────────────────────────────────────────────────

    public Flux<AdminDtos.RouteResponse> getAllRoutes() {
        return routeRepo.findAll()
                .flatMap(this::toResponse);
    }

    public Mono<AdminDtos.RouteResponse> getRouteById(String id) {
        return routeRepo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found: " + id)))
                .flatMap(this::toResponse);
    }

    public Flux<AdminDtos.PermissionResponse> getAllPermissions() {
        return permQueryRepo.findAll();
    }

    public Flux<Long> getPermissionIdsByRoute(String routeId) {
        return permissionRepo.findPermissionIdsByRouteId(routeId);
    }

    // ─── Commands ───────────────────────────────────────────────────────────

    public Mono<AdminDtos.RouteResponse> createRoute(AdminDtos.RouteRequest req) {
        return routeRepo.existsById(req.id())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.CONFLICT, "Route already exists: " + req.id()));
                    }
                    // isNew = true → Spring Data R2DBC dùng INSERT thay vì UPDATE.
                    // Với String ID do người dùng cung cấp, Spring Data không thể tự
                    // phân biệt INSERT vs UPDATE nên cần Persistable.isNew() = true.
                    GatewayRouteEntity entity = new GatewayRouteEntity(
                            req.id(), req.uri(),
                            req.predicates(), req.filters(),
                            req.routeOrder(), req.enabled(),
                            OffsetDateTime.now(), OffsetDateTime.now(), true
                    );
                    return routeRepo.save(entity);
                })
                .flatMap(this::toResponse)
                .doOnSuccess(r -> publishRefresh("create", r.id()));
    }

    public Mono<AdminDtos.RouteResponse> updateRoute(String id, AdminDtos.RouteRequest req) {
        return routeRepo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found: " + id)))
                .flatMap(existing -> {
                    // isNew = false (default) → UPDATE
                    GatewayRouteEntity updated = new GatewayRouteEntity(
                            existing.id(),
                            req.uri(),
                            req.predicates(),
                            req.filters(),
                            req.routeOrder(),
                            req.enabled(),
                            existing.createdAt(),
                            OffsetDateTime.now()
                    );
                    return routeRepo.save(updated);
                })
                .flatMap(this::toResponse)
                .doOnSuccess(r -> publishRefresh("update", r.id()));
    }

    public Mono<Void> deleteRoute(String id) {
        return routeRepo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found: " + id)))
                .flatMap(entity -> permissionRepo.deleteByRouteId(id)
                        .then(routeRepo.delete(entity)))
                .doOnSuccess(v -> publishRefresh("delete", id));
    }

    public Mono<AdminDtos.RouteResponse> toggleRoute(String id, boolean enabled) {
        return routeRepo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found: " + id)))
                .flatMap(existing -> {
                    // isNew = false (default) → UPDATE
                    GatewayRouteEntity updated = new GatewayRouteEntity(
                            existing.id(), existing.uri(),
                            existing.predicates(), existing.filters(),
                            existing.routeOrder(), enabled,
                            existing.createdAt(), OffsetDateTime.now()
                    );
                    return routeRepo.save(updated);
                })
                .flatMap(this::toResponse)
                .doOnSuccess(r -> publishRefresh("toggle", r.id()));
    }

    @Transactional
    public Mono<List<Long>> assignPermissions(String routeId, List<Long> permissionIds) {
        return routeRepo.existsById(routeId)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found: " + routeId));
                    }
                    return permissionRepo.deleteByRouteId(routeId)
                            .thenMany(Flux.fromIterable(permissionIds)
                                    .flatMap(pid -> permissionRepo.insert(routeId, pid)))
                            .then(permissionRepo.findPermissionIdsByRouteId(routeId).collectList());
                });
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private Mono<AdminDtos.RouteResponse> toResponse(GatewayRouteEntity e) {
        return permissionRepo.findPermissionIdsByRouteId(e.id())
                .collectList()
                .map(ids -> new AdminDtos.RouteResponse(
                        e.id(), e.uri(), e.predicates(), e.filters(),
                        e.routeOrder(), e.enabled(), ids,
                        e.createdAt(), e.updatedAt()
                ));
    }

    private void publishRefresh(String action, String routeId) {
        log.info("Publishing RefreshRoutesEvent after [{}] on route [{}]", action, routeId);
        eventPublisher.publishEvent(new RefreshRoutesEvent(this));
    }
}
