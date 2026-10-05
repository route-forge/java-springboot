package io.github.routeforge.core.support;

/**
 * 核心层的非致命问题出口。
 *
 * <p>刻意不依赖任何日志框架（核心层零依赖）：适配层把它桥到 SLF4J，命令行把它收进 {@code warnings} 数组。
 * 同一条消息的两个消费方口径必须一致，因此文案由核心层单点生成，适配层不得改写。
 */
public interface WarningSink {

    /** 不收集任何东西的静默实现（纯单元测试用）。 */
    WarningSink NOOP = message -> {
    };

    void warning(String message);
}
