package com.orochiverse.platform.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.orochiverse.platform.common.security.auth.JsonAccessDeniedHandler;
import com.orochiverse.platform.common.security.auth.JsonAuthenticationEntryPoint;
import com.orochiverse.platform.common.security.auth.JwtAuthenticationFilter;
import com.orochiverse.platform.common.security.jwt.AccessTokenVerifier;

/**
 * Phase 1.6 filter chain. Replaces the open-by-default Phase 1.5 baseline
 * with: <em>verify a JWT or be denied</em>.
 *
 * <h2>Public endpoints</h2>
 * <ul>
 *   <li>{@code /.well-known/jwks.json} — public-key distribution.</li>
 *   <li>{@code /actuator/health/**}, {@code /actuator/info},
 *       {@code /actuator/prometheus}, {@code /actuator/metrics/**} —
 *       infra/monitoring. Phase 1.10 may lock these down behind network
 *       policy; for now they're open so the dev box and Prometheus scrape
 *       work without bearer credentials.</li>
 *   <li>OpenAPI / Swagger — convenience during M1 development; gate or
 *       remove in prod via Traefik later.</li>
 *   <li>{@code /api/auth/login}, {@code /api/auth/refresh},
 *       {@code /api/auth/forgot-password}, {@code /api/auth/reset-password}
 *       — credential-bearing entry points; auth happens in the controller
 *       (Phase 1.7) rather than via a bearer token.</li>
 * </ul>
 *
 * <h2>Everything else</h2>
 * Requires a valid JWT. {@link JwtAuthenticationFilter} sits ahead of
 * {@link UsernamePasswordAuthenticationFilter} so the SecurityContext is
 * populated <em>before</em> Spring's authorization check runs.
 *
 * <h2>{@code @EnableMethodSecurity}</h2>
 * Activated so {@code @PreAuthorize("hasRole('OPERATOR_ADMIN')")} works on
 * controllers and services. {@link com.orochiverse.platform.common.security.auth.AuthorityResolver}
 * emits the matching {@code ROLE_*} authorities from the JWT claims.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint(ObjectMapper mapper) {
        return new JsonAuthenticationEntryPoint(mapper);
    }

    @Bean
    JsonAccessDeniedHandler jsonAccessDeniedHandler(ObjectMapper mapper) {
        return new JsonAccessDeniedHandler(mapper);
    }

    @Bean
    JwtAuthenticationFilter jwtAuthenticationFilter(AccessTokenVerifier verifier) {
        return new JwtAuthenticationFilter(verifier);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    JwtAuthenticationFilter jwtFilter,
                                    JsonAuthenticationEntryPoint entryPoint,
                                    JsonAccessDeniedHandler deniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/.well-known/jwks.json").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
