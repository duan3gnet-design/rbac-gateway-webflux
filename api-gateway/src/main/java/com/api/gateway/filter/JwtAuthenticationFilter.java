package com.api.gateway.filter;

import com.api.gateway.validator.JwtValidator;
import com.api.gateway.validator.RbacPermissionChecker;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    /**
     * Attribute key được đặt vào exchange khi request bị reject (401/403).
     * RateLimitFilter đọc key này để skip xử lý — tránh overwrite response đã set.
     */
    public static final String ATTR_AUTH_REJECTED = "gateway.auth.rejected";

    private final JwtValidator jwtValidator;
    private final RbacPermissionChecker rbacChecker;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login", "/api/auth/register", "/api/auth/refresh", "/api/auth/logout",
            "/api/auth/validate",
            "/api/auth/google", "/oauth2/**", "/login/oauth2/**"
    );

    private static final List<String> ADMIN_PATHS = List.of("/api/admin/**");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, @NonNull GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest()
                .getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }

        String token = authHeader.substring(7);

        return jwtValidator.validate(token)
                .flatMap(claims -> {
                    if (isAdminPath(path) && !claims.roles().contains("ROLE_ADMIN")) {
                        return forbidden(exchange);
                    }

                    if (!isAdminPath(path) && !rbacChecker.hasPermission(claims.permissions(), method, path)) {
                        return forbidden(exchange);
                    }

                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .header("X-User-Name", claims.username())
                            .header("X-User-Roles", String.join(",", claims.roles()))
                            .header("X-User-Permissions", String.join(",", claims.permissions()))
                            .build();

                    exchange.getAttributes().put("jwt.claims", claims);

                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                })
                .onErrorResume(e -> unauthorized(exchange));
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    private boolean isAdminPath(String path) {
        return ADMIN_PATHS.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getAttributes().put(ATTR_AUTH_REJECTED, true);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        exchange.getAttributes().put(ATTR_AUTH_REJECTED, true);
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
