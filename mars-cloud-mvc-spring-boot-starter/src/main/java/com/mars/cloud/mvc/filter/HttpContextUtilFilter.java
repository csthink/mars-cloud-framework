package com.mars.cloud.mvc.filter;

import com.mars.cloud.mvc.util.HttpContextUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * @since 2025-10-30 09:46
 */
@Slf4j
//@Component
//@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpContextUtilFilter extends OncePerRequestFilter {

    @SneakyThrows
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpContextUtil.create(request, response);

        try {
            filterChain.doFilter(request, response);
        } finally {
            HttpContextUtil.remove();
        }
    }
}
