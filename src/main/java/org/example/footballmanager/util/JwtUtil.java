package org.example.footballmanager.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.example.footballmanager.model.User;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    // Jak ključ (min 256 bitova za HS256/HS384/HS512)
    // U produkciji: čitaj iz application.properties ili env varijable
    private static final String SECRET_STRING = "VeljaTestSecretKeyVeryLongAndSecure12345678901234567890";
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET_STRING.getBytes(StandardCharsets.UTF_8));

    private static final long EXPIRATION_MS = 86400000; // 1 dan

    public String generateToken(User user) {
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("role", user.getRole().name())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(SECRET_KEY) // novi stil – JJWT sam bira algoritam po dužini ključa
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return Jwts.parser()
                .verifyWith(SECRET_KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(SECRET_KEY)
                    .build()
                    .parseSignedClaims(token);
            //System.out.println("Token VALIDAN za: " + getUsernameFromToken(token));
            return true;
        } catch (Exception e) {
            System.err.println("Token INVALID: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }

    // Bonus: dohvati role iz tokena
    public String getRoleFromToken(String token) {
        return Jwts.parser()
                .verifyWith(SECRET_KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role", String.class);
    }
}
