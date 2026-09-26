package org.example.config;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.commonmanager.dto.ApiErrorResponseDTO;
import org.example.commonmanager.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.LocalDateTime;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepo) {
        return username -> userRepo.findByUsername(username)
                .or(() -> userRepo.findByEmail(username))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(UserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(encoder);
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(req -> req
                        // ── Public: static assets, landing pages and auth ──
                        // Everything else requires a JWT. The previous list also contained
                        // "/teams/**", "/players/**", "/matches/**", "/training/**",
                        // "/countries/**", "/commonmanager/**", "/api/**" and
                        // "/proposal/api/**", which left the entire game API readable and
                        // mutable by anyone. For a multiplayer game that means opponents could
                        // read squads, rewrite lineups and move money.
                        .requestMatchers(
                                "/",
                                "/.well-known/**",
                                "/favicon.ico",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/audio/**",
                                "/demo/service/ui/**",
                                // Legacy game modes. Neither reads the JWT (no Authorization
                                // header anywhere under their /js folders), so they depend entirely
                                // on permitAll. Leaving them open rather than breaking two
                                // untouched modes I cannot test; they need their own auth pass.
                                "/basketballmanager/**",
                                "/americanfootballmanager/**",
                                "/auth/**",
                                "/api/server-time",
                                "/api/game-clock",
                                "/home.html",
                                "/login.html",
                                "/register.html",
                                "/tifo.html",
                                "/simulateAllResults.html"
                        ).permitAll()

                        // ── Admin only ──
                        .requestMatchers("/admin/**").hasAnyRole("ADMIN", "OWNER", "DEV")

                        // ── Everything else is authenticated ──
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exc -> exc
                        .authenticationEntryPoint((request, response, authException) -> {
                            log.warn(
                                    "Authentication entry point for {} {} (xrw={}, accept={}, authPresent={}): {}",
                                    request.getMethod(),
                                    request.getRequestURI(),
                                    request.getHeader("X-Requested-With"),
                                    request.getHeader("Accept"),
                                    request.getHeader("Authorization") != null,
                                    authException.getMessage()
                            );
                            if (shouldReturnUnauthorized(request)) {
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication required.", request);
                                return;
                            }
                            log.warn("Redirecting unauthenticated request to /login.html for {} {}", request.getMethod(), request.getRequestURI());
                            response.sendRedirect("/login.html");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            log.warn(
                                    "Access denied for {} {} (xrw={}, accept={}, authPresent={}): {}",
                                    request.getMethod(),
                                    request.getRequestURI(),
                                    request.getHeader("X-Requested-With"),
                                    request.getHeader("Accept"),
                                    request.getHeader("Authorization") != null,
                                    accessDeniedException.getMessage()
                            );
                            if (shouldReturnUnauthorized(request)) {
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Access denied.", request);
                                return;
                            }
                            log.warn("Redirecting forbidden request to /login.html for {} {}", request.getMethod(), request.getRequestURI());
                            response.sendRedirect("/login.html");
                        })
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private boolean shouldReturnUnauthorized(HttpServletRequest request) {
        String requestedWith = request.getHeader("X-Requested-With");
        String accept = request.getHeader("Accept");
        String uri = request.getRequestURI();
        // Any API path must answer with a JSON error, never a 302 to the HTML login page.
        // A fetch() that follows the redirect gets HTML, response.json() throws, and the SPA
        // renders a generic "API Error" card instead of "please log in" - which is how a dozen
        // dead routes ended up reporting a misleading error. This previously only matched
        // "/api/", so /proposal/api/** still redirected.
        boolean isApiPath = uri.contains("/api/") || uri.endsWith("/api");
        return "XMLHttpRequest".equalsIgnoreCase(requestedWith)
                || request.getHeader("Authorization") != null
                || !"GET".equalsIgnoreCase(request.getMethod())
                || isApiPath
                || (accept != null && accept.contains("application/json"));
    }

    private void writeJsonError(HttpServletResponse response,
                                int status,
                                String code,
                                String message,
                                HttpServletRequest request) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiErrorResponseDTO body = new ApiErrorResponseDTO(
                status,
                code,
                message,
                request != null ? request.getRequestURI() : null,
                LocalDateTime.now()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
