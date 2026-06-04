package ai.inquery.server.web.start.config.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web project configuration
 * 
 * Note: Authentication and authorization are now handled by Spring Security.
 * See SecurityConfig and JwtAuthenticationFilter.
 *
 */
@Configuration
@Slf4j
public class InqueryWebMvcConfigurer implements WebMvcConfigurer {

    // Authentication interceptors have been moved to Spring Security.
    // This class can be used for other WebMvc configurations if needed.
    // 
    // - JWT Authentication: JwtAuthenticationFilter
    // - Authorization rules: SecurityConfig
    // - DB session management: JwtAuthenticationFilter (Dbutils.setSession/removeSession)
    // - Context management: JwtAuthenticationFilter (ContextUtils)
}
