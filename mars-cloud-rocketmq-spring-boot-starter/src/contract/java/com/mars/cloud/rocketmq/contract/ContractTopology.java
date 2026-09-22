package com.mars.cloud.rocketmq.contract;

import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.remoting.protocol.route.BrokerData;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 契约测试的收尾：删除测试自己创建的主题、消费组与派生的重试、死信主题。只在测试源码里，不随 starter 发布。
 */
final class ContractTopology {

    private ContractTopology() {
    }

    static void delete(String nameServer, Set<String> topics, Set<String> groups) throws Exception {
        DefaultMQAdminExt admin = new DefaultMQAdminExt();
        admin.setNamesrvAddr(nameServer);
        admin.setInstanceName("contract-cleanup-" + UUID.randomUUID());
        admin.start();
        try {
            ClusterInfo cluster = admin.examineBrokerClusterInfo();
            Set<String> masters = new HashSet<>();
            for (BrokerData broker : cluster.getBrokerAddrTable().values()) {
                String master = broker.getBrokerAddrs().get(0L);
                if (master != null) {
                    masters.add(master);
                }
            }
            Set<String> nameServers = new HashSet<>(List.of(nameServer.split("[;,]")));
            for (String master : masters) {
                for (String group : groups) {
                    admin.deleteSubscriptionGroup(master, group, true);
                }
            }
            for (String topic : topics) {
                admin.deleteTopicInBroker(masters, topic);
                admin.deleteTopicInNameServer(nameServers, topic);
            }
        } finally {
            admin.shutdown();
        }
    }
}
