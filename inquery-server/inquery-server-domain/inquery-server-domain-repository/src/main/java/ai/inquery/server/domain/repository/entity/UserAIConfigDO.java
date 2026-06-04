package ai.inquery.server.domain.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * User AI integration configuration
 * </p>
 *
 * @since 2025-01-27
 */
@Getter
@Setter
@TableName("user_ai_config")
public class UserAIConfigDO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Primary key
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Creation time
     */
    private Date gmtCreate;

    /**
     * Modified time
     */
    private Date gmtModified;

    /**
     * User ID
     */
    private Long userId;

    /**
     * Confluence base URL
     */
    private String confluenceBaseUrl;

    /**
     * Confluence username
     */
    private String confluenceUsername;

    /**
     * Confluence API token
     */
    private String confluenceApiToken;

    /**
     * JIRA base URL
     */
    private String jiraBaseUrl;

    /**
     * JIRA username
     */
    private String jiraUsername;

    /**
     * JIRA API token
     */
    private String jiraApiToken;

    /**
     * Slack user token
     */
    private String slackUserToken;

    /**
     * Slack team ID (auto-resolved via auth.test API)
     */
    private String slackTeamId;

    /**
     * GitHub token
     */
    private String githubToken;

    /**
     * GitHub base URL
     */
    private String githubBaseUrl;

    /**
     * GitHub organization
     */
    private String githubOrganization;

    /**
     * Outlook access token
     */
    private String outlookAccessToken;

    /**
     * Outlook refresh token
     */
    private String outlookRefreshToken;

    /**
     * Outlook access token expires at (epoch millis)
     */
    private Long outlookExpiresAt;

    /**
     * Azure tenant ID for Outlook token refresh
     */
    private String outlookTenantId;

    /**
     * Azure client ID for Outlook token refresh
     */
    private String outlookClientId;

    /**
     * Azure client secret for Outlook token refresh
     */
    private String outlookClientSecret;

    /**
     * Outlook user email
     */
    private String outlookUserEmail;

    /**
     * Google OAuth client ID (BYO credentials)
     */
    private String googleClientId;

    /**
     * Google OAuth client secret (BYO credentials)
     */
    private String googleClientSecret;

    /**
     * Google access token
     */
    private String googleAccessToken;

    /**
     * Google refresh token (enables auto refresh)
     */
    private String googleRefreshToken;

    /**
     * Google access token expires at (epoch millis)
     */
    private Long googleExpiresAt;

    /**
     * Gemini model name
     */
    private String geminiModel;

    /**
     * DBT integration type: git, artifacts, cloud
     */
    private String dbtIntegrationType;

    /**
     * DBT Git repository URL
     */
    private String dbtGitRepoUrl;

    /**
     * DBT Git branch
     */
    private String dbtGitBranch;

    /**
     * Path to dbt project inside repository
     */
    private String dbtProjectPath;

    /**
     * DBT Git access token
     */
    private String dbtGitToken;

    /**
     * DBT manifest.json URL
     */
    private String dbtManifestUrl;

    /**
     * DBT catalog.json URL
     */
    private String dbtCatalogUrl;

    /**
     * Token for DBT artifact URLs
     */
    private String dbtArtifactToken;

    /**
     * DBT Cloud base URL
     */
    private String dbtCloudBaseUrl;

    /**
     * DBT Cloud account ID
     */
    private String dbtCloudAccountId;

    /**
     * DBT Cloud project ID
     */
    private String dbtCloudProjectId;

    /**
     * DBT Cloud environment ID
     */
    private String dbtCloudEnvironmentId;

    /**
     * DBT Cloud job ID
     */
    private String dbtCloudJobId;

    /**
     * DBT Cloud API token
     */
    private String dbtCloudApiToken;
}







