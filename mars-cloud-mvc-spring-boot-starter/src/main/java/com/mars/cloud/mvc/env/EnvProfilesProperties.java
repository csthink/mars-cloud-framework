package com.mars.cloud.mvc.env;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @since 2025-10-30 10:58
 * 环境 Profile 配置
 * - 通过 mars.env.dev-profiles 配置哪些 profile 代表“开发/测试环境”
 * - 默认包含：local、dev、test、testing
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mars.env")
public class EnvProfilesProperties {

    /**
     * 认为是“开发/测试环境”的 profile 列表（大小写不敏感）
     * 可在 application.yml 里覆盖：mars.env.dev-profiles: [local, dev, test, testing]
     */
    private List<String> devProfiles = new ArrayList<>(Arrays.asList("local", "dev", "test", "testing"));

    /**
     * 工具方法：判断 activeProfiles 是否命中 devProfiles（忽略大小写）
     */
    public boolean isDev(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return false;
        }

        for (String p : activeProfiles) {
            if (p == null) continue;
            for (String dp : devProfiles) {
                if (dp != null && dp.equalsIgnoreCase(p)) {
                    return true;
                }
            }
        }

        return false;
    }
}
