package com.mars.cloud.job.internal;

import com.mars.cloud.job.JobContext;
import com.mars.cloud.job.JobHandler;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobHandlerRegistryTest {

    static final List<String> CALLS = new ArrayList<>();

    static class PlainJobs {
        @JobHandler("noArguments")
        void noArguments() {
            CALLS.add("noArguments");
        }

        @JobHandler("withContext")
        public void withContext(JobContext context) {
            CALLS.add("withContext:" + context.param());
        }

        void notAJob() {
        }
    }

    public static class AdvisedJobs {
        @JobHandler("advised")
        public void advised() {
            CALLS.add("advised");
        }
    }

    static class LazyJobs {
        @JobHandler("lazy")
        void lazy() {
            CALLS.add("lazy");
        }
    }

    public interface DeclaredJob {
        void declared();
    }

    public static class InterfaceJobs implements DeclaredJob {
        @Override
        @JobHandler("declared")
        public void declared() {
            CALLS.add("declared");
        }
    }

    public static class UndeclaredJobs implements DeclaredJob {
        @Override
        public void declared() {
        }

        @JobHandler("undeclared")
        public void undeclared() {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Jobs {
        @Bean
        PlainJobs plainJobs() {
            return new PlainJobs();
        }

        /** 类代理（CGLIB）：拦截器记录调用，证明任务方法经代理调用，切面生效。 */
        @Bean
        AdvisedJobs advisedJobs() {
            ProxyFactory factory = new ProxyFactory(new AdvisedJobs());
            factory.setProxyTargetClass(true);
            factory.addAdvice((MethodInterceptor) invocation -> {
                CALLS.add("intercepted");
                return invocation.proceed();
            });
            return (AdvisedJobs) factory.getProxy();
        }

        /** 接口代理（JDK 动态代理）：注解写在实现类上、方法在接口里声明。 */
        @Bean
        DeclaredJob interfaceJobs() {
            ProxyFactory factory = new ProxyFactory(new InterfaceJobs());
            factory.addInterface(DeclaredJob.class);
            factory.addAdvice((MethodInterceptor) invocation -> {
                CALLS.add("intercepted-interface");
                return invocation.proceed();
            });
            return (DeclaredJob) factory.getProxy();
        }

        @Bean
        @Lazy
        LazyJobs lazyJobs() {
            return new LazyJobs();
        }

        @Bean
        JobHandlerRegistry registry() {
            return new JobHandlerRegistry();
        }
    }

    private static JobContext context(String param) {
        return new JobContext() {
            @Override public int jobId() { return 1; }
            @Override public long logId() { return 1; }
            @Override public String param() { return param; }
            @Override public int shardIndex() { return 0; }
            @Override public int shardTotal() { return 1; }
            @Override public void log(String pattern, Object... arguments) { }
        };
    }

    @Test
    void handlersAreFoundOnPlainProxiedAndLazyBeans() throws Throwable {
        CALLS.clear();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Jobs.class)) {
            JobHandlerRegistry registry = context.getBean(JobHandlerRegistry.class);
            assertThat(registry.names()).containsExactly("advised", "declared", "lazy", "noArguments", "withContext");

            registry.find("noArguments").invoke(context("ignored"));
            registry.find("withContext").invoke(context("p1"));
            registry.find("advised").invoke(context(""));
            registry.find("declared").invoke(context(""));
            registry.find("lazy").invoke(context(""));
            assertThat(CALLS).containsExactly("noArguments", "withContext:p1", "intercepted", "advised",
                    "intercepted-interface", "declared", "lazy");
            assertThat(registry.find("missing")).isNull();
            assertThat(registry.find(null)).isNull();
        }
    }

    @Test
    void exceptionsFromTheMethodAreRethrownUnwrapped() {
        class Failing {
            @JobHandler("failing")
            void failing() {
                throw new IllegalStateException("boom");
            }
        }
        JobMethod method = JobHandlerRegistry.scan(factoryWith("failing", new Failing())).get("failing");
        assertThatThrownBy(() -> method.invoke(context(""))).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DefaultListableBeanFactory factoryWith(Object... namesAndBeans) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < namesAndBeans.length; i += 2) {
            Object bean = namesAndBeans[i + 1];
            factory.registerBeanDefinition((String) namesAndBeans[i], new RootBeanDefinition((Class) bean.getClass(), () -> bean));
        }
        return factory;
    }

    private static void rejects(DefaultListableBeanFactory factory, String fragment) {
        assertThatThrownBy(() -> JobHandlerRegistry.scan(factory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(fragment);
    }

    static class First {
        @JobHandler("same")
        void run() {
        }
    }

    static class Second {
        @JobHandler("same")
        void run() {
        }
    }

    @Test
    void duplicateNamesFailWithBothOwners() {
        rejects(factoryWith("first", new First(), "second", new Second()), "任务名重复：same 同时出现在 first#run 与 second#run");
    }

    static class BadSignature {
        @JobHandler("bad")
        void run(String argument) {
        }
    }

    static class StaticJob {
        @JobHandler("static")
        static void run() {
        }
    }

    static class BlankName {
        @JobHandler(" ")
        void run() {
        }
    }

    static class SpacedName {
        @JobHandler("two words")
        void run() {
        }
    }

    @Test
    void invalidMethodsFailStartup() {
        rejects(factoryWith("bad", new BadSignature()), "任务方法只能无参或只接受一个 JobContext：bad#run");
        rejects(factoryWith("static", new StaticJob()), "任务方法不能是静态方法：static#run");
        rejects(factoryWith("blank", new BlankName()), "任务名不能为空白，也不能含空白字符：blank#run");
        rejects(factoryWith("spaced", new SpacedName()), "收到: \"two words\"");
    }

    @Test
    void handlersOnPrototypeBeansFailStartup() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        RootBeanDefinition definition = new RootBeanDefinition(First.class);
        definition.setScope(BeanDefinition.SCOPE_PROTOTYPE);
        factory.registerBeanDefinition("prototypeJobs", definition);
        rejects(factory, "任务方法所在的 Bean 必须是单例：prototypeJobs");
    }

    @Test
    void aMethodMissingFromTheInterfaceOfAJdkProxyFailsStartup() {
        ProxyFactory proxy = new ProxyFactory(new UndeclaredJobs());
        proxy.addInterface(DeclaredJob.class);
        rejects(factoryWith("undeclaredJobs", proxy.getProxy()), "JDK 动态代理只暴露接口里声明的方法");
    }

    static class FinalJobs {
        @JobHandler("final")
        final void run() {
        }
    }

    static class PrivateJobs {
        @JobHandler("private")
        private void run() {
        }
    }

    @Test
    void finalOrPrivateMethodsOnAClassProxyFailStartup() {
        rejects(factoryWith("finalJobs", classProxy(new FinalJobs())), "final 方法不经过代理，去掉 final：finalJobs#run");
        rejects(factoryWith("privateJobs", classProxy(new PrivateJobs())), "代理不能调用 private 方法，改为包级、protected 或 public：privateJobs#run");
    }

    private static Object classProxy(Object target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        return factory.getProxy();
    }

    @Test
    void beansWithoutHandlersAreIgnored() {
        Map<String, JobMethod> found = JobHandlerRegistry.scan(factoryWith("plain", "a string bean"));
        assertThat(found).isEmpty();
    }
}
