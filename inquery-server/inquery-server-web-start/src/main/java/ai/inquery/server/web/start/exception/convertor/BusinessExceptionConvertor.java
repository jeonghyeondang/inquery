package ai.inquery.server.web.start.exception.convertor;

import ai.inquery.server.tools.base.excption.BusinessException;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.common.util.I18nUtils;
import ai.inquery.spi.util.ExceptionUtils;

/**
 * BusinessException
 *
 */
public class BusinessExceptionConvertor implements ExceptionConvertor<BusinessException> {

    @Override
    public ActionResult convert(BusinessException exception) {
        return ActionResult.fail(exception.getCode(), I18nUtils.getMessage(exception.getCode(), exception.getArgs()),
            ExceptionUtils.getErrorInfoFromException(exception));
    }
}
