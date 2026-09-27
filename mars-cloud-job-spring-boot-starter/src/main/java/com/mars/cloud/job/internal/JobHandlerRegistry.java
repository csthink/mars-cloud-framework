package com.mars.cloud.job.internal;

import com.mars.cloud.job.JobContext;
import com.mars.cloud.job.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 登记容器里全部 {@link JobHandler} 方法。
 *
 * <p>在单例实例化完成之后扫描全部 Bean 定义：按 Bean 的用户类（去掉 CGLIB 代理层；JDK 动态代理取目标类）查找注解，
 * 在 Bean 本身上调用，代理上的切面（例如事务）因此生效。延迟初始化的 Bean 同样扫描，找到任务方法时实例化它。
 * 名称重复、签名不符、静态方法或非单例 Bean 上的任务方法都让启动失败。
 */
public final class JobHandlerRegistry implements SmartInitializingSingleton, BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(JobHandlerRegistry.class);

    private ConfigurableListableBeanFactory beanFactory;
    private volatile Map<String, JobMethod> handlers = Map.of();

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory listable)) {
            throw new IllegalStateException("任务方法登记需要 ConfigurableListableBeanFactory，收到: " + beanFactory.getClass());
        }
        this.beanFactory = listable;
    }

    @Override
    public void afterSingletonsInstantiated() {
        handlers = scan(beanFactory);
        log.info("已登记 {} 个任务方法: {}", handlers.size(), handlers.keySet());
    }

    /** 按任务名查找；没有时为 null。 */
    public JobMethod find(String name) {
        return name == null ? null : handlers.get(name);
    }

    public Set<String> names() {
        return handlers.keySet();
    }

    static Map<String, JobMethod> scan(ConfigurableListableBeanFactory beanFactory) {
        SortedMap<String, JobMethod> found = new TreeMap<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            if (beanFactory.getMergedBeanDefinition(beanName).isAbstract()) {
                continue;
            }
            Class<?> type = beanFactory.getType(beanName, false);
            if (type == null) {
                continue;
            }
            // JDK 动态代理的类型只有接口，注解写在实现类上时按代理类型找不到；取出目标类查找，
            // 找到的方法不在接口上时 jobMethod() 让启动失败，而不是静默漏掉。
            Class<?> userClass = Proxy.isProxyClass(type)
                    ? AopProxyUtils.ultimateTargetClass(beanFactory.getBean(beanName))
                    : ClassUtils.getUserClass(type);
            Map<Method, JobHandler> methods = MethodIntrospector.selectMethods(userClass,
                    (MethodIntrospector.MetadataLookup<JobHandler>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, JobHandler.class));
            if (methods.isEmpty()) {
                continue;
            }
            require(beanFactory.isSingleton(beanName), "任务方法所在的 Bean 必须是单例：" + beanName);
            Object bean = beanFactory.getBean(beanName);
            for (Map.Entry<Method, JobHandler> entry : methods.entrySet()) {
                JobMethod jobMethod = jobMethod(beanName, bean, entry.getKey(), entry.getValue().value());
                JobMethod existing = found.putIfAbsent(jobMethod.name(), jobMethod);
                if (existing != null) {
                    throw new IllegalStateException("任务名重复：" + jobMethod.name() + " 同时出现在 " + describe(existing)
                            + " 与 " + describe(jobMethod));
                }
            }
        }
        return Collections.unmodifiableSortedMap(found);
    }

    private static JobMethod jobMethod(String beanName, Object bean, Method method, String name) {
        String where = beanName + "#" + method.getName();
        require(name != null && !name.isBlank() && name.chars().noneMatch(Character::isWhitespace),
                "任务名不能为空白，也不能含空白字符：" + where + "，收到: \"" + name + "\"");
        require(!Modifier.isStatic(method.getModifiers()), "任务方法不能是静态方法：" + where);
        Class<?>[] parameters = method.getParameterTypes();
        boolean takesContext = parameters.length == 1 && parameters[0] == JobContext.class;
        require(parameters.length == 0 || takesContext,
                "任务方法只能无参或只接受一个 JobContext：" + where);
        // CGLIB 代理不拦截 final 方法，调用会落在没有注入依赖的代理实例上，运行时才出错。
        require(!AopUtils.isCglibProxy(bean) || !Modifier.isFinal(method.getModifiers()),
                "任务方法所在的 Bean 由 CGLIB 代理，final 方法不经过代理，去掉 final：" + where);
        Method invocable;
        try {
            invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
        } catch (IllegalStateException notOnProxy) {
            String advice = !AopUtils.isJdkDynamicProxy(bean) && Modifier.isPrivate(method.getModifiers())
                    ? "代理不能调用 private 方法，改为包级、protected 或 public"
                    : "JDK 动态代理只暴露接口里声明的方法，任务方法要在 Bean 实现的接口里声明";
            throw new IllegalStateException("任务方法在 Bean 的代理上不可调用：" + advice + "：" + where, notOnProxy);
        }
        ReflectionUtils.makeAccessible(invocable);
        return new JobMethod(name, beanName, bean, invocable, takesContext);
    }

    private static String describe(JobMethod method) {
        return method.beanName() + "#" + method.method().getName();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
