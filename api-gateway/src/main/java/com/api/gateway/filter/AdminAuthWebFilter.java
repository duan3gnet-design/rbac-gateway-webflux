package com.api.gateway.filter;

import com.api.gateway.validator.JwtValidator;
import com.auth.service.dto.ClaimsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFilter bảo vệ local admin endpoints ({@code /api/admin/**}).
 *
 * <p><b>Lưu ý quan trọng về error handling:</b> {@code onErrorResume} chỉ được
 * apply trên {@code validateAdmin()} — KHÔNG wrap toàn bộ {@code chain.filter()}.
 * Nếu wrap cả chain, các exception từ controller (như {@code ResponseStatusException 409})
 * sẽ bị bắt nhầm thành 401.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuthWebFilter implements WebFilter, Ordered {

    private final JwtValidator jwtValidator;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final String ADMIN_PATH_PATTERN   = "/api/admin/**";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (!PATH_MATCHER.match(ADMIN_PATH_PATTERN, path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest()
                .getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("AdminAuthWebFilter: no Bearer token → 401");
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        String token = authHeader.substring(7);

        // validateAdmin() trả Mono<ClaimsResponse> hoặc error.
        // chain.filter() được gọi SAU khi validate xong — exception từ controller
        // (409, 404, v.v.) không bị onErrorResume bên trong validateAdmin() bắt nhầm.
        return validateAdmin(token, exchange)
                .flatMap(claims -> chain.filter(exchange));
    }

    /**
     * Validate token và kiểm tra ROLE_ADMIN.
     * Trả về {@code Mono<ClaimsResponse>} nếu hợp lệ,
     * hoặc tự ghi response 401/403 rồi trả {@code Mono.empty()} để short-circuit.
     */
    private Mono<ClaimsResponse> validateAdmin(String token, ServerWebExchange exchange) {
        return jwtValidator.validate(token)
                .flatMap(claims -> {
                    if (!claims.roles().contains("ROLE_ADMIN")) {
                        log.debug("AdminAuthWebFilter: missing ROLE_ADMIN for user {} → 403",
                                claims.username());
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                        return exchange.getResponse().setComplete().then(Mono.empty());
                    }
                    log.debug("AdminAuthWebFilter: ROLE_ADMIN confirmed for user {}", claims.username());
                    return Mono.just(claims);
                })
                .onErrorResume(e -> {
                    log.warn("AdminAuthWebFilter: token validation failed [{}]: {}",
                            e.getClass().getSimpleName(), e.getMessage());
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete().then(Mono.empty());
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
