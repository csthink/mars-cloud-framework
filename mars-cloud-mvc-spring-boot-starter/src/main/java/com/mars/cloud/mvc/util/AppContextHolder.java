package com.mars.cloud.mvc.util;

import com.mars.cloud.mvc.env.EnvProfilesProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @since 2025-05-07 14:29
 */
@RequiredArgsConstructor
public class AppContextHolder implements ApplicationContextAware {

    private static final Set<String> DEV_PROFILE = Stream.of("local", "dev", "test", "testing")
            .collect(Collectors.toSet());

    @Getter
    private static ApplicationContext applicationContext;


    private final Environment env;
    private final EnvProfilesProperties envProps;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        AppContextHolder.applicationContext = applicationContext;
    }

    public static Object getBean(String name) {
        return applicationContext.getBean(name);
    }

    public static <T> T getBean(String name, Class<T> clazz) {
        return applicationContext.getBean(name, clazz);
    }

    public static <T> T getBean(Class<T> clazz) {
        return applicationContext.getBean(clazz);
    }

    public static <T> Map<String, T> getBeansOfType(Class<T> clazz) {
        return applicationContext.getBeansOfType(clazz);
    }


    //public boolean isDevEnv() {
    //    String[] activeProfiles = env.getActiveProfiles();
    //    if (ArrayUtils.isEmpty(activeProfiles)) {
    //        return false;
    //    }
    //    return Arrays.stream(activeProfiles).anyMatch(DEV_PROFILE::contains);
    //}

    public boolean isDevEnv() {
        return envProps.isDev(env.getActiveProfiles());
    }

}
