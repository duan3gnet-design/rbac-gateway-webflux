package com.api.gateway.validator;

import com.auth.service.dto.ClaimsResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

@DisplayName("JwtValidator")
class JwtValidatorTest {

    // Cùng secret với application.yml (base64 encoded)
    private static final String SECRET = "bXlfc3VwZXJfc2VjcmV0X2tleV9mb3JfcmJhY19nYXRld2F5XzIwMjQ=";

    private JwtValidator jwtValidator;

    @BeforeEach
    void setUp() {
        jwtValidator = new JwtValidator();
        ReflectionTestUtils.setField(jwtValidator, "secret", SECRET);
        jwtValidator.init();
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    }

    /** Tạo valid JWT với đầy đủ claims */
    private String buildToken(String subject, List<String> roles, List<String> permissions, long expiryMs) {
        return Jwts.builder()
                .subject(subject)
                .claim("roles", roles)
                .claim("permissions", permissions)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(secretKey())
                .compact();
    }

    /** Token đã hết hạn */
    private String buildExpiredToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .claim("roles", List.of("ROLE_USER"))
                .claim("permissions", List.of("products:READ"))
                .issuedAt(new Date(System.currentTimeMillis() - 10_000))
                .expiration(new Date(System.currentTimeMillis() - 5_000)) // expired 5s ago
                .signWith(secretKey())
                .compact();
    }

    /** Token ký bằng key khác */
    private String buildTokenWithWrongKey(String subject) {
        String wrongSecret = "d3JvbmdfX19rZXlfX190aGlzX2lzX25vdF92YWxpZF9rZXlfYXRfYWxs";
        SecretKey wrongKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(wrongSecret));
        return Jwts.builder()
                .subject(subject)
                .claim("roles", List.of("ROLE_USER"))
                .claim("permissions", List.of("products:READ"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(wrongKey)
                .compact();
    }

    // ─── VALID TOKEN ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("valid token")
    class ValidToken {

        @Test
        @DisplayName("Parse đúng username từ subject")
        void shouldParseUsername() {
            String token = buildToken("phan@example.com",
                    List.of("ROLE_USER"), List.of("products:READ"), 60_000);

            StepVerifier.create(jwtValidator.validate(token))
                    .assertNext(claims -> {
                        assert claims.username().equals("phan@example.com");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Parse đúng roles list")
        void shouldParseRoles() {
            String token = buildToken("user1",
                    List.of("ROLE_ADMIN", "ROLE_USER"), List.of("users:READ"), 60_000);

            StepVerifier.create(jwtValidator.validate(token))
                    .assertNext(claims -> {
                        assert claims.roles().contains("ROLE_ADMIN");
                        assert claims.roles().contains("ROLE_USER");
                        assert claims.roles().size() == 2;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Parse đúng permissions set")
        void shouldParsePermissions() {
            String token = buildToken("user1",
                    List.of("ROLE_USER"),
                    List.of("products:READ", "orders:READ", "orders:CREATE"),
                    60_000);

            StepVerifier.create(jwtValidator.validate(token))
                    .assertNext(claims -> {
                        assert claims.permissions().contains("products:READ");
                        assert claims.permissions().contains("orders:READ");
                        assert claims.permissions().contains("orders:CREATE");
                        assert claims.permissions().size() == 3;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Token không có permissions claim → trả về empty set (không throw)")
        void tokenWithoutPermissions_shouldReturnEmptySet() {
            // Build token manually không có permissions claim
            String token = Jwts.builder()
                    .subject("user-no-perms")
                    .claim("roles", List.of("ROLE_GUEST"))
                    // deliberately no "permissions" claim
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(secretKey())
                    .compact();

            StepVerifier.create(jwtValidator.validate(token))
                    .assertNext(claims -> {
                        assert claims.permissions().isEmpty();
                        assert claims.username().equals("user-no-perms");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("ClaimsResponse là record đúng kiểu")
        void shouldReturnClaimsResponseRecord() {
            String token = buildToken("admin", List.of("ROLE_ADMIN"), List.of("users:DELETE"), 60_000);

            StepVerifier.create(jwtValidator.validate(token))
                    .assertNext(claims -> {
                        assert claims instanceof ClaimsResponse;
                    })
                    .verifyComplete();
        }
    }

    // ─── INVALID TOKEN ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("invalid token")
    class InvalidToken {

        @Test
        @DisplayName("Token hết hạn → emit error (không complete)")
        void expiredToken_shouldEmitError() {
            String token = buildExpiredToken("user1");

            StepVerifier.create(jwtValidator.validate(token))
                    .expectError()
                    .verify();
        }

        @Test
        @DisplayName("Token ký bằng wrong key → emit error")
        void wrongKeyToken_shouldEmitError() {
            String token = buildTokenWithWrongKey("attacker");

            StepVerifier.create(jwtValidator.validate(token))
                    .expectError()
                    .verify();
        }

        @Test
        @DisplayName("Token rác (random string) → emit error")
        void malformedToken_shouldEmitError() {
            StepVerifier.create(jwtValidator.validate("this.is.not.a.jwt"))
                    .expectError()
                    .verify();
        }

        @Test
        @DisplayName("Token rỗng → emit error")
        void emptyToken_shouldEmitError() {
            StepVerifier.create(jwtValidator.validate(""))
                    .expectError()
                    .verify();
        }

        @Test
        @DisplayName("Token chỉ có 2 phần (thiếu signature) → emit error")
        void tokenMissingSignature_shouldEmitError() {
            StepVerifier.create(jwtValidator.validate("header.payload"))
                    .expectError()
                    .verify();
        }
    }
}
