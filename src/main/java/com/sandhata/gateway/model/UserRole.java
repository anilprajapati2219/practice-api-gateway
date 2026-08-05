package com.sandhata.gateway.model;

/**
 * Roles available in Practice Dashboard.
 * These roles are stored in the Integrators table
 * and assigned to each user by their email.
 */
public enum UserRole {

    /**
     * Full access — can add announcements, manage panel,
     * view all data across all practices.
     */
    ADMIN,

    /**
     * Can view all data for their practice,
     * can access business, marketing, training APIs.
     */
    MANAGER,

    /**
     * Read-only access — can view dashboard data
     * but cannot add or modify anything.
     */
    VIEWER,

    /**
     * Default role when user is authenticated but
     * not found in the Integrators table.
     */
    GUEST
}
