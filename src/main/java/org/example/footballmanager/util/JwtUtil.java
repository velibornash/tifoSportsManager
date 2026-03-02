package org.example.footballmanager.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.example.footballmanager.model.User;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {

    // Jak ključ (min 256 bitova za HS256/HS384/HS512)
    // U produkciji: čitaj iz application.properties ili env varijable
    private static final String SECRET_STRING = "VeljaTestSecretKeyVeryLongAndSecure12345678901234567890";
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET_STRING.getBytes());

    private static final long EXPIRATION_MS = 86400000; // 1 dan

    public String generateToken(User user) {
        return Jwts.builder()
                .setSubject(user.getUsername())
                .claim("role", user.getRole().name())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(SECRET_KEY) // novi stil – JJWT sam bira algoritam po dužini ključa
                .compact();
    }

    public String getUsernameFromToken(String token) {
        // Ispravan stil za 0.13.0: Jwts.parser() vraća JwtParserBuilder
        return Jwts.parser()
                .setSigningKey(SECRET_KEY)
                .build()                  // ← KLJUČNO: .build() vraća JwtParser
                .parseClaimsJws(token)    // ← sad radi
                .getBody()
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
        return (String) Jwts.parser()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .get("role");
    }
}