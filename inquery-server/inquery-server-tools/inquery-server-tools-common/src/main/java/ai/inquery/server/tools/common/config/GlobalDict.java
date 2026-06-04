package ai.inquery.server.tools.common.config;

import ai.inquery.server.tools.common.util.ConfigUtils;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * global dictionary
 *
 */
public interface GlobalDict {
    /**
     * template file
     **/
    List<String> TEMPLATE_FILE = Arrays.asList("template.html", "template_diy.docx", "sub_template_diy.docx");
    /**
     * Template storage directory
     **/
    String templateDir = ConfigUtils.CONFIG_BASE_PATH + File.separator + "template" + File.separator;

}
