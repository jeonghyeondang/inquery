package ai.inquery.server.web.start.config.interceptor;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * CORS filter that respects the configured allowed origins.
 * In development ("*"), all origins are allowed.
 * In production, only origins listed in CORS_ALLOWED_ORIGINS are accepted.
 */
@Component
public class CorsFilter implements Filter {

    @Value("${inquery.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
        throws IOException, ServletException {
        HttpServletResponse response = (HttpServletResponse)res;
        HttpServletRequest request = (HttpServletRequest)req;

        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && isOriginAllowed(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Allow-Methods", "POST, GET, PUT, OPTIONS, DELETE, PATCH");
            response.setHeader("Access-Control-Max-Age", "3600");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, uid, Authorization, INQUERY");
        }

        chain.doFilter(req, res);
    }

    private boolean isOriginAllowed(String origin) {
        if ("*".equals(allowedOrigins.trim())) {
            return true; // Development: allow all
        }
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .toList();
        return origins.contains(origin);
    }

}
