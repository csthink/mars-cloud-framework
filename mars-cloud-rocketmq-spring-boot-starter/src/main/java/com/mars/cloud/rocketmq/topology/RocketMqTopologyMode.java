package com.mars.cloud.rocketmq.topology;

/**
 * 启动期对主题与消费组的处理方式。
 *
 * @since 2026-09-22
 */
public enum RocketMqTopologyMode {

    /** 核验每个 binding 的主题与消费组都存在，缺失即启动失败。生产环境的默认值。 */
    VERIFY,

    /** 缺失的主题与消费组在每个 master broker 上创建，已存在的不改。本机运行环境使用。 */
    PROVISION,

    /** 不检查。只给不能连管理接口的特殊环境用。 */
    OFF
}
