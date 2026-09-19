package com.mars.cloud.mvc.domain;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @since 2025-10-30 08:18
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HttpContext {

    private HttpServletRequest request;
    private HttpServletResponse response;
}
