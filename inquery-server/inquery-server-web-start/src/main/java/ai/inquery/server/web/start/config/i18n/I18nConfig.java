package ai.inquery.server.web.start.config.i18n;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.util.Locale;

/**
 * Internationalized configuration
 *
 */
@Configuration
public class I18nConfig {
    @Bean
    public CookieLocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("INQUERY.LOCALE");
        resolver.setDefaultLocale(Locale.US);
        return resolver;
    }
}
