package ai.inquery.server.domain.core.query;

import ai.inquery.server.domain.api.service.AIService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Classifier for Deep Research mode.
 * Determines if a question needs clarification and generates research plans.
 * Now includes schema context to generate realistic, achievable plans.
 */
@Slf4j
@Component
public class DeepResearchClassifier {

    @Autowired
    private AIService aiService;

    @Autowired
    private SchemaSearcher schemaSearcher;

    /**
     * Classify a Deep Research query and generate a research plan.
     *
     * @param query User's question
     * @param model LLM model to use
     * @return Classification result with research plan or clarification request
     */
    public DeepResearchClassification classify(String query, String model) {
        return classify(query, model, null, null);
    }

    /**
     * Classify a Deep Research query with conversation history context.
     *
     * @param query User's question
     * @param model LLM model to use
     * @param conversationHistory Previous conversation for context
     * @return Classification result with research plan or clarification request
     */
    public DeepResearchClassification classify(String query, String model, String conversationHistory) {
        return classify(query, model, conversationHistory, null);
    }

    /**
     * Classify a Deep Research query with schema context.
     * This ensures the generated plan is based on actually available data.
     *
     * @param query User's question
     * @param model LLM model to use
     * @param conversationHistory Previous conversation for context
     * @param schemaContext Available database schema (tables and columns)
     * @return Classification result with research plan or clarification request
     */
    public DeepResearchClassification classify(String query, String model, String conversationHistory, String schemaContext) {
        return classify(query, model, conversationHistory, schemaContext, null);
    }

    public DeepResearchClassification classify(String query, String model, String conversationHistory, String schemaContext, List<String> excludedTables) {
        return classify(query, model, conversationHistory, schemaContext, excludedTables, null);
    }

    public DeepResearchClassification classify(String query, String model, String conversationHistory, String schemaContext, List<String> excludedTables, String businessContext) {
        log.info("Classifying Deep Research query: {}", query);

        DeepResearchClassification result = new DeepResearchClassification();
        result.setOriginalQuery(query);

        if (query == null || query.trim().isEmpty()) {
            result.setNeedsClarification(true);
            result.setClarificationQuestion("Please enter a question for deep research analysis.");
            return result;
        }

        // If no schema context provided, try to search for relevant schema
        String effectiveSchemaContext = schemaContext;
        if (effectiveSchemaContext == null || effectiveSchemaContext.trim().isEmpty()) {
            try {
                List<String> schemas = (excludedTables != null && !excludedTables.isEmpty())
                    ? schemaSearcher.searchSchema(query, excludedTables, 30)
                    : schemaSearcher.searchSchema(query, null, 30);
                if (schemas != null && !schemas.isEmpty()) {
                    effectiveSchemaContext = schemas.stream()
                            .collect(Collectors.joining("\n---\n"));
                }
            } catch (Exception e) {
                log.warn("Schema search failed during classification: {}", e.getMessage());
            }
        }

        try {
            String prompt = buildClassificationPrompt(query, conversationHistory, effectiveSchemaContext, businessContext);
            String response = aiService.generate(prompt, model);
            parseClassificationResponse(response, result);

            log.info("Deep Research classification result: needsClarification={}, planStepsCount={}, requiresWebSearch={}",
                    result.isNeedsClarification(),
                    result.getResearchPlan() != null ? result.getResearchPlan().getSteps().size() : 0,
                    result.isRequiresWebSearch());
        } catch (Exception e) {
            log.error("Deep Research classification failed: {}", e.getMessage());
            // Default to proceeding with research
            result.setNeedsClarification(false);
            result.setResearchPlan(buildDefaultPlan(query, effectiveSchemaContext != null));
        }

        return result;
    }

