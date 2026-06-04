package ai.inquery.server.domain.core.query;

import ai.inquery.server.domain.api.service.AIService;
import ai.inquery.server.domain.core.langchain.ModelMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates user queries to English and augments with search keywords
 * for better Vector DB semantic search.
 */
@Slf4j
@Component
public class QueryTranslator {

    @Autowired
    private AIService aiService;

    public TranslatedQuery translate(String query) {
        return translate(query, ModelMapper.getDefaultFastModel());
    }

    public TranslatedQuery translate(String query, String model) {
        log.info("Translating and augmenting query: {}", query);

        TranslatedQuery result = new TranslatedQuery();
        result.setOriginalQuery(query);

        try {
            String prompt = buildPrompt(query);
            result.setPrompt(prompt);  // Store prompt for logging
            
            String response = aiService.generate(prompt, model);
            result.setRawResponse(response);  // Store raw response for logging
            
            parseResponse(response, result);

            log.info("Translation result: translated='{}', keywords={}",
                result.getTranslatedQuery(), result.getSearchKeywords());
        } catch (Exception e) {
            log.warn("Query translation failed, using original: {}", e.getMessage());
            result.setTranslatedQuery(query);
            result.setSearchKeywords(List.of());
        }

        return result;
    }

    private String buildPrompt(String query) {
        return """
            You are a database query translator. Analyze the user's question and provide:

            1. translatedQuery: Translate to English (if already English, keep as-is).
               Make it a clear, concise database question.

            2. searchKeywords: Generate 5-10 relevant keywords/phrases for semantic search.
               Include:
               - The main metrics/concepts mentioned (e.g., DAU → "Daily Active Users")
               - Related database terms (e.g., "user activity", "traffic", "engagement")
               - Synonyms and abbreviation expansions
               - Time-related terms if mentioned

            User Query: "%s"

            Respond with ONLY valid JSON:
            {"translatedQuery": "...", "searchKeywords": ["keyword1", "keyword2", ...]}
            """.formatted(query);
    }

    private void parseResponse(String response, TranslatedQuery result) {
        if (response == null || response.trim().isEmpty()) {
            throw new RuntimeException("Empty LLM response");
        }

        // Clean response - extract JSON if wrapped in markdown
        String jsonStr = response.trim();
        if (jsonStr.startsWith("```json")) {
            jsonStr = jsonStr.substring(7);
        }
        if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.substring(3);
        }
        if (jsonStr.endsWith("```")) {
            jsonStr = jsonStr.substring(0, jsonStr.length() - 3);
        }
        jsonStr = jsonStr.trim();

        try {
            JSONObject json = JSON.parseObject(jsonStr);

            // translatedQuery
            String translated = json.getString("translatedQuery");
            result.setTranslatedQuery(translated != null ? translated : result.getOriginalQuery());

            // searchKeywords
            JSONArray keywords = json.getJSONArray("searchKeywords");
            if (keywords != null) {
                List<String> keywordList = new ArrayList<>();
                for (int i = 0; i < keywords.size(); i++) {
                    keywordList.add(keywords.getString(i));
                }
                result.setSearchKeywords(keywordList);
            } else {
                result.setSearchKeywords(List.of());
            }

        } catch (Exception e) {
            log.warn("Failed to parse LLM JSON response: {}, raw: {}", e.getMessage(), jsonStr);
            throw new RuntimeException("Failed to parse LLM response", e);
        }
    }

    /**
     * Builds the augmented search query by combining translated query with keywords.
     */
    public String buildSearchQuery(TranslatedQuery translated) {
        StringBuilder sb = new StringBuilder();
        sb.append(translated.getTranslatedQuery());

        if (translated.getSearchKeywords() != null && !translated.getSearchKeywords().isEmpty()) {
            sb.append(" ");
            sb.append(String.join(" ", translated.getSearchKeywords()));
        }

        return sb.toString();
    }

    @Data
    public static class TranslatedQuery {
        private String originalQuery;
        private String translatedQuery;
        private List<String> searchKeywords;
        private String prompt;  // LLM prompt used
        private String rawResponse;  // LLM raw response

        /**
         * Returns the augmented query for Vector DB search.
         * Combines translated query with search keywords.
         */
        public String getSearchQuery() {
            StringBuilder sb = new StringBuilder();
            sb.append(translatedQuery != null ? translatedQuery : originalQuery);

            if (searchKeywords != null && !searchKeywords.isEmpty()) {
                sb.append(" ");
                sb.append(String.join(" ", searchKeywords));
            }

            return sb.toString();
        }
    }
}
