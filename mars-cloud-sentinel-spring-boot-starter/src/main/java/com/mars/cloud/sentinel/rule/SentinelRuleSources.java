package com.mars.cloud.sentinel.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 在单例初始化阶段逐个装入规则：任一 Data ID 不存在、为空白、读取失败或没有通过校验，应用启动失败。
 * Web 服务器在这之后才开始接收请求。
 *
 * <p>同一个目录下的数据源共用一把锁，运行期不同规则类型的更新串行地校验与装入；网关的两种规则互相引用，
 * 校验时读到的另一种规则的现状因此不会在装入前被改掉。
 *
 * @since 2026-09-25
 */
public final class SentinelRuleSources implements SmartInitializingSingleton, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SentinelRuleSources.class);

    private final String applicationName;
    private final SentinelRuleCatalog catalog;
    private final RuleConfigSource configSource;
    private final Duration readTimeout;
    private final RuleUpdateRecorder recorder;
    private final Object lock = new Object();
    private final List<NacosRuleSource<?>> sources = new ArrayList<>();

    public SentinelRuleSources(String applicationName, SentinelRuleCatalog catalog, RuleConfigSource configSource,
                               Duration readTimeout, RuleUpdateRecorder recorder) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalStateException("Sentinel 规则的 Data ID 以 spring.application.name 开头，它不能为空");
        }
        this.applicationName = applicationName;
        this.catalog = catalog;
        this.configSource = configSource;
        this.readTimeout = readTimeout;
        this.recorder = recorder;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            for (RuleKind<?> kind : catalog.kinds()) {
                start(kind);
            }
        } catch (RuntimeException ex) {
            closeAll();
            throw ex;
        }
        log.info("Sentinel 规则已从 Nacos 装入：{}", sources.stream()
                .map(source -> source.dataId() + "=" + source.activeRules() + " 条")
                .toList());
    }

    private <T> void start(RuleKind<T> kind) {
        NacosRuleSource<T> source = new NacosRuleSource<>(kind, kind.type().dataId(applicationName), configSource,
                readTimeout, recorder, lock);
        source.start();
        sources.add(source);
    }

    public List<NacosRuleSource<?>> sources() {
        return List.copyOf(sources);
    }

    @Override
    public void destroy() {
        closeAll();
    }

    private void closeAll() {
        sources.forEach(NacosRuleSource::close);
        sources.clear();
    }
}
