package io.github.routeforge.spring.annotation;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import org.springframework.lang.Nullable;

/**
 * {@link ForgeTier} 的就近解析：方法 &gt; 类 &gt; 包，等价 Laravel 嵌套 group 的「内层覆盖外层」。
 *
 * <p>不用 {@code AnnotatedElementUtils.findMergedAnnotation} 一路找上去：它对方法不会回溯到声明类，
 * 而从类到包的查找又会把「类上标了、方法上也标了」的情形按 Spring 的祖先顺序处理，
 * 层级归属的覆盖方向必须由我们自己钉死并可单测。
 *
 * <p>只找<b>第一个</b>命中的（就近优先），不做多值合并：一条路由只能属于一个层级。
 */
public final class ForgeTiers {

    private ForgeTiers() {
    }

    /** 解析 handler 方法生效的层级标注；三层都没有时返回 {@code null}（交给 classifier / match / unassigned）。 */
    @Nullable
    public static String resolve(Method method) {
        ForgeTier declared = on(method);
        if (declared == null) {
            declared = on(method.getDeclaringClass());
        }
        if (declared == null) {
            declared = on(method.getDeclaringClass().getPackage());
        }
        return declared == null ? null : declared.value();
    }

    /** 层级标注的来源说明，用于诊断输出（{@code --forge:list} 与启动期冲突报告）。 */
    public static String sourceOf(Method method) {
        if (on(method) != null) {
            return "method @" + method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        }
        Class<?> owner = method.getDeclaringClass();
        if (on(owner) != null) {
            return "class @" + owner.getSimpleName();
        }
        if (on(owner.getPackage()) != null) {
            return "package " + owner.getPackageName();
        }
        return "none";
    }

    @Nullable
    private static ForgeTier on(@Nullable AnnotatedElement element) {
        if (element == null) {
            return null;
        }
        // getDeclaredAnnotation：只认「标在这一层」的，不做继承，保证就近覆盖方向可控
        return element.getDeclaredAnnotation(ForgeTier.class);
    }
}
