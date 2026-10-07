package io.github.routeforge.spring.scan;

import io.github.routeforge.core.support.WarningSink;
import io.github.routeforge.spring.annotation.Forge;
import io.github.routeforge.spring.annotation.ForgeRoute;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 启动后一次性扫描「把 forge 注解写在永远不会成为 handler 的位置」的误用，经 {@link WarningSink} 提示。
 *
 * <p>参照 Laravel 的 {@code ForgeRouteRegistrar::__destruct}（声明了属性却无消费方 → 记日志、<b>刻意不抛</b>）。
 * Spring 侧的对应误用：{@code @Forge} 标在方法上但该方法没有请求映射（永不成为 handler）、或
 * {@code @ForgeRoute} 标在一个 {@code @Component} 而非 {@code @Controller}/{@code @RestController} 的 bean 上
 * （类不是 handler，映射不生效）。这类标记会<b>静默失效</b>——路由进不了任何端点、{@code --forge:list}、d.ts 或
 * 严格扫描，宿主却以为生效了。
 *
 * <p>为什么必须在 bean 定义层面扫：{@link HandlerMethodRouteSource} 只枚举<b>已注册</b>的 handler，
 * 结构上看不见「没成为 handler 的注解」。故这里反过来——拿全量已注册 handler 的签名集，再看每个 bean 里
 * 带 forge 标记的方法是否落在这个集外。
 *
 * <p>成本与误报（按用户 2026-10-07 拍板）：<b>只在 {@code debug=true} 或 {@code strict=true} 时启用</b>，
 * 生产两者皆非则 {@link #afterSingletonsInstantiated()} 直接返回、零反射。范围锁在「带 forge 标记且本类
 * 未注册任何 handler」的局部方法，是近零误报的一档；{@code @ForgeTier} 的类/包级误放（继承、package-info、
 * 懒加载场景误报面大）<b>刻意不纳入</b>；完全没被任何 stereotype 纳入容器的类（连 bean 都不是）也扫不到
 * ——那需要 classpath 扫描，代价与误报都不可接受。
 *
 * <p>severity：恒为 {@code warning}（不给 {@link WarningSink} 扩 error 通道）——这些位置本就进不了 forge 产物，
 * 无法成为可 catch 的 strict error；Laravel 的 strict→error / 否则 warning 分级差异记进 SPEC。绝不抛异常。
 */
public final class ForgeAnnotationSanityChecker implements SmartInitializingSingleton {

    private final List<RequestMappingHandlerMapping> mappings;
    private final ListableBeanFactory beanFactory;
    private final WarningSink warnings;
    private final boolean enabled;

    public ForgeAnnotationSanityChecker(List<RequestMappingHandlerMapping> mappings,
            ListableBeanFactory beanFactory, WarningSink warnings, boolean enabled) {
        this.mappings = List.copyOf(mappings);
        this.beanFactory = beanFactory;
        this.warnings = warnings;
        this.enabled = enabled;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!enabled) {
            return;
        }
        Set<String> registered = registeredHandlerKeys();
        Set<String> handlerClasses = handlerDeclaringClasses(registered);
        Set<String> reported = new LinkedHashSet<>(); // 同签名的 bean 别名只报一次

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> userType = userType(beanName);
            if (userType == null) {
                continue;
            }
            boolean classContributesHandlers = handlerClasses.contains(userType.getName());
            for (Method method : userType.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic() || !carriesForgeMarker(method)) {
                    continue;
                }
                String key = signature(method);
                if (registered.contains(key) || !reported.add(key)) {
                    continue;
                }
                warnings.warning(inertMessage(userType, method, classContributesHandlers));
            }
        }
    }

    /** 已注册 handler 的方法签名集（以 {@link HandlerMethod#getMethod()} 的声明类为基准）。 */
    private Set<String> registeredHandlerKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (RequestMappingHandlerMapping mapping : mappings) {
            mapping.getHandlerMethods().values().forEach(handler -> keys.add(signature(handler.getMethod())));
        }
        return keys;
    }

    private static Set<String> handlerDeclaringClasses(Set<String> registeredKeys) {
        Set<String> classes = new LinkedHashSet<>();
        for (String key : registeredKeys) {
            classes.add(key.substring(0, key.indexOf('#')));
        }
        return classes;
    }

    private Class<?> userType(String beanName) {
        try {
            Class<?> type = beanFactory.getType(beanName);
            return type == null ? null : ClassUtils.getUserClass(type); // 解 CGLIB 代理回真实类
        } catch (RuntimeException e) {
            return null; // FactoryBean 产物 / 代理 / 条件 bean 等取不到类型：跳过（best-effort）
        }
    }

    private static boolean carriesForgeMarker(Method method) {
        return AnnotationUtils.findAnnotation(method, ForgeRoute.class) != null
                || AnnotatedElementUtils.hasAnnotation(method, Forge.class);
    }

    private static String signature(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + Arrays.toString(method.getParameterTypes());
    }

    private static String inertMessage(Class<?> owner, Method method, boolean classHasHandlers) {
        String why = classHasHandlers
                ? "该方法没有请求映射，永远不会成为 handler（@Forge 只能叠加在带映射注解的方法上）"
                : "其声明类不是已注册的 @Controller/@RestController，映射不会生效";
        return "Forge 注解写在不会被消费的位置：" + owner.getSimpleName() + "#" + method.getName()
                + " —— " + why + "。该标记当前完全失效，不出现在任何 forge 端点、--forge:list、d.ts 或严格扫描里。"
                + (classHasHandlers ? "给该方法补 @GetMapping 等映射注解，或移除 @Forge。" : "把类改成 @RestController，或移除该注解。")
                + "（仅提示，不判错、不阻断启动）";
    }

    /** 供测试与诊断读取的启用状态。 */
    public boolean enabled() {
        return enabled;
    }
}
