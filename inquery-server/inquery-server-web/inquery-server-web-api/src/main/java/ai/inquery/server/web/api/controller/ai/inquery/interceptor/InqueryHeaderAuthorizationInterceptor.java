package ai.inquery.server.web.api.controller.ai.inquery.interceptor;

import ai.inquery.server.domain.api.enums.AiSqlSourceEnum;
import ai.inquery.server.web.api.util.StringUtils;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import lombok.Getter;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * Description: Request to add header apikey
 *
 * @since 2023-03-23
 */
@Getter
public class InqueryHeaderAuthorizationInterceptor implements Interceptor {

    private String apiKey;

    private String model;

    public InqueryHeaderAuthorizationInterceptor(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        if (StringUtils.isEmpty(model)) {
            this.model = AiSqlSourceEnum.OPENAI.getCode();
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        Request request = original.newBuilder()
                .header(Header.AUTHORIZATION.getValue(), "Bearer " + apiKey)
                .header("X-INQUERY-AI-TYPE", model)
                .header(Header.CONTENT_TYPE.getValue(), ContentType.JSON.getValue())
                .method(original.method(), original.body())
                .build();
        return chain.proceed(request);
    }
}
