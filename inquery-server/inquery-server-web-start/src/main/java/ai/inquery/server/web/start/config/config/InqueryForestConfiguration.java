package ai.inquery.server.web.start.config.config;

import ai.inquery.server.tools.common.config.InqueryProperties;
import com.dtflys.forest.Forest;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;

/**
 * forest config
 *
 */
@Configuration
public class InqueryForestConfiguration implements InitializingBean {

    @Resource
    private InqueryProperties inqueryProperties;
    @Override
    public void afterPropertiesSet() throws Exception {
        Forest.config()
            .setVariableValue("gatewayBaseUrl", inqueryProperties.getGateway().getBaseUrl());
    }
}
