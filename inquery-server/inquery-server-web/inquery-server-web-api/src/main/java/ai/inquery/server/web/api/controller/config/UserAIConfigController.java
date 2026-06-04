package ai.inquery.server.web.api.controller.config;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.api.service.UserAIConfigService;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.web.api.aspect.ConnectionInfoAspect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * User AI config controller
 *
 * @since 2025-01-27
 */
@Slf4j
@ConnectionInfoAspect
@RequestMapping("/api/config/ai")
@RestController
public class UserAIConfigController {

    @Autowired
    private UserAIConfigService userAIConfigService;

    /**
     * Get user AI config
     *
     * @return user AI config
     */
    @GetMapping
    public DataResult<UserAIConfigSaveParam> getConfig() {
        return userAIConfigService.getConfig();
    }

    /**
     * Save or update user AI config
     *
     * @param param config save param
     * @return action result
     */
    @PostMapping
    public ActionResult saveConfig(@RequestBody UserAIConfigSaveParam param) {
        return userAIConfigService.saveConfig(param);
    }
}


