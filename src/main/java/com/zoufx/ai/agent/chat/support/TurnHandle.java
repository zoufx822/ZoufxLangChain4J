package com.zoufx.ai.agent.chat.support;

/**
 * {@link Turn} 面向 {@link TurnRegistry} 外部消费者（stop/pending 端点）的最小视图——
 * 只收窄出定位 + 停止 + 抢终局这三件事，编译期挡掉 assistant/inlineMoods 等生成期内部状态。
 */
public interface TurnHandle {

    String turnId();

    String anchorId();

    String prompt();

    /** CAS 抢占本轮终局：落库路与停止路争，赢者定局。 */
    boolean claim();

    /** 停止本轮：掐断上游 LC4J 流 + dispose 后端订阅（后者触发管道 doFinally 清理）。 */
    void abort();
}
