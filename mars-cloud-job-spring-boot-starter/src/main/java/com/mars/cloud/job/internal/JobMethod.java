package com.mars.cloud.job.internal;

import com.mars.cloud.job.JobContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 一个已登记的任务方法。
 *
 * @param name         任务名
 * @param beanName     所在 Bean 的名字
 * @param bean         所在 Bean（可能是代理）
 * @param method       在 {@code bean} 上可调用的方法
 * @param takesContext 方法是否接受 {@link JobContext}
 */
public record JobMethod(String name, String beanName, Object bean, Method method, boolean takesContext) {

    /**
     * 调用任务方法；方法抛出的异常原样抛出（去掉反射包装）。
     */
    void invoke(JobContext context) throws Throwable {
        try {
            if (takesContext) {
                method.invoke(bean, context);
            } else {
                method.invoke(bean);
            }
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
