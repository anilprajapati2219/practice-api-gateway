package com.sandhata.gateway.config;

import com.sandhata.gateway.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal controller to manage role cache.
 * Use this when a user's role changes in the database
 * so the gateway picks up the new role immediately.
 */
@Slf4j
@RestController
@RequestMapping("/internal/cache")
@RequiredArgsConstructor
public class CacheController {

    private final RoleService roleService;

    /**
     * Evict cache for a specific user.
     * Call this when a user's role is updated in the Integrators table.
     *
     * Example: POST /internal/cache/evict/anil.prajapati@sandhata.com
     */
    @PostMapping("/evict/{email}")
    public ResponseEntity<String> evictUserCache(@PathVariable String email) {
        roleService.evictCache(email);
        return ResponseEntity.ok("Cache evicted for: " + email);
    }

    /**
     * Clear entire role cache.
     * Call this after a bulk update of roles in the database.
     *
     * Example: POST /internal/cache/clear
     */
    @PostMapping("/clear")
    public ResponseEntity<String> clearAllCache() {
        roleService.clearAllCache();
        return ResponseEntity.ok("All role cache cleared");
    }
}
