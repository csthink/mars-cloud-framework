package com.mars.cloud.mvc.util;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;

/**
 * @since 2025-10-30 08:17
 */
public class RequestUtil {

    public static String getRequestMethodAndUri() {
        HttpServletRequest request = HttpContextUtil.getRequest();
        if (request != null && StringUtils.isNotBlank(request.getRequestURI())
                && StringUtils.isNotBlank(request.getMethod())) {
            return request.getMethod() + " - " + request.getRequestURI();
        }

        return "not a http request";
    }
}
