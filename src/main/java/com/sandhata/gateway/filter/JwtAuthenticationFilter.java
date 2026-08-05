package com.sandhata.gateway.filter;

import com.sandhata.gateway.model.UserContext;
import com.sandhata.gateway.model.UserRole;
import com.sandhata.gateway.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Global filter that runs on every request.
 *
 * Flow:
 * 1. Extract JWT from Authorization header
 * 2. Validate JWT (done by Spring Security OAuth2 Resource Server)
 * 3. Extract email and name from JWT claims
 * 4. Fetch user role from backend (via RoleService)
 * 5. Check if user has permission to access the requested path
 * 6. Forward request to backend with user context headers
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final RoleService roleService;

    // Paths that do NOT require authentication
    private static final List<String> PUBLIC_PATHS = List.of(
            "/actuator/health",
            "/actuator/info",
            "/api/config/azure"
    );

    // Paths that only ADMIN can access
    private static final List<String> ADMIN_ONLY_PATHS = List.of(
            "/api/announcements/add"
    );

    // Paths that ADMIN and MANAGER can access (write operations)
    private static final List<String> MANAGER_PATHS = List.of(
            "/api/availability-panel"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();
        String method = exchange.getRequest().getMethod().name();

        log.debug("Gateway filter — {} {}", method, path);

        // Allow public paths without authentication
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // Extract JWT and process
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(securityContext -> {
                    var authentication = securityContext.getAuthentication();
                    if (authentication == null || !authentication.isAuthenticated()) {
                        log.warn("Unauthenticated request to: {}", path);
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }

                    // Get JWT principal
                    Jwt jwt = (Jwt) authentication.getPrincipal();
                    String email = extractEmail(jwt);
                    String name = jwt.getClaimAsString("name");

                    if (email == null) {
                        log.warn("No email found in JWT for path: {}", path);
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }

                    // Fetch user role and check access
                    return roleService.getUserContext(email, name)
                            .flatMap(userContext -> {
                                // Check role-based access
                                if (!hasAccess(userContext.getRole(), path, method)) {
                                    log.warn("Access denied for user: {} role: {} path: {}",
                                            email, userContext.getRole(), path);
                                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                                    return exchange.getResponse().setComplete();
                                }

                                // Add user context headers to forward to backend
                                ServerWebExchange mutatedExchange = exchange.mutate()
                                        .request(exchange.getRequest().mutate()
                                                .header("X-User-Email", email)
                                                .header("X-User-Name", name != null ? name : "")
                                                .header("X-User-Role", userContext.getRole().name())
                                                .header("X-User-Practice", userContext.getPractice() != null
                                                        ? userContext.getPractice() : "")
                                                .build())
                                        .build();

                                log.debug("Forwarding request for user: {} role: {} to: {}",
                                        email, userContext.getRole(), path);
                                return chain.filter(mutatedExchange);
                            });
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // No security context — unauthenticated
                    log.warn("No security context for path: {}", path);
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }));
    }

    /**
     * Check if user role has access to the requested path and method.
     */
    private boolean hasAccess(UserRole role, String path, String method) {
        // GUEST role — read only access to basic paths
        if (role == UserRole.GUEST) {
            return method.equals("GET") && !isAdminOnlyPath(path) && !isManagerPath(path);
        }

        // VIEWER role — read only access to all API paths
        if (role == UserRole.VIEWER) {
            return method.equals("GET");
        }

        // MANAGER role — read and write but not admin paths
        if (role == UserRole.MANAGER) {
            return !isAdminOnlyPath(path);
        }

        // ADMIN role — full access to everything
        if (role == UserRole.ADMIN) {
            return true;
        }

        return false;
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    private boolean isAdminOnlyPath(String path) {
        return ADMIN_ONLY_PATHS.stream().anyMatch(path::startsWith);
    }

    private boolean isManagerPath(String path) {
        return MANAGER_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Extract email from JWT claims.
     * Azure AD uses 'preferred_username' or 'email' claim.
     */
    private String extractEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("preferred_username");
        if (email == null) {
            email = jwt.getClaimAsString("email");
        }
        if (email == null) {
            email = jwt.getClaimAsString("upn");
        }
        return email;
    }

    @Override
    public int getOrder() {
        return -1; // Run before other filters
    }
}
