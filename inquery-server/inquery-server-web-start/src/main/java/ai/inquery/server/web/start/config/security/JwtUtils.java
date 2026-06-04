package ai.inquery.server.web.start.config.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * JWT Token utility class
 */
@Slf4j
@Component
public class JwtUtils {

    @Value("${inquery.jwt.secret:inquery-default-secret-key-please-change-in-production}")
    private String secretKey;

    @Value("${inquery.jwt.expiration:2592000}")
    private long expirationSeconds; // Default 30 days

    @Value("${inquery.jwt.issuer:inquery}")
    private String issuer;

    /**
     * Generate JWT token for user
     */
    public String generateToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationSeconds * 1000);

        return JWT.create()
                .withIssuer(issuer)
                .withSubject(String.valueOf(userId))
                .withIssuedAt(now)
                .withExpiresAt(expiryDate)
                .sign(Algorithm.HMAC256(secretKey));
    }

    /**
     * Validate token and extract user ID
     * @return user ID if valid, null if invalid
     */
    public Long validateTokenAndGetUserId(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secretKey);
            JWTVerifier verifier = JWT.require(algorithm)
                    .withIssuer(issuer)
                    .build();
            
            DecodedJWT jwt = verifier.verify(token);
            String subject = jwt.getSubject();
            
            return Long.parseLong(subject);
        } catch (JWTVerificationException | NumberFormatException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if token is valid
     */
    public boolean isTokenValid(String token) {
        return validateTokenAndGetUserId(token) != null;
    }
}
