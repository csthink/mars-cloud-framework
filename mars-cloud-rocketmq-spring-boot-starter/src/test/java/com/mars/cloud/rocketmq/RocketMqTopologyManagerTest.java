package com.mars.cloud.rocketmq;

import com.mars.cloud.rocketmq.autoconfigure.BindingPrefixApplier;
import com.mars.cloud.rocketmq.autoconfigure.RocketMqBindingCatalog;
import com.mars.cloud.rocketmq.topology.RocketMqTopologyManager;
import com.mars.cloud.rocketmq.topology.RocketMqTopologyMode;
import com.alibaba.cloud.stream.binder.rocketmq.properties.RocketMQExtendedBindingProperties;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.remoting.protocol.ResponseCode;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.remoting.protocol.route.BrokerData;
import org.apache.rocketmq.remoting.protocol.route.TopicRouteData;
import org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RocketMqTopologyManagerTest {

    private static final String MASTER = "127.0.0.1:30911";

    private static RocketMqBindingCatalog catalog(String prefix) {
        BindingServiceProperties properties = new BindingServiceProperties();
        BindingProperties consumer = new BindingProperties();
        consumer.setDestination("order-event");
        consumer.setGroup("mars-cloud-product-service-order-event");
        BindingProperties producer = new BindingProperties();
        producer.setDestination("entitlement-event");
        Map<String, BindingProperties> bindings = new HashMap<>();
        bindings.put("orderPaid-in-0", consumer);
        bindings.put("granted-out-0", producer);
        properties.setBindings(bindings);
        BindingPrefixApplier applier = new BindingPrefixApplier(new MockEnvironment().withProperty("mars.rocketmq.prefix", prefix));
        applier.postProcessAfterInitialization(properties, "bindingServiceProperties");
        RocketMQExtendedBindingProperties extended = new RocketMQExtendedBindingProperties();
        org.springframework.context.support.GenericApplicationContext context = new org.springframework.context.support.GenericApplicationContext();
        context.refresh();
        extended.setApplicationContext(context);
        return new RocketMqBindingCatalog(properties, extended, applier);
    }

    private static DefaultMQAdminExt admin(Set<String> existingTopics, Set<String> existingGroups) throws Exception {
        DefaultMQAdminExt admin = mock(DefaultMQAdminExt.class);
        ClusterInfo cluster = new ClusterInfo();
        BrokerData broker = new BrokerData();
        broker.setBrokerName("local-broker");
        HashMap<Long, String> addresses = new HashMap<>();
        addresses.put(0L, MASTER);
        broker.setBrokerAddrs(addresses);
        cluster.setBrokerAddrTable(new HashMap<>(Map.of("local-broker", broker)));
        cluster.setClusterAddrTable(new HashMap<>(Map.of("LocalCluster", new HashSet<>(Set.of("local-broker")))));
        when(admin.examineBrokerClusterInfo()).thenReturn(cluster);
        when(admin.examineTopicRouteInfo(any())).thenAnswer(invocation -> {
            String topic = invocation.getArgument(0);
            if (existingTopics.contains(topic)) {
                return new TopicRouteData();
            }
            throw new MQClientException(ResponseCode.TOPIC_NOT_EXIST, "No topic route info in name server for the topic: " + topic);
        });
        when(admin.examineSubscriptionGroupConfig(eq(MASTER), any())).thenAnswer(invocation -> {
            String group = invocation.getArgument(1);
            if (!existingGroups.contains(group)) {
                return null;
            }
            SubscriptionGroupConfig config = new SubscriptionGroupConfig();
            config.setGroupName(group);
            return config;
        });
        return admin;
    }

    private static RocketMqTopologyManager manager(DefaultMQAdminExt admin) {
        return new RocketMqTopologyManager("127.0.0.1:29876") {
            @Override
            protected DefaultMQAdminExt admin() {
                return admin;
            }
        };
    }

    @Test
    void verifyPassesWhenEverythingExists() throws Exception {
        DefaultMQAdminExt admin = admin(Set.of("s1-order-event", "s1-entitlement-event"), Set.of("s1-mars-cloud-product-service-order-event"));
        manager(admin).apply(RocketMqTopologyMode.VERIFY, catalog("s1-"), 4);
        verify(admin, never()).createAndUpdateTopicConfig(any(), any());
        verify(admin).shutdown();
    }

    @Test
    void verifyFailsWithTheCommandsToCreateWhatIsMissing() throws Exception {
        DefaultMQAdminExt admin = admin(Set.of("s1-order-event"), Set.of());
        assertThatIllegalStateException()
                .isThrownBy(() -> manager(admin).apply(RocketMqTopologyMode.VERIFY, catalog("s1-"), 8))
                .withMessageContaining("mqadmin updateTopic -c LocalCluster -t s1-entitlement-event -r 8 -w 8")
                .withMessageContaining("mqadmin updateSubGroup -c LocalCluster -g s1-mars-cloud-product-service-order-event")
                .withMessageContaining("MARS_ROCKETMQ_TOPOLOGY=provision");
        verify(admin, never()).createAndUpdateTopicConfig(any(), any());
        verify(admin).shutdown();
    }

    @Test
    void provisionCreatesMissingItemsOnTheMasterAndReChecks() throws Exception {
        Set<String> topics = new HashSet<>(Set.of("order-event"));
        Set<String> groups = new HashSet<>();
        DefaultMQAdminExt admin = admin(topics, groups);
        org.mockito.Mockito.doAnswer(invocation -> {
            topics.add(((TopicConfig) invocation.getArgument(1)).getTopicName());
            return null;
        }).when(admin).createAndUpdateTopicConfig(eq(MASTER), any());
        org.mockito.Mockito.doAnswer(invocation -> {
            groups.add(((SubscriptionGroupConfig) invocation.getArgument(1)).getGroupName());
            return null;
        }).when(admin).createAndUpdateSubscriptionGroupConfig(eq(MASTER), any());

        manager(admin).apply(RocketMqTopologyMode.PROVISION, catalog(""), 4);

        ArgumentCaptor<TopicConfig> topic = ArgumentCaptor.forClass(TopicConfig.class);
        verify(admin).createAndUpdateTopicConfig(eq(MASTER), topic.capture());
        assertThat(topic.getValue().getTopicName()).isEqualTo("entitlement-event");
        assertThat(topic.getValue().getReadQueueNums()).isEqualTo(4);
        assertThat(topic.getValue().getWriteQueueNums()).isEqualTo(4);
        ArgumentCaptor<SubscriptionGroupConfig> group = ArgumentCaptor.forClass(SubscriptionGroupConfig.class);
        verify(admin).createAndUpdateSubscriptionGroupConfig(eq(MASTER), group.capture());
        assertThat(group.getValue().getGroupName()).isEqualTo("mars-cloud-product-service-order-event");
        verify(admin).shutdown();
    }

    @Test
    void offDoesNothing() throws Exception {
        DefaultMQAdminExt admin = admin(Set.of(), Set.of());
        manager(admin).apply(RocketMqTopologyMode.OFF, catalog("s1-"), 4);
        verify(admin, never()).examineBrokerClusterInfo();
    }

    @Test
    void unexpectedAdminFailureIsReportedWithTheNameServer() throws Exception {
        DefaultMQAdminExt admin = mock(DefaultMQAdminExt.class);
        when(admin.examineBrokerClusterInfo()).thenThrow(new org.apache.rocketmq.remoting.exception.RemotingConnectException("127.0.0.1:29876"));
        assertThatIllegalStateException()
                .isThrownBy(() -> manager(admin).apply(RocketMqTopologyMode.VERIFY, catalog(""), 4))
                .withMessageContaining("127.0.0.1:29876");
        verify(admin).shutdown();
    }
}
