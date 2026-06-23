package io.arkmem.memory.controller;

import io.arkmem.memory.config.ArkMemProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class InternalApiTokenFilter extends OncePerRequestFilter {

    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/api/health",
            "/api/ready",
            "/actuator",
            "/configure",
            "/v3/api-docs",
            "/swagger-ui"
    );

    private final ArkMemProperties properties;

    public InternalApiTokenFilter(ArkMemProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String expected = properties.getApi().getInternalToken();
        if (!hasText(expected)) {
            return true;
        }
        String path = request.getRequestURI();
        return PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String expected = properties.getApi().getInternalToken();
        if (matches(expected, bearerToken(request.getHeader("Authorization")))
                || matches(expected, request.getHeader("X-API-Key"))) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code":"FORBIDDEN","message":"Invalid internal token"}
                """);
    }

    private static String bearerToken(String authorization) {
        if (!hasText(authorization)) {
            return null;
        }
        String prefix = "Bearer ";
        return authorization.regionMatches(true, 0, prefix, 0, prefix.length())
                ? authorization.substring(prefix.length()).trim()
                : authorization.trim();
    }

    private static boolean matches(String expected, String candidate) {
        if (!hasText(expected) || !hasText(candidate)) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                candidate.trim().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
