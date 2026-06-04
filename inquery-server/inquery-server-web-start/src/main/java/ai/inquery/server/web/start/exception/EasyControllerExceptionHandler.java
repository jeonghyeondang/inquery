package ai.inquery.server.web.start.exception;

import ai.inquery.server.web.start.exception.convertor.*;
import ai.inquery.server.tools.base.excption.BusinessException;
import ai.inquery.server.tools.base.excption.SystemException;
import ai.inquery.server.tools.base.wrapper.result.ActionResult;
import ai.inquery.server.tools.common.exception.NeedLoggedInBusinessException;
import ai.inquery.server.tools.common.exception.RedirectBusinessException;
import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Maps;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

/**
 * Intercepting Controller exceptions
 *
 */
@ControllerAdvice
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EasyControllerExceptionHandler {

    /**
     * All exception handling converters
     */
    public static final Map<Class<?>, ExceptionConvertor> EXCEPTION_CONVERTOR_MAP = Maps.newHashMap();

    static {
        EXCEPTION_CONVERTOR_MAP.put(MethodArgumentNotValidException.class,
            new MethodArgumentNotValidExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(BindException.class, new BindExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(BusinessException.class, new BusinessExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(NeedLoggedInBusinessException.class, new BusinessExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(MissingServletRequestParameterException.class, new ParamExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(IllegalArgumentException.class, new ParamExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(MethodArgumentTypeMismatchException.class,
            new MethodArgumentTypeMismatchExceptionConvertor());
        EXCEPTION_CONVERTOR_MAP.put(MaxUploadSizeExceededException.class,
            new MaxUploadSizeExceededExceptionConvertor());
    }

    /**
     * Default converter
     */
    public static ExceptionConvertor DEFAULT_EXCEPTION_CONVERTOR = new DefaultExceptionConvertor();

    /**
     * Business abnormality
     *
     * @param request   request
     * @param exception exception
     * @return return
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, IllegalArgumentException.class,
        MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
        BusinessException.class, MaxUploadSizeExceededException.class,
        HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotAcceptableException.class,
        MultipartException.class, MissingRequestHeaderException.class, HttpMediaTypeNotSupportedException.class,
        NeedLoggedInBusinessException.class})
    @ResponseStatus(value = HttpStatus.OK)
    @ResponseBody
    public ActionResult handleBusinessException(HttpServletRequest request, Exception exception) {
        ActionResult result = convert(exception);
        log.info("Business exception occurred{}:{}", request.getRequestURI(), result, exception);
        return result;
    }

    /**
     * Business abnormality
     *
     * @param request   request
     * @param exception exception
     * @return return
     */
    @ExceptionHandler({RedirectBusinessException.class})
    public ModelAndView handleModelAndViewBizException(HttpServletRequest request, Exception exception) {
        ModelAndView result = translateModelAndView(exception);
        log.info("ModelAndView business exception occurred{}:{}", request.getRequestURI(), result, exception);
        return result;
    }

    public ModelAndView translateModelAndView(Throwable exception) {
        // Parameter exception
        if (exception instanceof RedirectBusinessException) {
            RedirectBusinessException e = (RedirectBusinessException)exception;
            return dealResponseModelAndView(null, e.getMessage(), e.getRedirect(), null, null);
        }
        // Jump to homepage by default
        return new ModelAndView("redirect:/");
    }

    private ModelAndView dealResponseModelAndView(String title, String errorMessage, String redirect, String href,
        String buttonText) {
        // If there is redirection information, jump
        if (StringUtils.isNotBlank(redirect)) {
            return new ModelAndView("redirect:" + redirect);
        }
        // Jump to homepage by default
        return new ModelAndView("redirect:/");

        // synchronous request
        //return ModelAndViewUtils.error(title, errorMessage,href,buttonText);
    }

    /**
     * System exception
     *
     * @param request   request
     * @param exception exception
     * @return return
     */
    @ExceptionHandler({SystemException.class})
    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public ActionResult handleSystemException(HttpServletRequest request, Exception exception) {
        ActionResult result = convert(exception);
        log.error("System exception occurred {}:{}", request.getRequestURI(), result, exception);
        return result;
    }

    /**
     * Unknown exception requires manual intervention to view logs
     *
     * @param request   request
     * @param exception exception
     * @return return
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public ActionResult handledException(HttpServletRequest request, Exception exception) {
        // Skip logging for client disconnect exceptions (Broken pipe / ClientAbortException)
        if (isClientAbortException(exception)) {
            log.debug("Client disconnected: {} {}", request.getRequestURI(), exception.getMessage());
            return null;
        }
        ActionResult result = convert(exception);
        log.error("An unknown exception occurred {}:{}", request.getRequestURI(), result, exception);
        return result;
    }

    /**
     * Check if an exception is caused by client disconnection (Broken pipe / ClientAbortException).
     */
    private boolean isClientAbortException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String className = current.getClass().getName();
            if (className.contains("ClientAbortException")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("Broken pipe") || message.contains("Connection reset"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public ActionResult convert(Throwable exception) {
        ExceptionConvertor exceptionConvertor = EXCEPTION_CONVERTOR_MAP.get(exception.getClass());
        if (exceptionConvertor == null) {
            if (exception instanceof BusinessException) {
                exceptionConvertor = EXCEPTION_CONVERTOR_MAP.get(BusinessException.class);
            } else if (exception instanceof SystemException) {
                exceptionConvertor = EXCEPTION_CONVERTOR_MAP.get(SystemException.class);
            } else {
                exceptionConvertor = DEFAULT_EXCEPTION_CONVERTOR;
            }
        }
        return exceptionConvertor.convert(exception);
    }
}
