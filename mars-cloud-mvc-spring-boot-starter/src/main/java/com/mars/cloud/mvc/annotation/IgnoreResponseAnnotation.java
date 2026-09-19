package com.mars.cloud.mvc.annotation;

import java.lang.annotation.*;

/**
 * @since 2025-10-29 10:34
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
public @interface IgnoreResponseAnnotation {

}
