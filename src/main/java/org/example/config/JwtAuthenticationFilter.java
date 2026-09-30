package org.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.commonmanager.util.JwtUtil;
import org.example.footballmanager.newLogic.service.PresenceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final PresenceRegistry presence;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   UserDetailsService userDetailsService,
                                   PresenceRegistry presence) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.presence = presence;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        if (path.startsWith("/js/")
                || path.startsWith("/css/")
                || path.startsWith("/newLogic/")
                || path.startsWith("/images/")
                || path.startsWith("/zox/")
                || path.endsWith(".html")) {
            filterChain.doFilter(request, response);
            return;
        }
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String jwt = authHeader.substring(7);
        String username;
        try {
            username = jwtUtil.getUsernameFromToken(jwt);
        } catch (Exception ex) {
            log.warn("[JWT] Failed to extract username from token for {} {}: {}", request.getMethod(), path, ex.getMessage());
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                if (jwtUtil.validateToken(jwt)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    // A successful authentication is the one moment every authenticated request passes
                    // through, which makes it the only honest place to answer "is anyone online".
                    // Throttled to a write a minute per account inside the registry — the SPA polls the
                    // clock, so this is otherwise a database write per request.
                    presence.markSeen(username);
                    log.debug("[JWT] Authenticated user '{}' for {} {}", username, request.getMethod(), path);
                } else {
                    log.warn("[JWT] Token validation failed for user '{}' on {} {}", username, request.getMethod(), path);
                }
            } catch (UsernameNotFoundException ex) {
                log.warn("[JWT] User '{}' not found in DB for {} {}", username, request.getMethod(), path);
                SecurityContextHolder.clearContext();
            } catch (Exception ex) {
                log.warn("[JWT] Error authenticating user '{}' for {} {}: {}", username, request.getMethod(), path, ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
