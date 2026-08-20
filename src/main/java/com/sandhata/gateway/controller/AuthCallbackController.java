package com.sandhata.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandhata.gateway.service.RoleService;
import com.sandhata.gateway.service.SessionJwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * BFF token exchange endpoint.
 *
 * Angular's login.component.ts redirects the browser straight to Microsoft
 * (no MSAL, no PKCE — the app registration is "Web" type now, not SPA), gets
 * back an auth {@code code}, and POSTs it here.
 *
 * This controller:
 *  1. Exchanges the code for tokens at Microsoft's /token endpoint using our
 *     confidential-client credentials (client id + client secret). This is a
 *     server-to-server call over TLS — it's exactly what "Web" app type
 *     (vs "SPA") is for, and it's why the SPA-era errors (AADSTS9002327,
 *     AADSTS700025, AADSTS50148, no_token_request_cache_error) don't apply
 *     anymore: none of that PKCE/SPA machinery is involved.
 *  2. Reads the user's email/name off the returned id_token.
 *  3. Looks up their role via {@link RoleService} (Integrators table).
 *  4. Mints our own session JWT ({@link SessionJwtService}) and sets it as
 *     an HttpOnly cookie. Azure's tokens are discarded after this point —
 *     the browser only ever holds our cookie.
 *
 * Note on routing: this controller is mapped under {@code /api/auth/**},
 * which is a sub-path of the gateway's {@code /api/**} route to the backend.
 * Spring's local @RestController mappings are matched before Spring Cloud
 * Gateway's route predicates (RequestMappingHandlerMapping has a lower order
 * than RoutePredicateHandlerMapping), so requests to these two exact paths
 * are handled here and never proxied to the backend.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthCallbackController {

    private final WebClient.Builder webClientBuilder;
    private final RoleService roleService;
    private final SessionJwtService sessionJwtService;
    private final ObjectMapper objectMapper;

    @Value("${azure.tenant-id}")
    private String tenantId;

    @Value("${azure.client-id}")
    private String clientId;

    @Value("${azure.client-secret}")
    private String clientSecret;

    @Value("${azure.redirect-uri}")
    private String redirectUri;

    @Value("${azure.scopes:openid profile email}")
    private String scopes;

    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;

    public record AuthCallbackRequest(String code) {}

    @PostMapping("/callback")
    public Mono<ResponseEntity<Map<String, Object>>> callback(
            @RequestBody AuthCallbackRequest request,
            ServerHttpResponse response) {

        if (request == null || request.code() == null || request.code().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "missing_code")));
        }

        return exchangeCodeForTokens(request.code())
                .flatMap(tokenResponse -> {
                    Object idTokenObj = tokenResponse.get("id_token");
                    if (idTokenObj == null) {
                        log.warn("Azure token response had no id_token");
                        return Mono.just(unauthorized("no_id_token"));
                    }

                    Map<String, Object> claims;
                    try {
                        claims = decodeAndValidateIdToken(idTokenObj.toString());
                    } catch (Exception e) {
                        log.warn("Rejected id_token: {}", e.getMessage());
                        return Mono.just(unauthorized("invalid_id_token"));
                    }

                    String email = firstNonBlank(
                            claims.get("preferred_username"), claims.get("email"), claims.get("upn"));
                    String name = str(claims.get("name"));

                    if (email == null) {
                        log.warn("id_token had no usable email claim");
                        return Mono.just(unauthorized("no_email_claim"));
                    }

                    return roleService.getUserContext(email, name)
                            .map(userContext -> {
                                String sessionToken = sessionJwtService.issueToken(userContext);

                                ResponseCookie cookie = ResponseCookie.from(SessionJwtService.COOKIE_NAME, sessionToken)
                                        .httpOnly(true)
                                        .secure(cookieSecure)
                                        .sameSite("Lax")
                                        .path("/")
                                        .maxAge(sessionJwtService.getExpirationSeconds())
                                        .build();
                                response.addCookie(cookie);

                                log.info("Login succeeded — user: {} role: {}", email, userContext.getRole());
                                return ResponseEntity.ok(Map.<String, Object>of(
                                        "email", email,
                                        "name", name != null ? name : "",
                                        "role", userContext.getRole().name()
                                ));
                            });
                })
                .onErrorResume(e -> {
                    log.error("Auth callback failed: {}", e.getMessage());
                    return Mono.just(buildFailureResponse(e));
                });
    }

    /**
     * Surfaces the real failure reason in the 401 body instead of a flat
     * "token_exchange_failed" — this is what shows up in the browser's
     * Network tab, which is often the only place anyone actually looks when
     * something's wrong. Safe to expose: Azure's OAuth error/error_description
     * fields never contain the client secret or any token, only a reason
     * code like "unauthorized_client" / "invalid_grant" plus an AADSTS
     * message. For a non-Azure failure (DNS, connection refused, timeout —
     * i.e. the gateway pod couldn't even reach login.microsoftonline.com)
     * we surface the exception type/message instead, which is likewise safe.
     */
    private ResponseEntity<Map<String, Object>> buildFailureResponse(Throwable e) {
        if (e instanceof AzureTokenExchangeException aze) {
            Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("error", "azure_token_exchange_failed");
            details.put("azureHttpStatus", aze.azureStatus);
            try {
                Map<?, ?> azureBody = objectMapper.readValue(aze.azureBody, Map.class);
                details.put("azureError", azureBody.get("error"));
                details.put("azureErrorDescription", azureBody.get("error_description"));
            } catch (Exception parseFailure) {
                // Azure didn't return JSON — surface the raw body, truncated.
                String raw = aze.azureBody;
                details.put("azureRawBody", raw.length() > 500 ? raw.substring(0, 500) + "..." : raw);
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(details);
        }

        // Not an HTTP error from Azure at all — the gateway pod likely
        // couldn't reach login.microsoftonline.com (network/DNS/proxy/egress
        // issue), or some other unexpected failure.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "token_exchange_failed",
                "exceptionType", e.getClass().getName(),
                "exceptionMessage", String.valueOf(e.getMessage())
        ));
    }

    /**
     * Carries Azure's actual HTTP status + response body up from
     * {@link #exchangeCodeForTokens} so the controller can put it in the
     * response instead of a flat generic error.
     */
    private static final class AzureTokenExchangeException extends RuntimeException {
        private final int azureStatus;
        private final String azureBody;

        AzureTokenExchangeException(int azureStatus, String azureBody) {
            super("azure_token_exchange_failed [" + azureStatus + "]");
            this.azureStatus = azureStatus;
            this.azureBody = azureBody;
        }
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerHttpResponse response) {
        ResponseCookie expired = ResponseCookie.from(SessionJwtService.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addCookie(expired);
        return Mono.just(ResponseEntity.ok().build());
    }

    /**
     * Server-to-server call to Microsoft's token endpoint — the confidential
     * client credentials grant. Requires the app registration to be "Web"
     * type with a client secret; this is the call that fails with
     * AADSTS700025/AADSTS9002327 against a "SPA" registration.
     */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> exchangeCodeForTokens(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("scope", scopes);

        String tokenUrl = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token";

        return webClientBuilder.build()
                .post()
                .uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse ->
                        clientResponse.bodyToMono(String.class).flatMap(body -> {
                            log.warn("Azure token exchange failed [{}]: {}", clientResponse.statusCode(), body);
                            return Mono.error(new AzureTokenExchangeException(clientResponse.statusCode().value(), body));
                        }))
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m);
    }

    /**
     * Decode the id_token's payload and sanity-check aud/iss/exp.
     *
     * We deliberately do NOT verify the id_token's signature here: it was
     * obtained directly from Microsoft's token endpoint over a
     * server-to-server TLS connection (never touched the browser), which is
     * the trusted channel OpenID Connect relies on for the authorization
     * code flow. We still check audience, issuer and expiry as a
     * defense-in-depth sanity check.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeAndValidateIdToken(String idToken) throws Exception {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("malformed id_token");
        }
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        Map<String, Object> claims = objectMapper.readValue(payloadBytes, Map.class);

        Object aud = claims.get("aud");
        if (aud == null || !clientId.equals(aud.toString())) {
            throw new IllegalStateException("id_token audience mismatch");
        }

        Object iss = claims.get("iss");
        if (iss == null || !iss.toString().contains(tenantId)) {
            throw new IllegalStateException("id_token issuer mismatch");
        }

        Object exp = claims.get("exp");
        if (exp instanceof Number expNumber && Instant.now().getEpochSecond() > expNumber.longValue()) {
            throw new IllegalStateException("id_token expired");
        }

        return claims;
    }

    private ResponseEntity<Map<String, Object>> unauthorized(String error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", error));
    }

    private static String firstNonBlank(Object... values) {
        for (Object v : values) {
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
