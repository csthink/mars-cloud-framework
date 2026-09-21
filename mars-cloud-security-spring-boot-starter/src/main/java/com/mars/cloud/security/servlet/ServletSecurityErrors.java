package com.mars.cloud.security.servlet;

import com.mars.cloud.security.*;
import com.mars.cloud.security.web.SecurityResponses;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Resource server errors encoded as the common response envelope. */
public final class ServletSecurityErrors implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final SecurityResponses responses;
    public ServletSecurityErrors(SecurityResponses responses) { this.responses = responses; }
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) throws IOException {
        write(request, response, request.getHeader("Authorization") == null
                ? SecurityErrorCode.TOKEN_MISSING : SecurityErrorCode.TOKEN_INVALID);
    }
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure) throws IOException {
        write(request, response, SecurityFailure.find(failure));
    }
    public void write(HttpServletRequest request, HttpServletResponse response, SecurityErrorCode code) throws IOException {
        response.setStatus(code.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (code.status() == 401) response.setHeader("WWW-Authenticate", SecurityResponses.challenge(code));
        response.getOutputStream().write(responses.body(code, request.getLocale()));
    }
}
