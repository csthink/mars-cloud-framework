package com.mars.cloud.rocketmq.topology;

import com.mars.cloud.rocketmq.autoconfigure.RocketMqBinding;
import com.mars.cloud.rocketmq.autoconfigure.RocketMqBindingCatalog;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.remoting.protocol.route.BrokerData;
import org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 用 RocketMQ 管理接口在启动期核验或创建 binding 用到的主题与消费组。
 *
 * <p>broker 关闭自动创建时，缺主题只在发送时报「No route info」，缺消费组只让拉取持续失败，都不是启动期失败；
 * 这里把它们变成启动期失败，并在异常消息里给出创建命令。重试与死信主题由 broker 派生，不在清单里。
 *
 * @since 2026-09-22
 */
public class RocketMqTopologyManager {

    private static final Logger log = LoggerFactory.getLogger(RocketMqTopologyManager.class);

    private final String nameServer;

    public RocketMqTopologyManager(String nameServer) {
        this.nameServer = nameServer;
    }

    /** 缺失项。 */
    public record Missing(Set<String> topics, Map<String, Set<String>> groupsByBroker) {

        public boolean isEmpty() {
            return topics.isEmpty() && groupsByBroker.values().stream().allMatch(Set::isEmpty);
        }
    }

    /**
     * 按模式处理清单：核验时缺失即抛 {@link IllegalStateException}，创建时补齐缺失项。
     */
    public void apply(RocketMqTopologyMode mode, RocketMqBindingCatalog catalog, int queues) {
        if (mode == RocketMqTopologyMode.OFF) {
            return;
        }
        Set<String> topics = new LinkedHashSet<>();
        Set<String> groups = new LinkedHashSet<>();
        for (RocketMqBinding binding : catalog.bindings()) {
            topics.add(binding.topic());
            if (binding.kind() == RocketMqBinding.Kind.CONSUMER) {
                groups.add(binding.group());
            }
        }
        if (topics.isEmpty()) {
            return;
        }
        DefaultMQAdminExt admin = admin();
        try {
            ClusterInfo cluster = admin.examineBrokerClusterInfo();
            List<String> masters = masters(cluster);
            if (masters.isEmpty()) {
                throw new IllegalStateException("RocketMQ 集群里没有 master broker，无法核验主题与消费组: " + nameServer);
            }
            String clusterName = cluster.getClusterAddrTable().keySet().stream().findFirst().orElse("<cluster>");
            Missing missing = inspect(admin, masters, topics, groups);
            if (missing.isEmpty()) {
                log.info("RocketMQ 主题与消费组核验通过: topics={} groups={}", topics, groups);
                return;
            }
            if (mode == RocketMqTopologyMode.VERIFY) {
                throw new IllegalStateException(describe(missing, clusterName, queues));
            }
            provision(admin, masters, missing, queues);
            Missing again = inspect(admin, masters, topics, groups);
            if (!again.isEmpty()) {
                throw new IllegalStateException("创建后仍缺失，" + describe(again, clusterName, queues));
            }
            log.info("RocketMQ 主题与消费组已创建并核验: topics={} groups={}", topics, groups);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("RocketMQ 主题与消费组核验失败（name-server " + nameServer + "）: " + e, e);
        } finally {
            admin.shutdown();
        }
    }

    protected DefaultMQAdminExt admin() {
        DefaultMQAdminExt admin = new DefaultMQAdminExt();
        admin.setNamesrvAddr(nameServer);
        admin.setInstanceName("mars-topology-" + UUID.randomUUID());
        try {
            admin.start();
        } catch (MQClientException e) {
            throw new IllegalStateException("无法连接 RocketMQ 管理接口（name-server " + nameServer + "）: " + e.getMessage(), e);
        }
        return admin;
    }

    private static List<String> masters(ClusterInfo cluster) {
        List<String> masters = new ArrayList<>();
        for (BrokerData broker : cluster.getBrokerAddrTable().values()) {
            String master = broker.getBrokerAddrs().get(0L);
            if (master != null) {
                masters.add(master);
            }
        }
        return masters;
    }

    private static Missing inspect(DefaultMQAdminExt admin, List<String> masters, Set<String> topics, Set<String> groups) throws Exception {
        Set<String> missingTopics = new LinkedHashSet<>();
        for (String topic : topics) {
            if (!topicExists(admin, topic)) {
                missingTopics.add(topic);
            }
        }
        Map<String, Set<String>> missingGroups = new java.util.LinkedHashMap<>();
        for (String master : masters) {
            Set<String> absent = new LinkedHashSet<>();
            for (String group : groups) {
                SubscriptionGroupConfig config = admin.examineSubscriptionGroupConfig(master, group);
                if (config == null) {
                    absent.add(group);
                }
            }
            missingGroups.put(master, absent);
        }
        return new Missing(missingTopics, missingGroups);
    }

    private static boolean topicExists(DefaultMQAdminExt admin, String topic) throws Exception {
        try {
            return admin.examineTopicRouteInfo(topic) != null;
        } catch (MQClientException e) {
            if (e.getResponseCode() == ResponseCode.TOPIC_NOT_EXIST) {
                return false;
            }
            throw e;
        }
    }

    private static void provision(DefaultMQAdminExt admin, List<String> masters, Missing missing, int queues) throws Exception {
        for (String master : masters) {
            for (String topic : missing.topics()) {
                TopicConfig config = new TopicConfig(topic, queues, queues, PermName.PERM_READ | PermName.PERM_WRITE);
                admin.createAndUpdateTopicConfig(master, config);
                log.info("已在 broker {} 创建主题 {}（读写队列 {}）", master, topic, queues);
            }
            for (String group : missing.groupsByBroker().getOrDefault(master, Set.of())) {
                SubscriptionGroupConfig config = new SubscriptionGroupConfig();
                config.setGroupName(group);
                admin.createAndUpdateSubscriptionGroupConfig(master, config);
                log.info("已在 broker {} 创建消费组 {}", master, group);
            }
        }
    }

    private static String describe(Missing missing, String clusterName, int queues) {
        StringBuilder text = new StringBuilder("RocketMQ 缺少 binding 需要的主题或消费组，请创建后再启动，或在本机运行环境设置 MARS_ROCKETMQ_TOPOLOGY=provision：");
        for (String topic : missing.topics()) {
            text.append("\n  mqadmin updateTopic -c ").append(clusterName).append(" -t ").append(topic)
                    .append(" -r ").append(queues).append(" -w ").append(queues);
        }
        Set<String> groups = new LinkedHashSet<>();
        missing.groupsByBroker().values().forEach(groups::addAll);
        for (String group : groups) {
            text.append("\n  mqadmin updateSubGroup -c ").append(clusterName).append(" -g ").append(group);
        }
        return text.toString();
    }
}
