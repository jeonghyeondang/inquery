package ai.inquery.server.web.api.controller.ai.DocParser;

import java.io.InputStream;
import java.util.List;

/**
 * @date March 20, 2023 8:13 am
 * @description
 */
public abstract class AbstractParser {
    public abstract List<String> parse(InputStream inputStream) throws Exception;
}
