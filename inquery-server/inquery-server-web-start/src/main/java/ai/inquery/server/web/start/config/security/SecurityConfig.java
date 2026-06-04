package ai.inquery.server.web.start.config.security;

import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.common.util.I18nUtils;
import com.alibaba.fastjson2.JSON;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.DispatcherType;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Spring Security Configuration
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Allowed CORS origins from configuration.
     * Default "*" allows all origins (development).
     * In production, set CORS_ALLOWED_ORIGINS env var (e.g., "https://inquery.ai,https://www.inquery.ai").
     */
    @org.springframework.beans.factory.annotation.Value("${inquery.cors.allowed-origins:*}")
    private String allowedOrigins;

    /**
     * Paths that are publicly accessible (standard patterns)
     */
    private static final String[] PUBLIC_PATHS = {
            "/favicon.ico",
            "/error",
            "/static/**",
            "/api/system",
            "/api/system/**",
            "/api/common/**",
            "/api/oauth/login_a",
            "/api/oauth/user_a",
            "/api/oauth/logout_a",
            "/login",
            "/login.html",
            "/register.html",
            "/api/ai/deep-research/**",
            "/api/v1/export/**",
            "/api/dashboard/public/**",
            // OAuth provider redirect callbacks: the provider redirects the browser here
            // without our JWT, so auth can't be enforced. The user is resolved from the
            // server-side `state` → pending mapping (CSRF-protected) instead.
            "/api/config/ai/google/oauth/callback",
            "/api/config/ai/outlook/oauth/callback"
    };

    /**
     * Ant-style patterns for paths ending with -a or _a (public APIs)
     * These require AntPathRequestMatcher because PathPattern doesn't support ** followed by other segments
     */
    private static final String[] PUBLIC_SUFFIX_PATTERNS = {
            "/**/*-a",
            "/**/*_a"
    };

    /**
     * Admin-only paths
     */
    private static final String[] ADMIN_PATHS = {
            "/api/admin/**",
            "/admin/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Create request matchers for suffix patterns using AntPathRequestMatcher
        RequestMatcher suffixMatcher = new OrRequestMatcher(
                Stream.of(PUBLIC_SUFFIX_PATTERNS)
                        .map(AntPathRequestMatcher::new)
                        .toArray(RequestMatcher[]::new)
        );

        http
                // Disable CSRF for stateless API
                .csrf(AbstractHttpConfigurer::disable)
                
                // Enable CORS
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                
                // Stateless session (JWT-based)
                .sessionManagement(session -> 
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                
                // Authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Allow async dispatches (SSE streaming) - already authenticated on initial request
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(suffixMatcher).permitAll()
                        .requestMatchers(ADMIN_PATHS).hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                
                // Add JWT filter before UsernamePasswordAuthenticationFilter
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                
                // Exception handling
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setStatus(HttpServletResponse.SC_OK);
                            response.getWriter().write(JSON.toJSONString(
                                    ActionResult.fail("common.needLoggedIn", 
                                            I18nUtils.getMessage("common.needLoggedIn"), "")));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.getWriter().write(JSON.toJSONString(
                                    ActionResult.fail("common.accessDenied", "Access denied", "")));
                        })
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Parse allowed origins from config (comma-separated)
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (origins.size() == 1 && origins.get(0).equals("*")) {
            // Development mode: allow all origins
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            // Production mode: only allow specified origins
            configuration.setAllowedOrigins(origins);
        }

        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(Arrays.asList("INQUERY", "Authorization", "Content-Disposition"));
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
