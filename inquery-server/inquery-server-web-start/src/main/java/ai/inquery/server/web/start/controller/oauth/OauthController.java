package ai.inquery.server.web.start.controller.oauth;

import ai.inquery.server.domain.api.enums.RoleCodeEnum;
import ai.inquery.server.domain.api.enums.ValidStatusEnum;
import ai.inquery.server.domain.api.model.User;
import ai.inquery.server.domain.api.service.UserService;
import ai.inquery.server.domain.core.cache.CacheKey;
import ai.inquery.server.domain.core.cache.MemoryCacheManage;
import ai.inquery.server.web.start.controller.oauth.request.ChangePasswordRequest;
import ai.inquery.server.web.start.controller.oauth.request.LoginRequest;
import ai.inquery.server.web.start.config.security.JwtUtils;
import ai.inquery.server.tools.base.excption.BusinessException;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.model.LoginUser;
import ai.inquery.server.tools.common.util.ContextUtils;
import cn.hutool.crypto.digest.DigestUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

/**
 * Login authorization service
 *
 */
@RestController
@RequestMapping("/api/oauth")
@Slf4j
public class OauthController {

    @Resource
    private UserService userService;

    @Resource
    private JwtUtils jwtUtils;

    /**
     * Login with username and password
     *
     * @param request
     * @param response
     * @return
     */
    @PostMapping("login_a")
    public DataResult<String> login(@Validated @RequestBody LoginRequest request, HttpServletResponse response) {
        // Query user
        User user = userService.query(request.getUserName()).getData();
        this.validateUser(user);

        // Successfully logged in without modifying the administrator password
        if (this.validateAdmin(user)) {
            String token = doLogin(user);
            response.setHeader("INQUERY", token);
            return DataResult.of(token);
        }

        if (!DigestUtil.bcryptCheck(request.getPassword(), user.getPassword())) {
            throw new BusinessException("oauth.passwordIncorrect");
        }

        String token = doLogin(user);
        response.setHeader("INQUERY", token);
        return DataResult.of(token);
    }

    private boolean validateAdmin(final @NotNull User user) {
        return RoleCodeEnum.ADMIN.getDefaultUserId().equals(user.getId()) && RoleCodeEnum.ADMIN.getPassword().equals(
                user.getPassword());
    }

    private void validateUser(final User user) {
        if (Objects.isNull(user)) {
            throw new BusinessException("oauth.userNameNotExits");
        }
        if (!ValidStatusEnum.VALID.getCode().equals(user.getStatus())) {
            throw new BusinessException("oauth.invalidUserName");
        }
        if (RoleCodeEnum.DESKTOP.getDefaultUserId().equals(user.getId())) {
            throw new BusinessException("oauth.IllegalUserName");
        }
    }

    private String doLogin(User user) {
        // Generate JWT token
        String token = jwtUtils.generateToken(user.getId());
        
        // Clear any cached user data to ensure fresh data on next request
        MemoryCacheManage.invalidate(CacheKey.getLoginUserKey(user.getId()));
        
        return token;
    }

    /**
     * Sign out
     *
     * @return
     */
    @PostMapping("logout_a")
    public ActionResult logout() {
        // Clear security context
        SecurityContextHolder.clearContext();
        
        // Clear cached user data
        LoginUser loginUser = ContextUtils.queryLoginUser();
        if (loginUser != null) {
            MemoryCacheManage.invalidate(CacheKey.getLoginUserKey(loginUser.getId()));
        }
        
        return ActionResult.isSuccess();
    }

    /**
     * user
     *
     * @return
     */
    @GetMapping("user")
    public DataResult<LoginUser> user() {
        return DataResult.of(getLoginUser());
    }

    /**
     * user
     *
     * @return
     */
    @GetMapping("user_a")
    public DataResult<LoginUser> usera() {
        return DataResult.of(getLoginUser());
    }

    private LoginUser getLoginUser() {
        return ContextUtils.queryLoginUser();
    }

    /**
     * Self change-password. The authenticated user provides their current
     * password (verified against the stored bcrypt hash) and a new password,
     * which is hashed and persisted.
     *
     * Note: this endpoint is intentionally NOT suffixed with `_a`, so it is
     * routed through {@code SecurityConfig}'s authenticated branch and an
     * unauthenticated caller will receive {@code common.needLoggedIn}.
     */
    @PostMapping("change-password")
    public ActionResult changePassword(@Validated @RequestBody ChangePasswordRequest request) {
        LoginUser loginUser = ContextUtils.queryLoginUser();
        if (Objects.isNull(loginUser)) {
            throw new BusinessException("common.needLoggedIn");
        }

        Long userId = loginUser.getId();

        // Block self-change for built-in system accounts.
        if (RoleCodeEnum.DESKTOP.getDefaultUserId().equals(userId)) {
            throw new BusinessException("oauth.IllegalUserName");
        }

        User user = userService.query(userId).getData();
        if (Objects.isNull(user)) {
            throw new BusinessException("oauth.userNameNotExits");
        }

        // Reject if the new password matches the current one.
        if (Objects.equals(request.getCurrentPassword(), request.getNewPassword())) {
            throw new BusinessException("oauth.passwordIncorrect");
        }

        // Verify the current password.
        // Special case: the built-in admin may still be running on the seeded
        // unhashed default password (see {@link RoleCodeEnum#getPassword()}).
        // In that case we accept a plain-text equality check as the "current".
        boolean currentMatches;
        if (RoleCodeEnum.ADMIN.getDefaultUserId().equals(userId)
                && RoleCodeEnum.ADMIN.getPassword().equals(user.getPassword())) {
            currentMatches = RoleCodeEnum.ADMIN.getPassword().equals(request.getCurrentPassword());
        } else {
            currentMatches = DigestUtil.bcryptCheck(request.getCurrentPassword(), user.getPassword());
        }
        if (!currentMatches) {
            throw new BusinessException("oauth.passwordIncorrect");
        }

        String newHash = DigestUtil.bcrypt(request.getNewPassword());
        userService.updatePassword(userId, newHash);

        // Invalidate any cached login-user data so subsequent requests reload it.
        MemoryCacheManage.invalidate(CacheKey.getLoginUserKey(userId));

        return ActionResult.isSuccess();
    }
}
