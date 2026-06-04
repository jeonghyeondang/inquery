package ai.inquery.server.domain.api.param;

import lombok.Data;

import java.io.Serializable;

/**
 * User AI config save param
 */
@Data
public class UserAIConfigSaveParam implements Serializable {

    private static final long serialVersionUID = 1L;

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
     * Outlook refresh token (optional, enables auto refresh)
     */
    private String outlookRefreshToken;

    /**
     * Outlook access token expires at (epoch millis, optional)
     */
    private Long outlookExpiresAt;

    /**
     * Azure tenant ID for Outlook token refresh (optional)
     */
    private String outlookTenantId;

    /**
     * Azure client ID for Outlook token refresh (optional)
     */
    private String outlookClientId;

    /**
     * Azure client secret for Outlook token refresh (optional)
     */
    private String outlookClientSecret;

    /**
     * Whether Outlook is connected (derived field)
     */
    private Boolean outlookConnected;

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
     * Whether Google Drive is connected (derived field)
     */
    private Boolean googleConnected;

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







