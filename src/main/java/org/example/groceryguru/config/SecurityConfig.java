package org.example.groceryguru.config;

import org.example.groceryguru.security.JwtFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    // comma-separated; prod sets CORS_ALLOWED_ORIGINS, defaults cover local dev
    @org.springframework.beans.factory.annotation.Value(
            "${cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:8081,http://localhost:8082}")
    private String allowedOrigins;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()

                // maintenance endpoints (scraping, cache builds, bulk rewrites):
                // GETs for convenience but they mutate state, so ADMIN only;
                // must come before the broad GET permitAll (first match wins)
                .requestMatchers(HttpMethod.GET,
                        "/api/products/fetch-images",
                        "/api/products/fetch-images-smart",
                        "/api/products/fetch-store-images",
                        "/api/products/cleanup-images",
                        "/api/products/cleanup-name-search-images",
                        "/api/products/cleanup-by-url",
                        "/api/products/cleanup-shared-images",
                        "/api/products/reset-image-search",
                        "/api/products/build-cjenoteka-cache",
                        "/api/products/match-images-local",
                        "/api/products/normalize-names",
                        "/api/products/repair-encoding",
                        "/api/products/unify-names",
                        "/api/products/improve-readability",
                        "/api/products/fix-encoding").hasRole("ADMIN")
                // read-only diagnostics
                .requestMatchers(HttpMethod.GET,
                        "/api/products/image-stats",
                        "/api/products/image-progress",
                        "/api/products/cjenoteka-cache-stats",
                        "/api/products/preview-image-matches",
                        "/api/products/preview-normalized-names").authenticated()

                .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/prices/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/stores/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/store-chains/**").permitAll()

                .requestMatchers(HttpMethod.POST, "/api/products/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/products/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/prices/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/stores/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/stores/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/stores/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/store-chains/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")

                // User management: listing all users and raw user creation
                // (which bypasses password encoding in /api/auth/register)
                // are admin-only. Per-user mutations are guarded in
                // UserController via self-or-admin checks.
                .requestMatchers(HttpMethod.GET, "/api/users").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/users").hasRole("ADMIN")

                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split("\\s*,\\s*")));
        config.setAllowedOriginPatterns(List.of("https://*.ngrok-free.app", "https://*.ngrok.io"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        return new UrlBasedCorsConfigurationSource() {{
            registerCorsConfiguration("/api/**", config);
        }};
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
