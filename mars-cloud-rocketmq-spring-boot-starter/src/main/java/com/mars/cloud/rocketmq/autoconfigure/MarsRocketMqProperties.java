package com.mars.cloud.rocketmq.autoconfigure;

import com.mars.cloud.rocketmq.topology.RocketMqTopologyMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * starter 自己的配置项，前缀 {@code mars.rocketmq}。
 *
 * <p>运行环境相关的取值只从环境变量来：{@code MARS_MQ_PREFIX} 映射到 {@link #getPrefix()}，
 * {@code MARS_ROCKETMQ_TOPOLOGY} 映射到 {@link #getTopology()}，{@code ROCKETMQ_NAME_SERVER} 映射到
 * binder 的 {@code spring.cloud.stream.rocketmq.binder.name-server}。
 *
 * @since 2026-09-22
 */
@ConfigurationProperties(prefix = "mars.rocketmq")
public class MarsRocketMqProperties {

    /** 运行环境前缀，形如 {@code s1-}；加到全部主题名与消费组名前面，空字符串表示不加。 */
    private String prefix = "";

    /** 启动期对主题与消费组的处理：核验、创建或不检查。 */
    private RocketMqTopologyMode topology = RocketMqTopologyMode.VERIFY;

    /** 创建主题时的读写队列数。 */
    private int topicQueues = 4;

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix == null ? "" : prefix.trim();
    }

    public RocketMqTopologyMode getTopology() {
        return topology;
    }

    public void setTopology(RocketMqTopologyMode topology) {
        this.topology = topology;
    }

    public int getTopicQueues() {
        return topicQueues;
    }

    public void setTopicQueues(int topicQueues) {
        this.topicQueues = topicQueues;
    }
}
