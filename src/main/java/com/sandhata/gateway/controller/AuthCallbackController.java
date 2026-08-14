//package com.sandhata.gateway.controller;
//
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.HttpCookie;
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ResponseCookie;
//import org.springframework.http.ResponseEntity;
//import org.springframework.http.server.reactive.ServerHttpResponse;
//import org.springframework.util.LinkedMultiValueMap;
//import org.springframework.util.MultiValueMap;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.reactive.function.BodyInserters;
//import org.springframework.web.reactive.function.client.WebClient;
//import reactor.core.publisher.Mono;
//
//import java.time.Duration;
//import java.util.Map;
//
///**
// * BFF (Backend for Frontend) Auth Controller.
// *
// * Handles token exchange — frontend sends auth code,
// * gateway exchanges it with Azure AD and stores token
// * in a secure HTTP-only cookie.
// *
// * Frontend teams do NOT need to handle tokens at all.
// */
//@Slf4j
//@RestController
//@RequestMapping("/api/auth")
//public class AuthCallbackController {
//
//    @Value("${azure.tenant-id}")
//    private String tenantId;
//
//    @Value("${azure.client-id}")
//    private String clientId;
//
//    @Value("${azure.redirect-uri}")
//    private String redirectUri;
//
//    private final WebClient webClient;
//
//    public AuthCallbackController(WebClient.Builder webClientBuilder) {
//        this.webClient = webClientBuilder.build();
//    }
//
//    /**
//     * Exchange auth code for JWT token.
//     * Frontend calls this after getting code from Azure AD.
//     *
//     * POST /api/auth/callback
//     * Body: { "code": "...", "codeVerifier": "..." }
//     */
//    @PostMapping("/callback")
//    public Mono<ResponseEntity<Map<String, String>>> callback(
//            @RequestBody Map<String, String> body,
//            ServerHttpResponse response) {
//
//        String code = body.get("code");
//        String codeVerifier = body.get("codeVerifier");
//
//        if (code == null || code.isEmpty()) {
//            return Mono.just(ResponseEntity
//                    .badRequest()
//                    .body(Map.of("error", "code is required")));
//        }
//
//        log.info("Exchanging auth code for token");
//
//        // Build form data for token request
//        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
//        formData.add("grant_type", "authorization_code");
//        formData.add("client_id", clientId);
//        formData.add("code", code);
//        formData.add("redirect_uri", redirectUri);
//        formData.add("scope", "openid profile email");
//        if (codeVerifier != null && !codeVerifier.isEmpty()) {
//            formData.add("code_verifier", codeVerifier);
//        }
//
//        // Exchange code for token with Azure AD
//        return webClient.post()
//                .uri("https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token")
//                .header("Content-Type", "application/x-www-form-urlencoded")
//                .body(BodyInserters.fromFormData(formData))
//                .retrieve()
//                .bodyToMono(Map.class)
//                .map(tokenResponse -> {
//                    String accessToken = (String) tokenResponse.get("access_token");
//                    String idToken = (String) tokenResponse.get("id_token");
//                    Integer expiresIn = (Integer) tokenResponse.get("expires_in");
//
//                    if (accessToken == null) {
//                        log.error("No access token in response: {}", tokenResponse);
//                        return ResponseEntity
//                                .status(HttpStatus.UNAUTHORIZED)
//                                .<Map<String, String>>body(Map.of("error", "Failed to get token"));
//                    }
//
//                    // Store token in HTTP-only secure cookie
//                    // HttpOnly = JavaScript cannot access it (XSS protection)
//                    // Secure = only sent over HTTPS
//                    // SameSite=Strict = CSRF protection
//                    ResponseCookie accessTokenCookie = ResponseCookie
//                            .from("access_token", accessToken)
//                            .httpOnly(true)
//                            .secure(true)
//                            .path("/")
//                            .maxAge(Duration.ofSeconds(expiresIn != null ? expiresIn : 3600))
//                            .sameSite("Strict")
//                            .build();
//
//                    response.addCookie(accessTokenCookie);
//
//                    log.info("Token stored in HTTP-only cookie successfully");
//
//                    return ResponseEntity.ok(Map.of("status", "success"));
//                })
//                .onErrorResume(org.springframework.web.reactive.function.client.WebClientResponseException.class, error -> {
//            log.error("Token exchange failed. Status: {}, Body: {}",
//                    error.getStatusCode(), error.getResponseBodyAsString());
//            return Mono.just(ResponseEntity
//                    .status(HttpStatus.UNAUTHORIZED)
//                    .<Map<String, String>>body(Map.of("error", "Token exchange failed",
//                            "detail", error.getResponseBodyAsString())));
//        })
//                .onErrorResume(error -> {
//                    log.error("Token exchange failed (non-HTTP error): {}", error.toString());
//                    return Mono.just(ResponseEntity
//                            .status(HttpStatus.UNAUTHORIZED)
//                            .<Map<String, String>>body(Map.of("error", "Token exchange failed")));
//                });
////                .onErrorResume(error -> {
////                    log.error("Token exchange failed: {}", error.getMessage());
////                    return Mono.just(ResponseEntity
////                            .status(HttpStatus.UNAUTHORIZED)
////                            .<Map<String, String>>body(Map.of("error", "Token exchange failed")));
////                });
//    }
//
//    /**
//     * Logout — clear the token cookie.
//     * POST /api/auth/logout
//     */
//    @PostMapping("/logout")
//    public Mono<ResponseEntity<Map<String, String>>> logout(ServerHttpResponse response) {
//
//        // Clear the cookie by setting maxAge to 0
//        ResponseCookie clearCookie = ResponseCookie
//                .from("access_token", "")
//                .httpOnly(true)
//                .secure(true)
//                .path("/")
//                .maxAge(Duration.ZERO)
//                .sameSite("Strict")
//                .build();
//
//        response.addCookie(clearCookie);
//
//        log.info("User logged out — token cookie cleared");
//
//        return Mono.just(ResponseEntity.ok(Map.of("status", "logged out")));
//    }
//
//    /**
//     * Check if user is logged in.
//     * GET /api/auth/me
//     * Returns user info from cookie token.
//     */
//    @GetMapping("/me")
//    public Mono<ResponseEntity<Map<String, String>>> me(
//            @CookieValue(name = "access_token", required = false) String token) {
//
//        if (token == null || token.isEmpty()) {
//            return Mono.just(ResponseEntity
//                    .status(HttpStatus.UNAUTHORIZED)
//                    .body(Map.of("error", "Not logged in")));
//        }
//
//        // Decode JWT claims (base64) — no need to verify again here
//        // Gateway filter already verified the token
//        try {
//            String[] parts = token.split("\\.");
//            if (parts.length >= 2) {
//                String payload = new String(
//                        java.util.Base64.getUrlDecoder().decode(parts[1]));
//                return Mono.just(ResponseEntity.ok(
//                        Map.of("status", "logged in", "payload", payload)));
//            }
//        } catch (Exception e) {
//            log.error("Failed to decode token: {}", e.getMessage());
//        }
//
//        return Mono.just(ResponseEntity.ok(Map.of("status", "logged in")));
//    }
//}