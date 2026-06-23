package io.arkmem.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpExchangeLoggingFilter extends OncePerRequestFilter {

    static final String REQUEST_ID_ATTRIBUTE = HttpExchangeLoggingFilter.class.getName() + ".REQUEST_ID";

    private static final Logger log = LoggerFactory.getLogger(HttpExchangeLoggingFilter.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_BODY_CHARS = 4_096;
    private static final String REDACTED = "[redacted]";
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader("X-Request-Id", requestId);

        ContentCachingRequestWrapper wrappedRequest = wrapRequest(request);
        ContentCachingResponseWrapper wrappedResponse = wrapResponse(response);
        long startedAt = System.nanoTime();
        Exception failure = null;

        MDC.put("request_id", requestId);
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } catch (IOException | ServletException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                logExchange(wrappedRequest, wrappedResponse, requestId, startedAt, failure);
                wrappedResponse.copyBodyToResponse();
            } finally {
                MDC.remove("request_id");
            }
        }
    }

    private void logExchange(
            ContentCachingRequestWrapper request,
            ContentCachingResponseWrapper response,
            String requestId,
            long startedAt,
            Exception failure
    ) {
        if (!log.isInfoEnabled()) {
            return;
        }

        String method = request.getMethod();
        String uri = requestUri(request);
        log.info(
                "HTTP request request_id={} method={} uri={} client={} params={} body={}",
                requestId,
                method,
                uri,
                request.getRemoteAddr(),
                requestParameters(request),
                body(request.getContentAsByteArray(), request.getContentType(), request.getCharacterEncoding())
        );

        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
        log.info(
                "HTTP response request_id={} method={} uri={} status={} duration_ms={} failure={} body={}",
                requestId,
                method,
                uri,
                response.getStatus(),
                durationMillis,
                failure == null ? "" : failure.getClass().getSimpleName(),
                body(response.getContentAsByteArray(), response.getContentType(), response.getCharacterEncoding())
        );
    }

    private static ContentCachingRequestWrapper wrapRequest(HttpServletRequest request) {
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            return wrapper;
        }
        return new ContentCachingRequestWrapper(request);
    }

    private static ContentCachingResponseWrapper wrapResponse(HttpServletResponse response) {
        if (response instanceof ContentCachingResponseWrapper wrapper) {
            return wrapper;
        }
        return new ContentCachingResponseWrapper(response);
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String headerValue = request.getHeader("X-Request-Id");
        if (headerValue == null || headerValue.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String requestId = headerValue.trim();
        return REQUEST_ID_PATTERN.matcher(requestId).matches() ? requestId : UUID.randomUUID().toString();
    }

    private static String requestUri(HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isBlank()) {
            return request.getRequestURI();
        }
        return request.getRequestURI() + "?" + redactQueryString(queryString);
    }

    private static String requestParameters(HttpServletRequest request) {
        Map<String, String[]> parameters = request.getParameterMap();
        if (parameters == null || parameters.isEmpty()) {
            return "{}";
        }

        ObjectNode params = OBJECT_MAPPER.createObjectNode();
        parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendParameter(params, entry.getKey(), entry.getValue()));
        try {
            return OBJECT_MAPPER.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static void appendParameter(ObjectNode params, String key, String[] values) {
        if (isSensitiveKey(key)) {
            params.put(key, REDACTED);
            return;
        }
        if (values == null || values.length == 0) {
            params.put(key, "");
            return;
        }
        if (values.length == 1) {
            params.put(key, values[0]);
            return;
        }

        ArrayNode array = params.putArray(key);
        for (String value : values) {
            array.add(value);
        }
    }

    private static String body(byte[] content, String contentType, String characterEncoding) {
        if (content == null || content.length == 0) {
            return "";
        }
        if (!isVisibleContent(contentType)) {
            return "[omitted content_type=" + contentType + " bytes=" + content.length + "]";
        }
        return truncate(redactBody(new String(content, charset(contentType, characterEncoding))));
    }

    private static boolean isVisibleContent(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith(MediaType.TEXT_PLAIN_VALUE)
                || normalized.startsWith(MediaType.APPLICATION_JSON_VALUE)
                || normalized.startsWith(MediaType.APPLICATION_XML_VALUE)
                || normalized.startsWith(MediaType.TEXT_XML_VALUE)
                || normalized.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                || normalized.contains("+json");
    }

    private static Charset charset(String contentType, String characterEncoding) {
        Charset contentTypeCharset = contentTypeCharset(contentType);
        if (contentTypeCharset != null) {
            return contentTypeCharset;
        }
        if (isJsonContent(contentType)) {
            return StandardCharsets.UTF_8;
        }
        if (characterEncoding == null || characterEncoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(characterEncoding);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static Charset contentTypeCharset(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        try {
            return MediaType.parseMediaType(contentType).getCharset();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isJsonContent(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith(MediaType.APPLICATION_JSON_VALUE)
                || normalized.contains("+json");
    }

    private static String redactBody(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(trimmed);
            return OBJECT_MAPPER.writeValueAsString(redactJson(root));
        } catch (JsonProcessingException e) {
            return redactKeyValueText(trimmed);
        }
    }

    private static JsonNode redactJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode redacted = OBJECT_MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                if (isSensitiveKey(entry.getKey())) {
                    redacted.put(entry.getKey(), REDACTED);
                } else {
                    redacted.set(entry.getKey(), redactJson(entry.getValue()));
                }
            });
            return redacted;
        }
        if (node.isArray()) {
            ArrayNode redacted = OBJECT_MAPPER.createArrayNode();
            node.forEach(item -> redacted.add(redactJson(item)));
            return redacted;
        }
        return node;
    }

    private static String redactQueryString(String queryString) {
        String[] parts = queryString.split("&", -1);
        for (int i = 0; i < parts.length; i++) {
            int separator = parts[i].indexOf('=');
            String key = separator >= 0 ? parts[i].substring(0, separator) : parts[i];
            if (isSensitiveKey(key)) {
                parts[i] = separator >= 0 ? key + "=" + REDACTED : key;
            }
        }
        return String.join("&", parts);
    }

    private static String redactKeyValueText(String value) {
        String redacted = value;
        for (String key : new String[]{"api_key", "apiKey", "access_token", "refresh_token", "token", "password", "secret"}) {
            redacted = redacted.replaceAll("(?i)(" + key + "\\s*[=:]\\s*)[^&\\s,}]+", "$1" + REDACTED);
        }
        return redacted;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
        return normalized.contains("apikey")
                || normalized.contains("accesstoken")
                || normalized.contains("refreshtoken")
                || normalized.equals("token")
                || normalized.contains("authorization")
                || normalized.contains("password")
                || normalized.contains("secret");
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_BODY_CHARS) {
            return value;
        }
        return value.substring(0, MAX_BODY_CHARS) + "...[truncated]";
    }
}
