package com.gdgoc.dashboard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdgoc.dashboard.common.ApiError;
import com.gdgoc.dashboard.member.MemberStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.regex.Pattern;

@Component
public class TerminationAccessFilter extends OncePerRequestFilter {

    private static final Pattern LETTER_DOWNLOAD_OR_SIGNED_URL_PATTERN =
            Pattern.compile("^/api/v1/profile/me/letters/\\d+/(download|signed-url)$");
    private static final Pattern SIGNED_DOWNLOAD_TOKEN_PATTERN =
            Pattern.compile("^/api/v1/letters/signed-download/[^/]+$");

    private final ObjectMapper objectMapper;

    public TerminationAccessFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            if (principal.getStatus() == MemberStatus.TERMINATED) {
                String method = request.getMethod();
                String uri = request.getRequestURI();

                boolean isWhitelistedGet = "GET".equalsIgnoreCase(method) && (
                        "/api/v1/auth/me".equals(uri)
                        || "/api/v1/profile/me".equals(uri)
                        || "/api/v1/profile/me/letters".equals(uri)
                        || LETTER_DOWNLOAD_OR_SIGNED_URL_PATTERN.matcher(uri).matches()
                        || SIGNED_DOWNLOAD_TOKEN_PATTERN.matcher(uri).matches()
                );

                if (!isWhitelistedGet) {
                    writeForbidden(
                            response,
                            uri,
                            "All operational permissions are revoked after termination. Only profile and letter downloads are accessible during the 10-day window."
                    );
                    return;
                }

                if (uri.startsWith("/api/v1/profile/me/letters") || SIGNED_DOWNLOAD_TOKEN_PATTERN.matcher(uri).matches()) {
                    Instant expiresAt = principal.getLetterWindowExpiresAt();
                    if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
                        writeForbidden(
                                response,
                                uri,
                                "The 10-day post-termination window to view or download letters has expired."
                        );
                        return;
                    }
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private void writeForbidden(HttpServletResponse response, String path, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error = ApiError.of(403, "Forbidden", message, path);
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
