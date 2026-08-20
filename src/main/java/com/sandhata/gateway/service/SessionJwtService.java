package com.sandhata.gateway.service;

import com.sandhata.gateway.model.UserContext;
import com.sandhata.gateway.model.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Mints and validates the gateway's own session JWT.
 *
 * This is the "BFF" half of the auth flow: after {@code AuthCallbackController}
 * exchanges the Azure AD auth code for an id_token (server-side, using the
 * client secret) and looks up the user's role via {@link RoleService}, it
 * calls {@link #issueToken} to mint a token that carries our own claims
 * (email, name, role, practice). That token — not Azure's — is what gets
 * stored in the HttpOnly session cookie and validated on every request by
 * {@code JwtAuthenticationFilter}.
 *
 * Keeping this separate from Azure's token means the gateway never needs to
 * re-validate against Azure's JWKS on every request, and the role/practice
 * claims are fully under our control.
 */
@Slf4j
@Service
public class SessionJwtService {

    public static final String COOKIE_NAME = "pd_session";

    @Value("${app.jwt.secret}")
    private String secretBase64;

    @Value("${app.jwt.expiration-minutes:480}")
    private long expirationMinutes;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = Decoders.BASE64.decode(secretBase64);
        if (keyBytes.length < 32) {
            // HS256 requires a >=256-bit key. Fail fast at startup rather than
            // producing tokens that would be trivially forgeable.
            throw new IllegalStateException(
                    "app.jwt.secret must decode to at least 32 bytes (256 bits). " +
                            "Set JWT_SECRET to a strong, random, base64-encoded value in every non-local environment.");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Mint a signed session token for the given user.
     */
    public String issueToken(UserContext user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofMinutes(expirationMinutes));

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("name", user.getName())
                .claim("role", user.getRole().name())
                .claim("practice", user.getPractice() != null ? user.getPractice() : "")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    public long getExpirationSeconds() {
        return expirationMinutes * 60;
    }

    /**
     * Validate and decode a session token. Returns empty if the token is
     * missing, malformed, expired, or has a bad signature — never throws.
     */
    public Optional<UserContext> parseToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            UserContext context = UserContext.builder()
                    .email(claims.getSubject())
                    .name(claims.get("name", String.class))
                    .practice(claims.get("practice", String.class))
                    .role(UserRole.parse(claims.get("role", String.class)))
                    .build();

            return Optional.of(context);
        } catch (ExpiredJwtException e) {
            log.debug("Session token expired for subject: {}", e.getClaims() != null ? e.getClaims().getSubject() : "unknown");
            return Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Rejected invalid session token: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
