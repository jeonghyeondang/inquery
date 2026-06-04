package ai.inquery.server.domain.core.impl;

import ai.inquery.server.domain.api.param.AiFeedbackCreateParam;
import ai.inquery.server.domain.api.service.AiFeedbackService;
import ai.inquery.server.domain.repository.MapperUtils;
import ai.inquery.server.domain.repository.entity.AiFeedbackDO;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import ai.inquery.server.tools.base.wrapper.result.ListResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Feedback Service Implementation
 */
@Service
public class AiFeedbackServiceImpl implements AiFeedbackService {

    @Override
    public DataResult<Long> create(AiFeedbackCreateParam param) {
        AiFeedbackDO feedbackDO = new AiFeedbackDO();
        feedbackDO.setFeedbackType(param.getFeedbackType());
        feedbackDO.setResponseType(param.getResponseType());
        feedbackDO.setChatRoomId(param.getChatRoomId());
        feedbackDO.setMessageId(param.getMessageId());
        feedbackDO.setQuestion(param.getQuestion());
        feedbackDO.setGeneratedContent(param.getGeneratedContent());
        feedbackDO.setResearchSessionId(param.getResearchSessionId());
        feedbackDO.setDataSourceId(param.getDataSourceId());
        feedbackDO.setDatabaseName(param.getDatabaseName());
        feedbackDO.setUserId(param.getUserId());
        
        MapperUtils.getAiFeedbackMapper().insert(feedbackDO);
        return DataResult.of(feedbackDO.getId());
    }

    @Override
    public DataResult<Boolean> hasPositiveFeedback(String question, Long dataSourceId) {
        QueryWrapper<AiFeedbackDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("feedback_type", "POSITIVE");
        queryWrapper.eq("data_source_id", dataSourceId);
        queryWrapper.like("question", question);
        
        Long count = MapperUtils.getAiFeedbackMapper().selectCount(queryWrapper);
        return DataResult.of(count > 0);
    }

    @Override
    public ListResult<QueryPattern> getSuccessfulPatterns(Long dataSourceId, int limit) {
        if (dataSourceId == null) {
            return ListResult.of(new ArrayList<>());
        }

        QueryWrapper<AiFeedbackDO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("feedback_type", "POSITIVE");
        queryWrapper.eq("response_type", "SQL_GENERATION");
        queryWrapper.eq("data_source_id", dataSourceId);
        queryWrapper.isNotNull("question");
        queryWrapper.isNotNull("generated_content");
        queryWrapper.ne("question", "");
        queryWrapper.ne("generated_content", "");
        queryWrapper.orderByDesc("gmt_create");
        queryWrapper.last("LIMIT " + limit);

        List<AiFeedbackDO> feedbackList = MapperUtils.getAiFeedbackMapper().selectList(queryWrapper);
        
        List<QueryPattern> patterns = new ArrayList<>();
        for (AiFeedbackDO feedback : feedbackList) {
            patterns.add(new QueryPattern(feedback.getQuestion(), feedback.getGeneratedContent()));
        }
        
        return ListResult.of(patterns);
    }
}
