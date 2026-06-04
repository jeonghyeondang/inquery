package ai.inquery.server.web.start.config.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.BodyFilter;

/**
 * log config
 *
 */
@Configuration
public class WebLogConfiguration {

    @Bean
    public BodyFilter bodyFilter() {
        return BodyFilter.none();
    }
}
