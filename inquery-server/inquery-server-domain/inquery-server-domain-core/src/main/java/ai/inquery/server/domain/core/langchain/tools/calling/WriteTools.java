package ai.inquery.server.domain.core.langchain.tools.calling;

import ai.inquery.server.domain.api.param.UserAIConfigSaveParam;
import ai.inquery.server.domain.core.langchain.InqueryAgentService;
import ai.inquery.server.domain.core.langchain.tools.ToolApprovalCallback;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * External-service write tools exposed to the top-level LLM agent.
 *
 * <p>Each tool delegates to the existing
 * {@link InqueryAgentService#processWithConfluenceWrite},
 * {@link InqueryAgentService#processWithSlackWrite},
 * {@link InqueryAgentService#processWithJiraWrite} which already own the
 * "extract structured fields → request approval → call REST API" flow.
 *
 * <p>The inner LLM call in those methods is structured-output (title/body/etc),
 * fast, and reuses the same user model name. The outer agent treats each write
 * as a single tool invocation.
 *
 * <p>Approval semantics are preserved end-to-end: the caller passes a
 * {@link ToolApprovalCallback} which surfaces the same approval UI events the
 * old per-service branches used.
 */
@Slf4j
public class WriteTools {

    private final InqueryAgentService inqueryAgentService;
    private final UserAIConfigSaveParam userConfig;
    private final String modelName;
    private final String conversationHistory;
    private final ToolApprovalCallback approvalCallback;
    private final Consumer<String> progressCallback;

    public WriteTools(InqueryAgentService inqueryAgentService,
                      UserAIConfigSaveParam userConfig,
                      String modelName,
                      String conversationHistory,
                      ToolApprovalCallback approvalCallback,
                      Consumer<String> progressCallback) {
        this.inqueryAgentService = inqueryAgentService;
        this.userConfig = userConfig;
        this.modelName = modelName;
        this.conversationHistory = conversationHistory;
        this.approvalCallback = approvalCallback;
        this.progressCallback = progressCallback;
    }

    @Tool("Post a message to Slack (requires user approval).")
    public String postSlackMessage(
            @P("Natural-language description of the message to post, include channel hint if known") String intent
    ) {
        log.info("[WriteTools] postSlackMessage invoked");
        emitProgress("write.slack");
        String result = inqueryAgentService.processWithSlackWrite(
                modelName, intent, userConfig, conversationHistory, approvalCallback);
        return result != null ? result : "Slack write was not completed.";
    }

    @Tool("Create a new Confluence wiki page (requires user approval).")
    public String createConfluencePage(
            @P("Natural-language description of the page to create") String intent
    ) {
        log.info("[WriteTools] createConfluencePage invoked");
        emitProgress("write.confluence");
        String result = inqueryAgentService.processWithConfluenceWrite(
                modelName, intent, userConfig, conversationHistory, approvalCallback);
        return result != null ? result : "Confluence write was not completed.";
    }

    @Tool("Create a new Jira issue (requires user approval).")
    public String createJiraIssue(
            @P("Natural-language description of the issue to create") String intent
    ) {
        log.info("[WriteTools] createJiraIssue invoked");
        emitProgress("write.jira");
        String result = inqueryAgentService.processWithJiraWrite(
                modelName, intent, userConfig, conversationHistory, approvalCallback);
        return result != null ? result : "Jira write was not completed.";
    }

    private void emitProgress(String key) {
        if (progressCallback == null || key == null || key.isBlank()) return;
        try {
            progressCallback.accept(key);
        } catch (Exception e) {
            log.debug("[WriteTools] progress callback failed: {}", e.getMessage());
        }
    }
}
