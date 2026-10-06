package io.github.routeforge.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * forge 元信息的<b>副注解</b>：叠加在原生 {@code @GetMapping} / {@code @PostMapping} 等映射注解上使用。
 *
 * <p>存在理由：{@link ForgeRoute} 是组合注解，映射条件靠 {@code @AliasFor} 透传；一旦宿主需要
 * Spring  later 新增的映射条件属性、或要用 {@code RouterFunction} 之类的非常规注册方式，
 * 组合注解就会「差一个属性」而写不出来。此时退回原生 mapping 注解 + 本注解，映射语义 100% 交给 Spring。
 *
 * <p>与 {@link ForgeRoute} 的优先级与冲突口径（SPEC §4.1）：本注解的 forge 事实让位于
 * {@code @ForgeRoute}（显式组合注解更靠近调用点）；<b>两者都表态且给出不同值</b>时不择一，
 * 启动期 fail-fast（RF_BE_010）——「一条被静默忽略」比「启动失败」难查得多。
 *
 * <p>只允许标在方法上：类级/包级唯一有效的 forge 事实是层级归属，那是 {@link ForgeTier} 的职责。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Forge {

    /** 路由名（前端调用用的键）。 */
    String name() default "";

    /** 显式层级名；留空则走 {@link ForgeTier} 继承 / classifier / match。 */
    String tier() default "";

    /** 别名声明，指向本路由；与 {@code forge.aliases} 并用时本处优先。 */
    String[] aliases() default {};

    /** 层级匹配用的中间件标签（元数据，不构成安全边界）。 */
    String[] middleware() default {};

    /** 可选路径参数名（产物渲染为 {@code {name?}}），必须是 URI 模板里存在的参数名。 */
    String[] optional() default {};

    /** 路径参数默认值，形如 {@code page=1}。 */
    String[] defaults() default {};
}
