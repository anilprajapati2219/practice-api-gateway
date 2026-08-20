package com.sandhata.gateway.model;

/**
 * Roles available in Practice Dashboard, stored in the {@code role} column
 * of the Integrators table and looked up by email
 * ({@code /api/integration/getIntegratorByEmail/{email}}).
 *
 * Privilege order, highest to lowest:
 *   ADMIN &gt; PRACTICE_HEAD &gt; FUNCTION_LEAD &gt; TEAM_LEAD &gt; USER
 *
 * {@link #rank} encodes that order so access checks are simple numeric
 * comparisons (see {@link com.sandhata.gateway.config.RoleAccessProperties}
 * and {@code JwtAuthenticationFilter}). A role can access anything that
 * requires a rank less than or equal to its own.
 */
public enum UserRole {

    /**
     * Default role — authenticated but not specially privileged.
     * Also the safe fallback when a user isn't found in the Integrators
     * table, or the role value on their record isn't recognized.
     */
    USER(1),

    /**
     * Leads a team under a function.
     */
    TEAM_LEAD(2),

    /**
     * Leads a function; can see data belonging to their function
     * (and everything a Team Lead can see).
     */
    FUNCTION_LEAD(3),

    /**
     * Oversees an entire practice; can see data across all functions
     * and teams within their practice.
     */
    PRACTICE_HEAD(4),

    /**
     * Full access — superuser across all practices.
     */
    ADMIN(5);

    private final int rank;

    UserRole(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    /**
     * True if this role's privilege is greater than or equal to
     * {@code required} — i.e. this role is allowed to access something
     * that requires at least {@code required}.
     */
    public boolean atLeast(UserRole required) {
        return this.rank >= required.rank;
    }

    /**
     * Parses a role value coming from the Integrators table / backend
     * response. Case-insensitive and tolerant of spaces
     * (e.g. "Function Lead", "function_lead", "FUNCTION_LEAD" all match
     * {@link #FUNCTION_LEAD}). Falls back to {@link #USER} for anything
     * unrecognized, null, or blank — never throws.
     */
    public static UserRole parse(String value) {
        if (value == null || value.isBlank()) {
            return USER;
        }
        String normalized = value.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        try {
            return UserRole.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}
