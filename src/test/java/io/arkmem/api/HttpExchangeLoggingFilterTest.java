package io.arkmem.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class HttpExchangeLoggingFilterTest {

    private final HttpExchangeLoggingFilter filter = new HttpExchangeLoggingFilter();

    @Test
    void logsRequestAndResponseBodiesWithRedaction(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/memories");
        request.setQueryString("api_key=sk-query&user_id=user-1");
        request.addParameter("api_key", "sk-query");
        request.addParameter("user_id", "user-1");
        request.addHeader("X-Request-Id", "request-123");
        request.setRemoteAddr("127.0.0.1");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent("""
                {
                  "api_key": "sk-body",
                  "password": "pass-body",
                  "messages": [
                    {"role": "user", "content": "Remember stable preferences."}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            servletRequest.getInputStream().readAllBytes();
            servletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            servletResponse.getWriter().write("{\"token\":\"response-token\",\"status\":\"ok\"}");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("request-123");
        assertThat(response.getContentAsString()).isEqualTo("{\"token\":\"response-token\",\"status\":\"ok\"}");
        assertThat(output)
                .contains("HTTP request")
                .contains("HTTP response")
                .contains("request_id=request-123")
                .contains("method=POST")
                .contains("uri=/memories?api_key=[redacted]&user_id=user-1")
                .contains("params={\"api_key\":\"[redacted]\",\"user_id\":\"user-1\"}")
                .contains("\"api_key\":\"[redacted]\"")
                .contains("\"password\":\"[redacted]\"")
                .contains("\"token\":\"[redacted]\"")
                .doesNotContain("sk-query")
                .doesNotContain("sk-body")
                .doesNotContain("pass-body")
                .doesNotContain("response-token");
    }

    @Test
    void logsFormParametersWithRedaction(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/configure");
        request.addHeader("X-Request-Id", "request-456");
        request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.addParameter("api_key", "sk-form");
        request.addParameter("provider", "aliyun-bailian");
        request.setContent("api_key=sk-form&provider=aliyun-bailian".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            servletRequest.getParameterMap();
            servletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            servletResponse.getWriter().write("{\"configured\":true}");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getContentAsString()).isEqualTo("{\"configured\":true}");
        assertThat(output)
                .contains("HTTP request")
                .contains("HTTP response")
                .contains("request_id=request-456")
                .contains("params={\"api_key\":\"[redacted]\",\"provider\":\"aliyun-bailian\"}")
                .contains("body=api_key=[redacted]&provider=aliyun-bailian")
                .contains("{\"configured\":true}")
                .doesNotContain("sk-form");
    }

    @Test
    void logsJsonResponseAsUtf8WhenCharsetIsNotDeclared(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/memories");
        request.addHeader("X-Request-Id", "request-789");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
            servletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
            servletResponse.getOutputStream().write("""
                    {"results":[{"memory":"项目确认：中文摘要保持可读"}]}
                    """.getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilter(request, response, chain);

        assertThat(output)
                .contains("request_id=request-789")
                .contains("项目确认：中文摘要保持可读")
                .doesNotContain("é¡¹");
    }
}
