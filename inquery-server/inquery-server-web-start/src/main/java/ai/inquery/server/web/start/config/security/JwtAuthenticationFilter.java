package ai.inquery.server.web.start.config.security;

import ai.inquery.server.domain.api.enums.RoleCodeEnum;
import ai.inquery.server.domain.api.enums.ValidStatusEnum;
import ai.inquery.server.domain.api.model.User;
import ai.inquery.server.domain.api.service.UserService;
import ai.inquery.server.domain.core.cache.CacheKey;
import ai.inquery.server.domain.core.cache.MemoryCacheManage;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.tools.common.config.InqueryProperties;
import ai.inquery.server.tools.common.enums.ModeEnum;
import ai.inquery.server.tools.common.model.Context;
import ai.inquery.server.tools.common.model.LoginUser;
import ai.inquery.server.tools.common.util.ContextUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * JWT Authentication Filter
 * Validates JWT token and sets up Spring Security context
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "INQUERY";
    private static final String TOKEN_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final UserService userService;
    private final InqueryProperties inqueryProperties;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // Skip JWT re-authentication on async dispatches (SSE streaming)
        // The initial request was already authenticated
        return true;
    }

    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {
        try {
            // Skip OPTIONS requests (CORS preflight)
            if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                filterChain.doFilter(request, response);
                return;
            }

            // Set DB session for all requests
            Dbutils.setSession();

            // Extract token from header or query parameter
            String token = extractToken(request);

            Long userId = null;
            if (StringUtils.isNotBlank(token)) {
                userId = jwtUtils.validateTokenAndGetUserId(token);
            }

            if (userId != null) {
                setupSecurityContext(userId, token);
            }

            filterChain.doFilter(request, response);
        } finally {
            // Cleanup
            ContextUtils.removeContext();
            Dbutils.removeSession();
            SecurityContextHolder.clearContext();
        }
    }

    private String extractToken(HttpServletRequest request) {
        // Try INQUERY header first
        String headerToken = request.getHeader(TOKEN_HEADER);
        if (StringUtils.isNotBlank(headerToken)) {
            if (headerToken.startsWith(TOKEN_PREFIX)) {
                return headerToken.substring(TOKEN_PREFIX.length());
            }
            return headerToken;
        }

        // Try Authorization header
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.isNotBlank(authHeader) && authHeader.startsWith(TOKEN_PREFIX)) {
            return authHeader.substring(TOKEN_PREFIX.length());
        }

        // Legacy: try 'satoken' header (used by frontend fetch calls)
        String satokenHeader = request.getHeader("satoken");
        if (StringUtils.isNotBlank(satokenHeader)) {
            return satokenHeader;
        }

        // Try query parameter (for SSE requests)
        String queryToken = request.getParameter("token");
        if (StringUtils.isNotBlank(queryToken)) {
            return queryToken;
        }

        // Legacy: try 'satoken' parameter
        return request.getParameter("satoken");
    }

    private void setupSecurityContext(Long userId, String token) {
        Long finalUserId = userId;
        LoginUser loginUser = MemoryCacheManage.computeIfAbsent(CacheKey.getLoginUserKey(userId), () -> {
            User user = userService.query(finalUserId).getData();
            if (user == null) {
                return null;
            }
            if (!ValidStatusEnum.VALID.getCode().equals(user.getStatus())) {
                return null;
            }
            boolean admin = RoleCodeEnum.ADMIN.getCode().equals(user.getRoleCode());

            return LoginUser.builder()
                    .id(user.getId())
                    .nickName(user.getNickName())
                    .admin(admin)
                    .roleCode(user.getRoleCode())
                    .build();
        });

        if (loginUser == null) {
            return;
        }

        loginUser.setToken(token);

        // Set Inquery context
        ContextUtils.setContext(Context.builder()
                .loginUser(loginUser)
                .build());

        // Set Spring Security context
        List<SimpleGrantedAuthority> authorities = loginUser.getAdmin()
                ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"))
                : Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
