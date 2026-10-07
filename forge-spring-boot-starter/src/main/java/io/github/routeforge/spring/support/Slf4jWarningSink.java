package io.github.routeforge.spring.support;

import io.github.routeforge.core.support.WarningSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把核心层的非致命告警接到 SLF4J。
 *
 * <p>核心层刻意零依赖（{@link WarningSink} 只是个函数式接口），文案由核心层单点生成；
 * 适配层只负责搬运，<b>不改写、不加前缀</b>——同一句文案也被 {@code --forge:list} 的 warnings 通道
 * 与管理器页面消费，加了前缀就让三个出口的话不再可比。
 */
public final class Slf4jWarningSink implements WarningSink {

    private static final Logger LOGGER = LoggerFactory.getLogger("io.github.routeforge");

    @Override
    public void warning(String message) {
        LOGGER.warn(message);
    }
}
