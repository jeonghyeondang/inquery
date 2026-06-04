package ai.inquery.server.web.start.exception.convertor;

import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.common.util.I18nUtils;
import ai.inquery.spi.util.ExceptionUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * MaxUploadSizeExceededException
 *
 */
public class MaxUploadSizeExceededExceptionConvertor implements ExceptionConvertor<MaxUploadSizeExceededException> {

    @Override
    public ActionResult convert(MaxUploadSizeExceededException exception) {
        return ActionResult.fail("common.maxUploadSize", I18nUtils.getMessage("common.maxUploadSize"), ExceptionUtils.getErrorInfoFromException(exception));
    }
}
