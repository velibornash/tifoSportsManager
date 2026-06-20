package org.example.config;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.commonmanager.dto.ApiErrorResponseDTO;
import org.example.commonmanager.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                        .requestMatchers(
                                "/",
                                "/home.html",
                                "/login.html",
                                "/register.html",
                                "/dashboard.html",
                                "/realisticDemo.html",
                                "/simulateAllResults.html",
                                "/cleanSheetTifo.html",
                                "/tifo.html",
                                "/old/**",
                                "/zox-match-preview.html",
                                "/zox/**",
                                "/api/zox/**",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/audio/**",
                                "/auth/**",
                                "/api/**",
                                "/api/clean-sheet/**",
                                "/api/zox/**",
                                "/countries/**",
                                "/start-realistic-demo",
                                "/newLogic/**",
                                "/api/v2/**",
                                "/teams/**",
                                "/players/**",
                                "/matches/**",
                                "/match-stats/**",
                                "/demo-position-updates/**",
                                "/training/**",
                                "/demo-match-events/**",
                                "/match-events/**",
                                "/basketballmanager/**",
                                "/americanfootballmanager/**",
                                "/commonmanager/**"
                        ).permitAll()

                        .requestMatchers("/admin/**").hasAnyRole("ADMIN", "OWNER", "DEV")

                        .anyRequest().authenticated()
                )
                .exceptionHandling(exc -> exc
                        .authenticationEntryPoint((request, response, authException) -> {
                            if (shouldReturnUnauthorized(request)) {
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "Authentication required.", request);
                                return;
                            }
                            response.sendRedirect("/login.html");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            if (shouldReturnUnauthorized(request)) {
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Access denied.", request);
                                return;
                            }
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
        return "XMLHttpRequest".equalsIgnoreCase(requestedWith)
                || request.getHeader("Authorization") != null
                || !"GET".equalsIgnoreCase(request.getMethod())
                || (accept != null && accept.contains("application/json"))
                || request.getRequestURI().startsWith("/api/");
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
