package ai.inquery.server.domain.api.service;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;

/**
 * User AI config service
 *
 * @since 2025-01-27
 */
public interface UserAIConfigService {

    /**
     * Get user AI config
     *
     * @return user AI config
     */
    DataResult<UserAIConfigSaveParam> getConfig();

    /**
     * Save or update user AI config
     *
     * @param param config save param
     * @return action result
     */
    ActionResult saveConfig(UserAIConfigSaveParam param);

    /**
     * Get the full config for internal use (e.g., MCP connections).
     * Contains unmasked tokens. Not for frontend exposure.
     *
     * @return config param with full tokens, or null if not configured
     */
    UserAIConfigSaveParam getConfigInternal();
}







