package com.resource.service.controller;

import com.resource.service.client.AuthServiceClient;
import com.resource.service.dto.OrderResponse;
import com.resource.service.dto.PlaceOrderRequest;
import com.resource.service.dto.ProductResponse;
import com.resource.service.dto.UserProfileResponse;
import com.resource.service.dto.UserSummary;
import com.resource.service.service.OrderService;
import com.resource.service.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ProductService productService;
    private final OrderService orderService;
    private final AuthServiceClient authServiceClient;

    // ── Products ──────────────────────────────────────────────

    @GetMapping("/products")
    public ResponseEntity<List<ProductResponse>> getProducts() {
        return ResponseEntity.ok(productService.findAll());
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(productService.findById(id));
    }

    // ── Orders ────────────────────────────────────────────────

    @PostMapping("/orders")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<OrderResponse> placeOrder(
            @RequestBody PlaceOrderRequest request,
            @AuthenticationPrincipal String username) {
        return ResponseEntity.ok(orderService.placeOrder(username, request));
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<List<OrderResponse>> getMyOrders(
            @AuthenticationPrincipal String username) {
        return ResponseEntity.ok(orderService.findByUsername(username));
    }

    // ── Admin ─────────────────────────────────────────────────

    @GetMapping("/admin/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserSummary>> getUsers() {
        return ResponseEntity.ok(authServiceClient.getAllUsers());
    }

    @DeleteMapping("/admin/users/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        authServiceClient.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    // ── Profile ───────────────────────────────────────────────

    @GetMapping("/profile/{username}")
    @PreAuthorize("hasRole('ADMIN') or #username == authentication.name")
    public ResponseEntity<UserProfileResponse> getProfile(
            @PathVariable String username,
            @AuthenticationPrincipal String requestingUser) {
        return authServiceClient.getUserByUsername(username)
                .map(u -> ResponseEntity.ok(new UserProfileResponse(
                        u.username(),
                        u.fullName(),
                        u.provider(),
                        OffsetDateTime.now())))
                .orElse(ResponseEntity.notFound().build());
    }
}