    private String buildClassificationPrompt(String query, String conversationHistory, String schemaContext, String businessContext) {
        String historySection = "";
        if (conversationHistory != null && !conversationHistory.trim().isEmpty()) {
            historySection = """
            
            [CONVERSATION HISTORY]
            %s
            
            """.formatted(conversationHistory);
        }

        String businessSection = "";
        if (businessContext != null && !businessContext.trim().isEmpty()) {
            businessSection = """
            
            [BUSINESS CONTEXT]
            The following describes what this service/product is and how it operates:
            %s
            
            """.formatted(businessContext);
        }

        String schemaSection = "";
        String schemaGuidance;
        if (schemaContext != null && !schemaContext.trim().isEmpty()) {
            schemaSection = """
            
            [AVAILABLE DATABASE SCHEMA — for reference only]
            %s
            
            """.formatted(schemaContext);
            schemaGuidance = """
            - The research plan should describe WHAT to analyze, not WHICH specific tables to query
            - Do NOT mention individual table names in the plan steps — the execution engine will find the right tables automatically
            - Each step should be a meaningful analytical question (e.g., "Analyze daily active user trends" not "Query FCT_TRAFFIC table")
            - Use the schema only to confirm that the required data exists; if something is missing, set requiresWebSearch=true
            - Steps should cover different analytical angles (trends, comparisons, anomalies, root causes, etc.)
            - If BUSINESS CONTEXT is provided, use it to understand the domain and generate more relevant, business-aware research steps""";
        } else {
            schemaGuidance = """
            - No database schema available. Focus on web search and external data collection
            - Set requiresWebSearch=true since no internal data is available""";
        }

        return """
            You are analyzing a user's question for deep research analysis.
            
            This system has TWO data sources:
            1. DATABASE: Internal database with structured data (if schema is provided below)
            2. WEB SEARCH: External web search for news, trends, social media sentiment, etc.
            
            Determine:
            1. Is the question clear enough to proceed with research? Or does it need clarification?
            2. If clear, generate a research plan with 3-5 steps
            3. Determine if web search is needed (for external context, news, trends, etc.)
            4. Detect the user's language and provide appropriate button label
            
            %s%s%s
            [USER QUESTION]
            %s
            
            Respond in JSON format:
            {
                "needsClarification": boolean,
                "clarificationQuestion": "question to ask user if unclear (in user's language)" or null,
                "language": "detected language code (e.g., en, ko, ja, zh, es, fr, de, etc.)",
                "requiresWebSearch": boolean,
                "webSearchTopics": ["topic1", "topic2"] or null,
                "researchPlan": {
                    "title": "Research title summarizing the question (in user's language)",
                    "steps": [
                        {
                            "icon": "search" | "globe" | "chart" | "document" | "clock",
                            "title": "High-level analytical step (in user's language)",
                            "details": "What insight this step produces and why it matters — do NOT mention specific table names",
                            "source": "database"
                        }
                    ],
                    "estimatedTime": "Estimated completion time (in user's language)"
                },
                "buttonLabel": "Start Research button label (in user's language)",
                "editPlanLabel": "Edit Plan button label (in user's language)"
            }
            
            Guidelines:
            - needsClarification=true if: question is too vague, missing key context, or ambiguous
            - requiresWebSearch=true if: question asks about news, trends, social sentiment, external events, or data not in database
            - IMPORTANT: Detect the user's language from their question and respond ENTIRELY in that language
            - All text fields must be in user's language
            %s
            - Icons: "search" for DB data collection, "globe" for web search, "chart" for analysis, "document" for report
            - source: always set to "database" (the execution engine decides data sources automatically)
            """.formatted(historySection, businessSection, schemaSection, query, schemaGuidance);
    }

    private void parseClassificationResponse(String response, DeepResearchClassification result) {
        if (response == null || response.trim().isEmpty()) {
            throw new RuntimeException("Empty LLM response");
        }

        String jsonStr = extractJsonFromResponse(response);
        if (jsonStr == null) {
            throw new RuntimeException("No valid JSON found in response");
        }

        try {
            JSONObject json = JSON.parseObject(jsonStr);

            // Parse needsClarification
            Boolean needsClarification = json.getBoolean("needsClarification");
            result.setNeedsClarification(needsClarification != null ? needsClarification : false);

            // Parse clarificationQuestion
            String clarificationQuestion = json.getString("clarificationQuestion");
            if (clarificationQuestion != null && !clarificationQuestion.trim().isEmpty()) {
                result.setClarificationQuestion(clarificationQuestion.trim());
            }

            // Parse language
            String language = json.getString("language");
            result.setLanguage(language != null ? language : "en");

            // Parse buttonLabel
            String buttonLabel = json.getString("buttonLabel");
            result.setButtonLabel(buttonLabel != null ? buttonLabel : "Start Research");

            // Parse editPlanLabel
            String editPlanLabel = json.getString("editPlanLabel");
            result.setEditPlanLabel(editPlanLabel != null ? editPlanLabel : "Edit Plan");

            // Parse requiresWebSearch
            Boolean requiresWebSearch = json.getBoolean("requiresWebSearch");
            result.setRequiresWebSearch(requiresWebSearch != null ? requiresWebSearch : false);

            // Parse webSearchTopics
            JSONArray topicsArray = json.getJSONArray("webSearchTopics");
            if (topicsArray != null) {
                List<String> topics = new ArrayList<>();
                for (int i = 0; i < topicsArray.size(); i++) {
                    String topic = topicsArray.getString(i);
                    if (topic != null && !topic.trim().isEmpty()) {
                        topics.add(topic.trim());
                    }
                }
                result.setWebSearchTopics(topics);
            }

            // Parse researchPlan
            JSONObject planJson = json.getJSONObject("researchPlan");
            if (planJson != null) {
                ResearchPlan plan = new ResearchPlan();
                plan.setTitle(planJson.getString("title"));
                plan.setEstimatedTime(planJson.getString("estimatedTime"));

                JSONArray stepsArray = planJson.getJSONArray("steps");
                if (stepsArray != null) {
                    List<ResearchStep> steps = new ArrayList<>();
                    for (int i = 0; i < stepsArray.size(); i++) {
                        JSONObject stepJson = stepsArray.getJSONObject(i);
                        ResearchStep step = new ResearchStep();
                        step.setIcon(stepJson.getString("icon"));
                        step.setTitle(stepJson.getString("title"));
                        step.setDetails(stepJson.getString("details"));
                        step.setSource(stepJson.getString("source")); // "database", "web", or "both"
                        steps.add(step);
                    }
                    plan.setSteps(steps);
                }

                result.setResearchPlan(plan);
            }

        } catch (Exception e) {
            log.warn("Failed to parse classification response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse classification response", e);
        }
    }

