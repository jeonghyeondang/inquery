package ai.inquery.server.web.start.exception.convertor;

import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.spi.util.ExceptionUtils;
import org.springframework.validation.BindException;

/**
 * BindException
 *
 */
public class BindExceptionConvertor implements ExceptionConvertor<BindException> {

    @Override
    public ActionResult convert(BindException exception) {
        String message = ExceptionConvertorUtils.buildMessage(exception.getBindingResult());
        return ActionResult.fail("common.paramError", message, ExceptionUtils.getErrorInfoFromException(exception));
    }
}
