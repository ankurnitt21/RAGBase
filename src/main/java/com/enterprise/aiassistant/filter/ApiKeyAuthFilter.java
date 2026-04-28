package com.enterprise.aiassistant.filter;

import com.enterprise.aiassistant.exception.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that enforces API key authentication via the X-API-Key header.
 * Authentication is only active when app.security.api-key is set in the environment.
 * When the key is blank (default in dev), all requests pass through unauthenticated.
 * Skips auth for /actuator, /swagger-ui, /v3/api-docs, and static UI paths.
 * Returns HTTP 401 with an ApiError body for missing or invalid keys.
 */
@Component
@Order(2)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final String configuredApiKey;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(@Value("${app.security.api-key:}") String configuredApiKey) {
        this.configuredApiKey = configuredApiKey;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip auth for actuator, swagger, and static UI
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/")
                || path.startsWith("/static");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // If no API key is configured, auth is disabled (dev mode)
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey == null || !providedKey.equals(configuredApiKey)) {
            log.warn("Unauthorized request to {} — invalid or missing API key", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ApiError error = ApiError.of(401, "Unauthorized",
                    "Missing or invalid X-API-Key header", request.getRequestURI());
            response.getWriter().write(objectMapper.writeValueAsString(error));
            return;
        }

        chain.doFilter(request, response);
    }
}
