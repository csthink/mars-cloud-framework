package com.mars.cloud.sentinel.rule;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 在应用配置导入所用的同一个 {@link ConfigService} 上读取规则，命名空间与凭据因此与应用配置相同，不另开连接。
 *
 * @since 2026-09-25
 */
public final class NacosRuleConfigSource implements RuleConfigSource {

    private static final Logger log = LoggerFactory.getLogger(NacosRuleConfigSource.class);

    private final ConfigService configService;

    public NacosRuleConfigSource(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String read(String dataId, String group, Duration timeout) throws NacosException {
        return configService.getConfig(dataId, group, timeout.toMillis());
    }

    @Override
    public Registration listen(String dataId, String group, Consumer<String> listener) throws NacosException {
        AbstractListener nacosListener = new AbstractListener() {
            @Override
            public void receiveConfigInfo(String configInfo) {
                listener.accept(configInfo);
            }
        };
        configService.addListener(dataId, group, nacosListener);
        return () -> {
            try {
                configService.removeListener(dataId, group, nacosListener);
            } catch (RuntimeException ex) {
                log.warn("注销 Sentinel 规则监听失败：dataId={}, group={}", dataId, group, ex);
            }
        };
    }
}
