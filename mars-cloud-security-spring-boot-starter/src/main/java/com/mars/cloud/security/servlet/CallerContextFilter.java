package com.mars.cloud.security.servlet;

import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.security.AuthenticatedCaller;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.Callable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;

/** Installs verified identity for each Servlet dispatch and supported Callable execution. */
public final class CallerContextFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken) || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        var caller = AuthenticatedCaller.from(authentication);
        WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor(CallerContextFilter.class.getName(),
                new CallableProcessingInterceptor() {
                    private final ThreadLocal<CallerContextHolder.Scope> scope = new ThreadLocal<>();
                    @Override public <T> void preProcess(NativeWebRequest webRequest, Callable<T> task) {
                        scope.set(CallerContextHolder.open(AuthenticatedCaller.from(
                                SecurityContextHolder.getContext().getAuthentication())));
                    }
                    @Override public <T> void postProcess(NativeWebRequest webRequest, Callable<T> task, Object result) {
                        var current = scope.get();
                        try { if (current != null) current.close(); } finally { scope.remove(); }
                    }
                });
        try (var ignored = CallerContextHolder.open(caller)) { chain.doFilter(request, response); }
    }
}
