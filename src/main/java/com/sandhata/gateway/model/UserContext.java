package com.sandhata.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Holds the authenticated user's context —
 * extracted from JWT and enriched with role from DB.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserContext {

    private String email;
    private String name;
    private String practice;
    private UserRole role;

}
