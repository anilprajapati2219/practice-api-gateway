package com.sandhata.gateway.filter;

import com.sandhata.gateway.config.RoleAccessProperties;
import com.sandhata.gateway.model.UserContext;
import com.sandhata.gateway.model.UserRole;
import com.sandhata.gateway.service.SessionJwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * Global filter that runs on every request through the gateway.
 *
 * Flow:
 *  1. Public paths (health check, the auth endpoints themselves, ...) pass
 *     straight through.
 *  2. Every other request must carry a valid {@code pd_session} cookie —
 *     the session JWT minted by {@code AuthCallbackController} at login.
 *     This is NOT an Azure AD token; the gateway validates its own token,
 *     signed with a secret only it knows ({@link SessionJwtService}).
 *  3. The token's role/practice claims (set at login time from the
 *     Integrators table) are checked against the path's minimum required
 *     role ({@link RoleAccessProperties}).
 *  4. On success, the request is forwarded to the backend with trusted
 *     X-User-* headers — any such headers the client sent itself are
 *     stripped first so they can't be spoofed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final SessionJwtService sessionJwtService;
    private final RoleAccessProperties roleAccessProperties;

    // Paths that do NOT require authentication
    private static final List<String> PUBLIC_PATHS = List.of(
            "/actuator/health",
            "/actuator/info",
            "/api/config/azure",
            "/api/auth/callback",
            "/api/auth/logout"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();
        String method = exchange.getRequest().getMethod().name();

        log.debug("Gateway filter — {} {}", method, path);

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        HttpCookie sessionCookie = exchange.getRequest().getCookies().getFirst(SessionJwtService.COOKIE_NAME);
        if (sessionCookie == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "no session cookie for path: " + path);
        }

        Optional<UserContext> maybeUser = sessionJwtService.parseToken(sessionCookie.getValue());
        if (maybeUser.isEmpty()) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "invalid/expired session token for path: " + path);
        }

        UserContext user = maybeUser.get();
        UserRole requiredRole = roleAccessProperties.minRoleFor(path);

        if (!user.getRole().atLeast(requiredRole)) {
            return reject(exchange, HttpStatus.FORBIDDEN,
                    "user " + user.getEmail() + " (role " + user.getRole() + ") lacks " + requiredRole + " for path: " + path);
        }

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(httpHeaders -> {
                            // Strip any client-supplied values so they can't spoof identity/role
                            httpHeaders.remove("X-User-Email");
                            httpHeaders.remove("X-User-Name");
                            httpHeaders.remove("X-User-Role");
                            httpHeaders.remove("X-User-Practice");

                            httpHeaders.set("X-User-Email", user.getEmail());
                            httpHeaders.set("X-User-Name", user.getName() != null ? user.getName() : "");
                            httpHeaders.set("X-User-Role", user.getRole().name());
                            httpHeaders.set("X-User-Practice", user.getPractice() != null ? user.getPractice() : "");
                        })
                        .build())
                .build();

        log.debug("Forwarding request for user: {} role: {} to: {}", user.getEmail(), user.getRole(), path);
        return chain.filter(mutatedExchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String reason) {
        log.warn("{} — {}", status, reason);
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    public int getOrder() {
        return -1; // Run before other filters
    }
}
