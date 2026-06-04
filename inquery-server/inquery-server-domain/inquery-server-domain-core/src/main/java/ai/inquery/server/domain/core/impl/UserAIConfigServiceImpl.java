package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.api.service.UserAIConfigService;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.UserAIConfigDO;
import ai.inquery.server.domain.repository.mapper.UserAIConfigMapper;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.common.util.ContextUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * User AI config service implementation
 *
 * @since 2025-01-27
 */
@Slf4j
@Service
public class UserAIConfigServiceImpl implements UserAIConfigService {

    private UserAIConfigMapper getMapper() {
        return Dbutils.getMapper(UserAIConfigMapper.class);
    }

    @Override
    public DataResult<UserAIConfigSaveParam> getConfig() {
        Long userId = ContextUtils.getUserId();
        
        LambdaQueryWrapper<UserAIConfigDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAIConfigDO::getUserId, userId);
        UserAIConfigDO configDO = getMapper().selectOne(wrapper);
        
        if (configDO == null) {
            return DataResult.of(new UserAIConfigSaveParam());
        }
        
        UserAIConfigSaveParam param = new UserAIConfigSaveParam();
        BeanUtils.copyProperties(configDO, param);
        // Security: never expose OAuth tokens to frontend
        param.setOutlookAccessToken(null);
        param.setOutlookRefreshToken(null);
        param.setOutlookExpiresAt(null);
        // Optional: do not expose client secret either
        param.setOutlookClientSecret(null);
        // Derived status flag for UI
        boolean connected = StringUtils.isNotBlank(configDO.getOutlookRefreshToken())
            && StringUtils.isNotBlank(configDO.getOutlookTenantId())
            && StringUtils.isNotBlank(configDO.getOutlookClientId());
        param.setOutlookConnected(connected);
        // Security: never expose Google OAuth tokens / secret to frontend
        param.setGoogleAccessToken(null);
        param.setGoogleRefreshToken(null);
        param.setGoogleExpiresAt(null);
        param.setGoogleClientSecret(null);
        boolean googleConnected = StringUtils.isNotBlank(configDO.getGoogleRefreshToken())
            && StringUtils.isNotBlank(configDO.getGoogleClientId());
        param.setGoogleConnected(googleConnected);
        return DataResult.of(param);
    }

    @Override
    public ActionResult saveConfig(UserAIConfigSaveParam param) {
        Long userId = ContextUtils.getUserId();
        if (userId == null) {
            return ActionResult.fail("User not found.", "User not found.", "USER_NOT_FOUND");
        }
        
        log.info("Saving AI config for user: {}", userId);

        // Auto-resolve Slack team ID when token is provided
        if (StringUtils.isNotBlank(param.getSlackUserToken())) {
            String teamId = resolveSlackTeamId(param.getSlackUserToken());
            if (teamId != null) {
                param.setSlackTeamId(teamId);
                log.info("Resolved Slack team ID: {}", teamId);
            } else {
                log.warn("Failed to resolve Slack team ID from token");
            }
        } else {
            param.setSlackTeamId(null);
        }

        LambdaQueryWrapper<UserAIConfigDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAIConfigDO::getUserId, userId);
        UserAIConfigDO configDO = getMapper().selectOne(wrapper);
        
        if (configDO == null) {
            configDO = new UserAIConfigDO();
            configDO.setUserId(userId);
            // Explicitly copy all fields (including null)
            configDO.setConfluenceBaseUrl(param.getConfluenceBaseUrl());
            configDO.setConfluenceUsername(param.getConfluenceUsername());
            configDO.setConfluenceApiToken(param.getConfluenceApiToken());
            configDO.setJiraBaseUrl(param.getJiraBaseUrl());
            configDO.setJiraUsername(param.getJiraUsername());
            configDO.setJiraApiToken(param.getJiraApiToken());
            configDO.setSlackUserToken(param.getSlackUserToken());
            configDO.setSlackTeamId(param.getSlackTeamId());
            configDO.setGithubToken(param.getGithubToken());
            configDO.setGithubBaseUrl(param.getGithubBaseUrl());
            configDO.setGithubOrganization(param.getGithubOrganization());
            configDO.setOutlookTenantId(param.getOutlookTenantId());
            configDO.setOutlookClientId(param.getOutlookClientId());
            configDO.setOutlookClientSecret(param.getOutlookClientSecret());
            configDO.setOutlookUserEmail(param.getOutlookUserEmail());
            copyGoogleConfig(param, configDO);
            configDO.setGeminiModel(param.getGeminiModel());
            copyDbtConfig(param, configDO);
            getMapper().insert(configDO);
            log.info("Inserted new AI config for user: {}", userId);
        } else {
            // Explicitly copy all fields (including null)
            configDO.setConfluenceBaseUrl(param.getConfluenceBaseUrl());
            configDO.setConfluenceUsername(param.getConfluenceUsername());
            configDO.setConfluenceApiToken(param.getConfluenceApiToken());
            configDO.setJiraBaseUrl(param.getJiraBaseUrl());
            configDO.setJiraUsername(param.getJiraUsername());
            configDO.setJiraApiToken(param.getJiraApiToken());
            configDO.setSlackUserToken(param.getSlackUserToken());
            configDO.setSlackTeamId(param.getSlackTeamId());
            configDO.setGithubToken(param.getGithubToken());
            configDO.setGithubBaseUrl(param.getGithubBaseUrl());
            configDO.setGithubOrganization(param.getGithubOrganization());
            configDO.setOutlookTenantId(param.getOutlookTenantId());
            configDO.setOutlookClientId(param.getOutlookClientId());
            configDO.setOutlookClientSecret(param.getOutlookClientSecret());
            configDO.setOutlookUserEmail(param.getOutlookUserEmail());
            copyGoogleConfig(param, configDO);
            configDO.setGeminiModel(param.getGeminiModel());
            copyDbtConfig(param, configDO);
            getMapper().updateById(configDO);
            log.info("Updated AI config for user: {}", userId);
        }
        
        return ActionResult.isSuccess();
    }

    /**
     * Resolve Slack team ID by calling Slack auth.test API.
     */
    private String resolveSlackTeamId(String slackToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://slack.com/api/auth.test"))
                    .header("Authorization", "Bearer " + slackToken)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = new ObjectMapper().readTree(response.body());
            if (json.path("ok").asBoolean()) {
                return json.path("team_id").asText(null);
            }
            log.warn("Slack auth.test failed: {}", json.path("error").asText());
        } catch (Exception e) {
            log.warn("Failed to call Slack auth.test: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public UserAIConfigSaveParam getConfigInternal() {
        Long userId = ContextUtils.getUserId();
        if (userId == null) return null;

        LambdaQueryWrapper<UserAIConfigDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAIConfigDO::getUserId, userId);
        UserAIConfigDO configDO = getMapper().selectOne(wrapper);
        if (configDO == null) return null;

        UserAIConfigSaveParam param = new UserAIConfigSaveParam();
        BeanUtils.copyProperties(configDO, param);
        return param;
    }

    /**
     * Copy Google BYO OAuth client config. OAuth tokens (access/refresh/expires) are
     * managed exclusively by the OAuth callback and must NOT be touched here, otherwise
     * a plain settings save would wipe the connection. The client secret is only
     * overwritten when a non-blank value is provided (it is masked on read).
     */
    private void copyGoogleConfig(UserAIConfigSaveParam param, UserAIConfigDO configDO) {
        configDO.setGoogleClientId(param.getGoogleClientId());
        if (StringUtils.isNotBlank(param.getGoogleClientSecret())) {
            configDO.setGoogleClientSecret(param.getGoogleClientSecret());
        }
    }

    private void copyDbtConfig(UserAIConfigSaveParam param, UserAIConfigDO configDO) {
        configDO.setDbtIntegrationType(param.getDbtIntegrationType());
        configDO.setDbtGitRepoUrl(param.getDbtGitRepoUrl());
        configDO.setDbtGitBranch(param.getDbtGitBranch());
        configDO.setDbtProjectPath(param.getDbtProjectPath());
        configDO.setDbtGitToken(param.getDbtGitToken());
        configDO.setDbtManifestUrl(param.getDbtManifestUrl());
        configDO.setDbtCatalogUrl(param.getDbtCatalogUrl());
        configDO.setDbtArtifactToken(param.getDbtArtifactToken());
        configDO.setDbtCloudBaseUrl(param.getDbtCloudBaseUrl());
        configDO.setDbtCloudAccountId(param.getDbtCloudAccountId());
        configDO.setDbtCloudProjectId(param.getDbtCloudProjectId());
        configDO.setDbtCloudEnvironmentId(param.getDbtCloudEnvironmentId());
        configDO.setDbtCloudJobId(param.getDbtCloudJobId());
        configDO.setDbtCloudApiToken(param.getDbtCloudApiToken());
    }
}

