package com.auth.service.controller;

import com.auth.service.dto.*;
import com.auth.service.service.*;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authManager;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final PermissionService permissionService;
    private final GoogleAuthService googleAuthService;

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody @Validated LoginRequest req) {
        authManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password())
        );
        var userDetails = userDetailsService.loadUserByUsername(req.username());
        String accessToken = jwtService.generateToken(userDetails);
        String refreshToken = refreshTokenService.createRefreshToken(req.username()).getToken();
        return ResponseEntity.ok(new TokenResponse(accessToken, refreshToken));
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody @Validated RegisterRequest req) {
        userService.register(req);
        return ResponseEntity.status(201).body("User created");
    }

    @GetMapping("/validate")
    public ResponseEntity<ClaimsResponse> validate(
            @RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        if (!jwtService.isValid(token)) {
            return ResponseEntity.status(401).build();
        }

        Claims claims = jwtService.extractClaims(token);
        List<String> roles = claims.get("roles", List.class);

        Set<String> permissions = permissionService.getPermissions(roles);

        return ResponseEntity.ok(new ClaimsResponse(
                claims.getSubject(),
                roles,
                permissions
        ));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenPair> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {
        TokenPair tokens = refreshTokenService.rotate(request.refreshToken());
        return ResponseEntity.ok(tokens);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.revokeToken(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(
            @RequestHeader("X-User-Name") String username) {
        refreshTokenService.revokeAllTokens(username);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/google")
    public ResponseEntity<TokenResponse> googleLogin(
            @Valid @RequestBody GoogleTokenRequest request) {
        TokenResponse tokens = googleAuthService.authenticate(request.idToken());
        return ResponseEntity.ok(tokens);
    }
}

