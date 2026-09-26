package com.mars.cloud.sentinel.rule;

import com.alibaba.csp.sentinel.datasource.AbstractDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * 一个规则 Data ID 的数据源：启动期读取失败即抛出，运行期更新整批接受或整批拒绝。
 *
 * <p>不使用 {@code sentinel-datasource-nacos} 的 {@code NacosDataSource}：它在监听登记失败、初始读取失败与
 * 内容为空时都只写一条警告后继续运行，限流就此静默失效；它还为每个 Data ID 各建一个 Nacos 客户端。
 *
 * <p>校验与装入在构造时给出的锁里进行。同一个规则目录下的数据源共用一把锁：网关的两种规则在校验时要读
 * 另一种规则的现状，串行化后「删除分组」与「新增对它的引用」不会并发地都通过。
 *
 * @param <T> 规则管理器接受的集合类型
 * @since 2026-09-25
 */
public final class NacosRuleSource<T> extends AbstractDataSource<String, T> {

    private static final Logger log = LoggerFactory.getLogger(NacosRuleSource.class);

    private final RuleKind<T> kind;
    private final String dataId;
    private final RuleConfigSource configSource;
    private final Duration readTimeout;
    private final RuleUpdateRecorder recorder;
    private final Object lock;

    private String acceptedContent;
    private volatile int activeRules;
    private volatile boolean lastUpdateAccepted = true;
    private RuleConfigSource.Registration registration;

    public NacosRuleSource(RuleKind<T> kind, String dataId, RuleConfigSource configSource, Duration readTimeout,
                           RuleUpdateRecorder recorder) {
        this(kind, dataId, configSource, readTimeout, recorder, new Object());
    }

    /**
     * @param lock 校验与装入时持有的锁；同一个规则目录下的数据源传同一个对象
     */
    public NacosRuleSource(RuleKind<T> kind, String dataId, RuleConfigSource configSource, Duration readTimeout,
                           RuleUpdateRecorder recorder, Object lock) {
        super(kind::parse);
        this.kind = kind;
        this.dataId = dataId;
        this.configSource = configSource;
        this.readTimeout = readTimeout;
        this.recorder = recorder;
        this.lock = lock;
    }

    @Override
    public String readSource() throws Exception {
        return configSource.read(dataId, RuleType.GROUP, readTimeout);
    }

    /**
     * 读取、校验并装入规则，再登记变更监听。失败时不留下已登记的监听：应用配置所用的 Nacos 客户端是进程级的，
     * 留下的监听会一直引用这个作废的数据源。
     *
     * @throws IllegalStateException Data ID 不存在或读取失败、内容为空白、规则没有通过校验、监听登记失败
     */
    public void start() {
        RuleConfigSource.Registration registered;
        IllegalStateException failure;
        synchronized (lock) {
            String content = readForStartup();
            T rules;
            try {
                rules = parser.convert(content);
            } catch (RuleRejectedException ex) {
                throw startupFailure("规则没有通过校验：" + ex.getMessage(), ex);
            }
            kind.register(getProperty());
            install(content, rules);
            recorder.watch(kind.type(), () -> activeRules, () -> lastUpdateAccepted);
            try {
                registration = configSource.listen(dataId, RuleType.GROUP, this::onChange);
            } catch (Exception ex) {
                throw startupFailure("登记变更监听失败", ex);
            }
            // 读取与登记监听之间的修改不保证触发监听（Nacos 客户端以本机快照为监听的初始值），登记后再读一次补上
            try {
                onChange(readSource());
                return;
            } catch (Exception ex) {
                registered = registration;
                registration = null;
                failure = startupFailure("登记监听后的复核读取失败", ex);
            }
        }
        // 注销监听放在锁外，见 close()
        registered.close();
        throw failure;
    }

    void onChange(String content) {
        synchronized (lock) {
            if (content == null || content.isBlank()) {
                reject("配置已被删除或内容为空白；要清空规则请写 []");
                return;
            }
            if (Objects.equals(content, acceptedContent)) {
                lastUpdateAccepted = true;
                return;
            }
            T rules;
            try {
                rules = parser.convert(content);
            } catch (RuleRejectedException ex) {
                reject(ex.getMessage());
                return;
            } catch (RuntimeException ex) {
                log.error("Sentinel 规则被拒绝，保留上一批：dataId={}, group={}, type={}, 校验时出现意外错误",
                        dataId, RuleType.GROUP, kind.type().id(), ex);
                markRejected();
                return;
            }
            install(content, rules);
            recorder.accepted(kind.type());
            log.info("Sentinel 规则已更新：dataId={}, type={}, 生效 {} 条", dataId, kind.type().id(), activeRules);
        }
    }

    /**
     * 注销监听。注销本身在锁外进行：Nacos 客户端在它自己的缓存锁里同步调用监听器（进入 {@link #onChange} 等本组件的锁），
     * 注销又要取同一把缓存锁，若在本组件的锁内注销，与恰好到达的通知会形成锁序倒置。
     */
    @Override
    public void close() {
        RuleConfigSource.Registration registered;
        synchronized (lock) {
            registered = registration;
            registration = null;
        }
        if (registered != null) {
            registered.close();
        }
    }

    public RuleType type() {
        return kind.type();
    }

    public String dataId() {
        return dataId;
    }

    int activeRules() {
        return activeRules;
    }

    boolean lastUpdateAccepted() {
        return lastUpdateAccepted;
    }

    private void install(String content, T rules) {
        getProperty().updateValue(rules);
        acceptedContent = content;
        activeRules = kind.size(rules);
        lastUpdateAccepted = true;
    }

    private void reject(String reason) {
        log.error("Sentinel 规则被拒绝，保留上一批：dataId={}, group={}, type={}, 原因：{}",
                dataId, RuleType.GROUP, kind.type().id(), reason);
        markRejected();
    }

    private void markRejected() {
        lastUpdateAccepted = false;
        recorder.rejected(kind.type());
    }

    private String readForStartup() {
        String content;
        try {
            content = readSource();
        } catch (Exception ex) {
            throw startupFailure("读取失败", ex);
        }
        if (content == null) {
            // Nacos 客户端在读取出错或超时时会退回本机快照，没有快照的主机上得到的也是 null
            throw startupFailure("配置不存在或读取失败；不需要这类规则时写 []", null);
        }
        if (content.isBlank()) {
            throw startupFailure("内容为空白；不需要这类规则时写 []", null);
        }
        return content;
    }

    private IllegalStateException startupFailure(String reason, Throwable cause) {
        return new IllegalStateException("Sentinel 规则加载失败：dataId=" + dataId + ", group=" + RuleType.GROUP
                + ", type=" + kind.type().id() + "，" + reason, cause);
    }
}
