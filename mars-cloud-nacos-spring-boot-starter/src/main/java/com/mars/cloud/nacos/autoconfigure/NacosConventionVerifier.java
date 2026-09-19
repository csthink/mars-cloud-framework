package com.mars.cloud.nacos.autoconfigure;

import com.mars.cloud.nacos.NacosConfigDataConvention;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在应用上下文创建阶段校验 mars-cloud 的 Nacos 接入约定。
 */
final class NacosConventionVerifier implements InitializingBean {

    private final Environment environment;
    private final NacosConventionProperties properties;

    NacosConventionVerifier(Environment environment, NacosConventionProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.isValidationEnabled()) {
            return;
        }

        boolean configEnabled = enabled("spring.cloud.nacos.config.enabled");
        boolean discoveryEnabled = enabled("spring.cloud.nacos.discovery.enabled");
        if (!configEnabled && !discoveryEnabled) {
            return;
        }

        String applicationName = required("spring.application.name");
        NacosConfigDataConvention.validateApplicationName(applicationName);

        String configNamespace = configEnabled
                ? required("spring.cloud.nacos.config.namespace")
                : null;
        String discoveryNamespace = discoveryEnabled
                ? required("spring.cloud.nacos.discovery.namespace")
                : null;

        if (configEnabled && discoveryEnabled && !configNamespace.equals(discoveryNamespace)) {
            throw new IllegalStateException(
                    "Nacos Config 与 Discovery 必须使用同一个 Namespace ID");
        }

        if (discoveryEnabled) {
            String discoveryGroup = environment.getProperty(
                    "spring.cloud.nacos.discovery.group",
                    NacosConfigDataConvention.APPLICATION_GROUP);
            if (!NacosConfigDataConvention.APPLICATION_GROUP.equals(discoveryGroup)) {
                throw new IllegalStateException(
                        "Nacos Discovery group 必须是 "
                                + NacosConfigDataConvention.APPLICATION_GROUP);
            }
        }

        if (configEnabled) {
            verifyImports(applicationName);
        }
    }

    private boolean enabled(String key) {
        return environment.getProperty(key, Boolean.class, true);
    }

    private String required(String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 必须显式配置且不能为空");
        }
        return value.trim();
    }

    private void verifyImports(String applicationName) {
        List<String> imports = Binder.get(environment)
                .bind("spring.config.import", Bindable.listOf(String.class))
                .orElse(List.of())
                .stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();

        int sharedIndex = indexOf(imports,
                NacosConfigDataConvention.SHARED_DATA_ID,
                NacosConfigDataConvention.SHARED_GROUP);
        int applicationIndex = indexOf(imports,
                applicationName + ".yaml",
                NacosConfigDataConvention.APPLICATION_GROUP);

        if (sharedIndex < 0 || applicationIndex < 0) {
            throw new IllegalStateException(
                    "spring.config.import 必须包含且不得 optional: "
                            + NacosConfigDataConvention.requiredImports(applicationName));
        }
        if (sharedIndex >= applicationIndex) {
            throw new IllegalStateException(
                    "spring.config.import 必须先导入共享配置，再导入应用配置");
        }
    }

    private int indexOf(List<String> imports, String expectedDataId, String expectedGroup) {
        for (int index = 0; index < imports.size(); index++) {
            NacosImport parsed = parse(imports.get(index));
            if (!parsed.optional()
                    && expectedDataId.equals(parsed.dataId())
                    && expectedGroup.equals(parsed.parameters().get("group"))
                    && "true".equalsIgnoreCase(parsed.parameters().get("refreshEnabled"))) {
                return index;
            }
        }
        return -1;
    }

    private NacosImport parse(String rawLocation) {
        String location = rawLocation.trim();
        boolean optional = location.startsWith("optional:");
        if (optional) {
            location = location.substring("optional:".length());
        }
        if (!location.startsWith("nacos:")) {
            return new NacosImport(optional, "", Map.of());
        }

        String value = location.substring("nacos:".length());
        int queryStart = value.indexOf('?');
        String dataId = queryStart < 0 ? value : value.substring(0, queryStart);
        Map<String, String> parameters = new LinkedHashMap<>();
        if (queryStart >= 0 && queryStart + 1 < value.length()) {
            for (String part : value.substring(queryStart + 1).split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2) {
                    parameters.put(pair[0], pair[1]);
                }
            }
        }
        return new NacosImport(optional, dataId, Map.copyOf(parameters));
    }

    private record NacosImport(boolean optional, String dataId, Map<String, String> parameters) {
    }
}
