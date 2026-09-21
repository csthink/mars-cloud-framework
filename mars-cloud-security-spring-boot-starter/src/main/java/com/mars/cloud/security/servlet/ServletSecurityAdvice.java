package com.mars.cloud.security.servlet;

import com.mars.cloud.security.SecurityFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Preserves method authorization error semantics before general MVC exception advice. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ServletSecurityAdvice {
    private final ServletSecurityErrors errors;
    public ServletSecurityAdvice(ServletSecurityErrors errors) { this.errors = errors; }
    @ExceptionHandler(AccessDeniedException.class)
    public void handle(AccessDeniedException failure, HttpServletRequest request, HttpServletResponse response) throws IOException {
        errors.write(request, response, SecurityFailure.find(failure));
    }
}
