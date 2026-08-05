package com.sandhata.gateway.service;

import com.sandhata.gateway.model.UserContext;
import com.sandhata.gateway.model.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches user role from the Practice Dashboard backend service
 * by looking up the user's email in the Integrators table.
 *
 * Caches results to avoid hitting the backend on every request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final WebClient.Builder webClientBuilder;

    @Value("${PRACTICE_DASHBOARD_SERVICE_URL:http://localhost:8081}")
    private String practiceServiceUrl;

    // Simple in-memory cache — email → UserContext
    // In production consider using Redis for distributed caching
    private final Map<String, UserContext> roleCache = new ConcurrentHashMap<>();

    /**
     * Get user context (role, practice etc.) for the given email.
     * First checks cache, then calls backend if not cached.
     */
    public Mono<UserContext> getUserContext(String email, String name) {

        // Return from cache if available
        if (roleCache.containsKey(email)) {
            log.debug("Role cache hit for: {}", email);
            return Mono.just(roleCache.get(email));
        }

        log.debug("Role cache miss for: {} — fetching from backend", email);

        // Call backend to get user details by email
        return webClientBuilder
                .baseUrl(practiceServiceUrl)
                .build()
                .get()
                .uri("/api/integration/getIntegratorByEmail/{email}", email)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    String role = response.getOrDefault("role", "VIEWER").toString();
                    String practice = response.getOrDefault("practice", "").toString();

                    UserContext context = UserContext.builder()
                            .email(email)
                            .name(name)
                            .practice(practice)
                            .role(parseRole(role))
                            .build();

                    // Cache the result
                    roleCache.put(email, context);
                    log.info("User {} assigned role: {} practice: {}", email, role, practice);
                    return context;
                })
                .onErrorResume(error -> {
                    // If backend call fails or user not found — assign GUEST role
                    log.warn("Could not fetch role for {} — assigning GUEST. Error: {}", email, error.getMessage());
                    UserContext guestContext = UserContext.builder()
                            .email(email)
                            .name(name)
                            .practice("")
                            .role(UserRole.GUEST)
                            .build();
                    return Mono.just(guestContext);
                });
    }

    /**
     * Clear cached role for a user — call this when role changes.
     */
    public void evictCache(String email) {
        roleCache.remove(email);
        log.info("Role cache evicted for: {}", email);
    }

    /**
     * Clear entire role cache — call this on deployment.
     */
    public void clearAllCache() {
        roleCache.clear();
        log.info("Role cache cleared");
    }

    private UserRole parseRole(String role) {
        try {
            return UserRole.valueOf(role.toUpperCase());
        } catch (Exception e) {
            return UserRole.VIEWER;
        }
    }
}
