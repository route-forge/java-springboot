package io.github.routeforge.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 层级归属标注，承载 Laravel 侧「分组 tier 透传」的等价语义：标在类或包上，整组路由继承同一层级。
 *
 * <p>与 Laravel 的 {@code Route::group(['tier' => ...])} 对应关系：
 * <ul>
 *   <li>Laravel 的嵌套 group「内层覆盖外层」= 本注解的 <b>方法 &gt; 类 &gt; 包</b> 就近覆盖；</li>
 *   <li>包级只能写在 {@code package-info.java} 上（Java 没有更好的包级标注位）；</li>
 *   <li>标在方法上时等价于显式 {@code @ForgeRoute(tier = ...)}，供「只用原生 mapping 注解」的宿主使用。</li>
 * </ul>
 *
 * <p>优先级仍是 SPEC §4.2 的五级不变：方法级（含本注解标在方法上）= 显式 &gt; 类/包级 = group 继承
 * &gt; classifier &gt; config match &gt; unassigned。也就是说类上标的层级会被方法上的任何显式表态覆盖，
 * 这与 Laravel「单条 {@code ->tier()} 胜过 group」一致。
 *
 * <p>层级名必须在 {@code forge.levels} 中存在；不存在时按 SPEC 走 {@code RF_BE_002}（显式路径）
 * 或在严格模式聚合里报到，不静默降级成 unassigned。
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PACKAGE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ForgeTier {

    /** 层级名，须与 {@code forge.levels.<name>} 对应。 */
    String value();
}
