package com.zoufx.ai.agent.chat.model;

import org.jspecify.annotations.Nullable;

/**
 * 思考模式配置。{@code enabled}=是否开启思考；{@code effort}=思考深度档（仅 enabled 时生效）。
 *
 * <p>深度从属于开关：紧凑构造器在创建时归一化——未开启思考则 effort 强制为 null，
 * 故访问器 {@code effort()} 天然返回门控后的值（关闭思考时恒为 null，端口据此走快档）。
 */
public record Thinking(boolean enabled, @Nullable String effort) {

    /** 思考关闭时的规范值。 */
    public static final Thinking OFF = new Thinking(false, null);

    public Thinking {
        if (!enabled) effort = null;   // 没开思考就谈不上深度，归一化为 null
    }
}