    private String extractJsonFromResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        String trimmed = response.trim();

        // Case 1: Already starts with JSON object
        if (trimmed.startsWith("{")) {
            return extractJsonObject(trimmed);
        }

        // Case 2: Markdown code block
        int jsonBlockStart = trimmed.indexOf("```json");
        if (jsonBlockStart >= 0) {
            int jsonStart = jsonBlockStart + 7;
            int jsonEnd = trimmed.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return trimmed.substring(jsonStart, jsonEnd).trim();
            }
        }

        int codeBlockStart = trimmed.indexOf("```");
        if (codeBlockStart >= 0) {
            int jsonStart = codeBlockStart + 3;
            int newlineAfterStart = trimmed.indexOf("\n", jsonStart);
            if (newlineAfterStart > jsonStart && newlineAfterStart < jsonStart + 10) {
                jsonStart = newlineAfterStart + 1;
            }
            int jsonEnd = trimmed.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return trimmed.substring(jsonStart, jsonEnd).trim();
            }
        }

        // Case 3: Find first {
        int braceStart = trimmed.indexOf("{");
        if (braceStart >= 0) {
            return extractJsonObject(trimmed.substring(braceStart));
        }

        return null;
    }

    private String extractJsonObject(String str) {
        if (str == null || !str.startsWith("{")) {
            return null;
        }

        int braceCount = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        return str.substring(0, i + 1);
                    }
                }
            }
        }

        return str;
    }

    private ResearchPlan buildDefaultPlan(String query, boolean hasSchemaContext) {
        ResearchPlan plan = new ResearchPlan();
        plan.setTitle(query.length() > 50 ? query.substring(0, 50) + "..." : query);
        plan.setEstimatedTime("Completes in a few minutes");

        List<ResearchStep> steps = new ArrayList<>();

        if (hasSchemaContext) {
            // Database-focused plan
            ResearchStep step1 = new ResearchStep();
            step1.setIcon("search");
            step1.setTitle("Data Collection");
            step1.setDetails("Searching relevant data from database");
            step1.setSource("database");
            steps.add(step1);

            ResearchStep step2 = new ResearchStep();
            step2.setIcon("chart");
            step2.setTitle("Analysis");
            step2.setDetails("Analyzing collected data from multiple angles");
            step2.setSource("database");
            steps.add(step2);
        } else {
            // Web search-focused plan
            ResearchStep step1 = new ResearchStep();
            step1.setIcon("globe");
            step1.setTitle("External Research");
            step1.setDetails("Searching web for relevant information");
            step1.setSource("web");
            steps.add(step1);

            ResearchStep step2 = new ResearchStep();
            step2.setIcon("chart");
            step2.setTitle("Analysis");
            step2.setDetails("Analyzing collected information");
            step2.setSource("web");
            steps.add(step2);
        }

        ResearchStep step3 = new ResearchStep();
        step3.setIcon("document");
        step3.setTitle("Report Generation");
        step3.setDetails("Generating comprehensive research report");
        step3.setSource(hasSchemaContext ? "database" : "web");
        steps.add(step3);

        plan.setSteps(steps);
        return plan;
    }

    @Data
    public static class DeepResearchClassification {
        private String originalQuery;
        private boolean needsClarification;
        private String clarificationQuestion;
        private String language;
        private String buttonLabel;
        private String editPlanLabel;
        private boolean requiresWebSearch;
        private List<String> webSearchTopics;
        private ResearchPlan researchPlan;
    }

    @Data
    public static class ResearchPlan {
        private String title;
        private List<ResearchStep> steps;
        private String estimatedTime;
    }

    @Data
    public static class ResearchStep {
        private String icon;
        private String title;
        private String details;
        private String source; // "database", "web", or "both"
    }
}
