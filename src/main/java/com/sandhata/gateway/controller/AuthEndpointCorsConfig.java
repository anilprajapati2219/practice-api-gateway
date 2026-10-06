package com.sandhata.gateway.controller;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * spring.cloud.gateway.globalcors only applies to proxied gateway routes.
 * /api/auth/** is served locally by AuthCallbackController, so it needs its
 * own CORS mapping. This copies the allowed origins from globalcors so both
 * stay in sync.
 */
@Configuration
public class AuthEndpointCorsConfig implements WebFluxConfigurer {

    private final GlobalCorsProperties globalCorsProperties;

    public AuthEndpointCorsConfig(GlobalCorsProperties globalCorsProperties) {
        this.globalCorsProperties = globalCorsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        CorsConfiguration global = globalCorsProperties.getCorsConfigurations().get("/**");
        if (global == null || global.getAllowedOrigins() == null) {
            return;
        }
        registry.addMapping("/api/auth/**")
                .allowedOrigins(global.getAllowedOrigins().toArray(new String[0]))
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
