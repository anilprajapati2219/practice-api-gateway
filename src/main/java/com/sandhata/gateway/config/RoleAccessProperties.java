package com.sandhata.gateway.config;

import com.sandhata.gateway.model.UserRole;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds the {@code role-access.rules} list from application.yml.
 *
 * Each rule pairs an Ant-style path pattern with the minimum {@link UserRole}
 * required to access it. Rules are evaluated in the order they're declared —
 * first match wins — so put more specific patterns before broader ones
 * (e.g. "/api/announcements/add" before "/api/**").
 *
 * If no rule matches a path, {@link #defaultMinRole} applies (defaults to
 * USER — any authenticated user).
 *
 * Note: this only gates *which endpoints* a role may call. Which *rows* of
 * data a Function Lead/Team Lead/etc. actually sees within an allowed
 * endpoint is enforced by the backend, using the X-User-Role / X-User-Email /
 * X-User-Practice headers the gateway forwards on every request.
 */
@Data
@Component
@ConfigurationProperties(prefix = "role-access")
public class RoleAccessProperties {

    private List<Rule> rules = new ArrayList<>();

    private UserRole defaultMinRole = UserRole.USER;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Minimum role required for the given path, per the configured rules.
     */
    public UserRole minRoleFor(String path) {
        for (Rule rule : rules) {
            if (pathMatcher.match(rule.getPath(), path)) {
                return rule.getMinRole();
            }
        }
        return defaultMinRole;
    }

    @Data
    public static class Rule {
        private String path;
        private UserRole minRole = UserRole.USER;
    }
}
