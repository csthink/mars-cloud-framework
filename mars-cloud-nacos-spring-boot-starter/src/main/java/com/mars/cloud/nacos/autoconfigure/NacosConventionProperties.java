package com.mars.cloud.nacos.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nacos 约定校验开关。
 */
@ConfigurationProperties(prefix = "mars.nacos.convention")
public class NacosConventionProperties {

    /**
     * 是否在启动期校验 Namespace、Group、Data ID 与导入顺序。
     */
    private boolean validationEnabled = true;

    public boolean isValidationEnabled() {
        return validationEnabled;
    }

    public void setValidationEnabled(boolean validationEnabled) {
        this.validationEnabled = validationEnabled;
    }
}
