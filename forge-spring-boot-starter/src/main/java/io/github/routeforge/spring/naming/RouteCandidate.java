package io.github.routeforge.spring.naming;

/**
 * 命名策略的输入：派生一个名字所需的最小事实。
 *
 * <p>刻意不直接给 Spring 的 {@code HandlerMethod}：那样 SPI 就与具体框架版本绑死，
 * 实现方也拿不到「这条候选来自哪个 mapping 注解」以外的信息。若实现确需反射宿主方法
 * （读注解、看包名），用 {@code handlerType} + {@code handlerMethod} 自行定位即可。
 *
 * @param handlerType   handler 所在类
 * @param handlerMethod handler 方法名（不含参数）
 * @param pattern       该候选的 URI 模板原文（可能含 {@code {name:regex}} 约束，未归一化）
 */
public record RouteCandidate(Class<?> handlerType, String handlerMethod, String pattern) {
}
