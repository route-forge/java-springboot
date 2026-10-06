package io.github.routeforge.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 声明一条 forge 命名路由：一条注解同时给出 HTTP 映射与 forge 元信息。
 *
 * <p>组合注解形态（meta-annotated {@link RequestMapping}），映射语义完全由 Spring 处理：
 * {@code name / value / path / method / params / headers / consumes / produces / version}
 * 逐个 {@code @AliasFor(annotation = RequestMapping.class)} 透传，与直接写 {@code @RequestMapping}
 * <b>等价</b>（含条件属性；这一条由 {@code ForgeRoutePassthroughTest} 用两份控制器逐一比对钉住）。
 * Framework 7 的 {@code @RequestMapping} 共 9 个属性（比 6 多了 {@code version}），
 * 少透传一个就是宿主的能力缺口，因此这里逐个补齐而不是「常用六个够了」。
 *
 * <p>其余属性是 forge 自有语义，不参与 Spring 的映射判定：
 * <ul>
 *   <li>{@link #tier()} —— 显式层级标注（优先级链第 1 级）；</li>
 *   <li>{@link #aliases()} —— 别名声明，写在<b>被指向</b>的真实路由上；</li>
 *   <li>{@link #middleware()} —— 层级匹配用的元数据标签，<b>不是安全边界</b>（真实鉴权归 Spring Security）；</li>
 *   <li>{@link #optional()} / {@link #defaults()} —— 可选路径参数与默认值。Spring 的模板语法直接拒绝
 *       {@code {name?}}，所以"Laravel 风格的可选标记"只能由这两个属性承载，再由归一化拼进产物 URI；
 *       声明的名字若不在 URI 模板中，属于配置错误，在扫描期 fail-fast。</li>
 * </ul>
 *
 * <p>只允许标在方法上：类级前缀用原生 {@code @RequestMapping}，类/包级层级用 {@link ForgeTier}。
 * 若允许标在类上，{@code name}/{@code aliases}/{@code optional} 这类逐路由属性会「写了没作用」——
 * 这类静默丢弃正是 Laravel 侧专门发过告警（RF_BE_007）的坑，宁可不提供。
 *
 * <p>与 {@link Forge} 副注解同时声明同一方法时：映射仍由本注解提供，forge 事实若冲突则在启动期
 * fail-fast（RF_BE_010），不做静默择一。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RequestMapping
public @interface ForgeRoute {

    /** 路由名（forge 契约里前端调用用的键），如 {@code admin.users.show}。留空则交给命名策略或视为未命名。 */
    @AliasFor(annotation = RequestMapping.class)
    String name() default "";

    /** {@code @RequestMapping#value} 的透传（与 {@link #path()} 互为别名，二者不可同时给不同值）。 */
    @AliasFor(annotation = RequestMapping.class)
    String[] value() default {};

    /** {@code @RequestMapping#path} 的透传。 */
    @AliasFor(annotation = RequestMapping.class)
    String[] path() default {};

    /** {@code @RequestMapping#method} 的透传。留空表示不限方法（与 Spring 同语义）。 */
    @AliasFor(annotation = RequestMapping.class)
    RequestMethod[] method() default {};

    /** {@code @RequestMapping#params} 的透传。 */
    @AliasFor(annotation = RequestMapping.class)
    String[] params() default {};

    /** {@code @RequestMapping#headers} 的透传。 */
    @AliasFor(annotation = RequestMapping.class)
    String[] headers() default {};

    /** {@code @RequestMapping#consumes} 的透传。 */
    @AliasFor(annotation = RequestMapping.class)
    String[] consumes() default {};

    /** {@code @RequestMapping#produces} 的透传。 */
    @AliasFor(annotation = RequestMapping.class)
    String[] produces() default {};

    /** {@code @RequestMapping#version} 的透传（Framework 7 新增的 API 版本条件）。 */
    @AliasFor(annotation = RequestMapping.class)
    String version() default "";

    /** 显式层级名；留空表示不显式表态，交由 {@link ForgeTier} 继承、classifier 或 match 规则决定。 */
    String tier() default "";

    /** 别名（旧名/对外稳定名），指向本路由。与 {@code forge.aliases} 配置并用时本处优先。 */
    String[] aliases() default {};

    /** 层级匹配用的中间件标签集合（元数据，不构成安全边界）。 */
    String[] middleware() default {};

    /**
     * 可选路径参数名，产物 URI 里渲染为 Laravel 风格的 {@code {name?}}。
     *
     * <p>必须是 URI 模板里出现过的参数名，否则扫描期 fail-fast——静默忽略会让前端以为参数可省。
     */
    String[] optional() default {};

    /**
     * 路径参数默认值，形如 {@code page=1}；产物写入 {@code parameter_defaults}。
     *
     * <p>与 TS 类型的 {@code ?} 标记无关（可选性只看 {@link #optional()}），与 Laravel 侧口径一致。
     */
    String[] defaults() default {};
}
