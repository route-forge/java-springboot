package io.github.routeforge.spring.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 组合注解的映射等价性测试：{@code @ForgeRoute} 写出来的映射必须与直接写 {@code @RequestMapping}
 * <b>逐条件相同</b>，否则「一条注解写好」就是骗人的——少透传某个属性时宿主会遇到
 * 「同样的条件写上去却不生效」这种最难查的问题。
 *
 * <p>做法是同一套条件分别用两种写法注册在两个独立上下文里（同路径不能共存于一个上下文，会判
 * ambiguous mapping），再比对两边的 {@link RequestMappingInfo}：它的 {@code equals} 覆盖全部条件，
 * 名字不在其中，所以 {@code name} 单独断言。
 */
class ForgeRoutePassthroughTest {

    /** 原生写法。 */
    @Controller
    static class NativeController {

        @RequestMapping(name = "native.simple", path = "/simple", method = RequestMethod.GET)
        String simple() {
            return "";
        }

        @RequestMapping(name = "native.bare", path = "/bare")
        String bare() {
            return "";
        }

        @RequestMapping(name = "native.value", value = "/value")
        String viaValue() {
            return "";
        }

        @RequestMapping(name = "native.multi", path = {"/multi/{id:\\d+}", "/multi/all"},
                method = {RequestMethod.GET, RequestMethod.PUT},
                params = {"kind=x", "source"}, headers = {"X-Tenant", "X-Version=2"})
        String multi() {
            return "";
        }

        @RequestMapping(name = "native.media", path = "/media", method = RequestMethod.POST,
                consumes = "application/json", produces = "application/xml")
        String media() {
            return "";
        }

    }

    /** 组合注解写法：条件属性逐个透传，附加 forge 自有属性。 */
    @Controller
    static class ComposedController {

        @ForgeRoute(name = "native.simple", path = "/simple", method = RequestMethod.GET, tier = "public")
        String simple() {
            return "";
        }

        @ForgeRoute(name = "native.bare", path = "/bare")
        String bare() {
            return "";
        }

        @ForgeRoute(name = "native.value", value = "/value")
        String viaValue() {
            return "";
        }

        @ForgeRoute(name = "native.multi", path = {"/multi/{id:\\d+}", "/multi/all"},
                method = {RequestMethod.GET, RequestMethod.PUT},
                params = {"kind=x", "source"}, headers = {"X-Tenant", "X-Version=2"},
                tier = "admin", aliases = {"native.legacy"}, middleware = {"auth"})
        String multi() {
            return "";
        }

        @ForgeRoute(name = "native.media", path = "/media", method = RequestMethod.POST,
                consumes = "application/json", produces = "application/xml")
        String media() {
            return "";
        }

    }

    @Test
    @DisplayName("五组条件组合下，组合注解与原生注解注册出的映射完全相同")
    void composedMappingEqualsNativeMapping() {
        Map<String, RequestMappingInfo> nativeInfos = mappingsOf(NativeController.class);
        Map<String, RequestMappingInfo> composedInfos = mappingsOf(ComposedController.class);

        assertThat(nativeInfos).hasSize(5);
        assertThat(composedInfos).hasSize(5);
        assertThat(composedInfos.keySet()).isEqualTo(nativeInfos.keySet());
        for (String handler : nativeInfos.keySet()) {
            assertThat(composedInfos.get(handler))
                    .as("handler %s 的映射条件必须与原生写法逐项相同", handler)
                    .isEqualTo(nativeInfos.get(handler));
            assertThat(composedInfos.get(handler).getName())
                    .as("handler %s 的 name 必须经 @AliasFor 落到 @RequestMapping", handler)
                    .isEqualTo(nativeInfos.get(handler).getName())
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("forge 自有属性不污染映射：带满 forge 属性的 multi 仍与原生一致")
    void forgeOwnAttributesDoNotAffectMapping() {
        Map<String, RequestMappingInfo> nativeInfos = mappingsOf(NativeController.class);
        Map<String, RequestMappingInfo> composedInfos = mappingsOf(ComposedController.class);

        // multi 在组合注解上额外带了 tier / aliases / middleware，映射条件必须一项都没变
        assertThat(composedInfos.get("multi").getPatternValues())
                .containsExactlyInAnyOrderElementsOf(nativeInfos.get("multi").getPatternValues());
        assertThat(composedInfos.get("multi").getMethodsCondition().getMethods())
                .isEqualTo(nativeInfos.get("multi").getMethodsCondition().getMethods());
        assertThat(composedInfos.get("multi").getParamsCondition().getExpressions())
                .isEqualTo(nativeInfos.get("multi").getParamsCondition().getExpressions());
        assertThat(composedInfos.get("multi").getHeadersCondition().getExpressions())
                .isEqualTo(nativeInfos.get("multi").getHeadersCondition().getExpressions());
    }

    @Test
    @DisplayName("version 属性经 @AliasFor 绑定到 @RequestMapping（注册需 ApiVersionStrategy，另测）")
    void versionAttributeIsAliased() throws Exception {
        // Framework 7 的 version 条件要求映射上配置 ApiVersionStrategy，未配置时注册直接抛
        // "API version specified, but no ApiVersionStrategy configured"；
        // 因此这里在注解层证明别名生效，注册层的等价性由其余属性覆盖。
        var annotations = org.springframework.core.annotation.MergedAnnotations.from(
                        versionedMethod(), org.springframework.core.annotation.MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                .get(org.springframework.web.bind.annotation.RequestMapping.class);

        assertThat(annotations.isPresent()).isTrue();
        assertThat(annotations.getString("version")).isEqualTo("2.1");
        assertThat(annotations.getString("name")).isEqualTo("native.versions");
    }

    private static Method versionedMethod() throws Exception {
        return VersionedComposed.class.getDeclaredMethod("versioned");
    }

    @Controller
    static class VersionedComposed {

        @ForgeRoute(name = "native.versions", path = "/ver", version = "2.1")
        public String versioned() {
            return "";
        }
    }

    @Test
    @DisplayName("组合注解的 path 与 method 读得到（归一化阶段的输入）")
    void composedAttributesAreReadable() throws Exception {
        Method method = ComposedController.class.getDeclaredMethod("multi");
        ForgeRoute declared = method.getAnnotation(ForgeRoute.class);

        assertThat(declared).isNotNull();
        assertThat(declared.path()).containsExactly("/multi/{id:\\d+}", "/multi/all");
        assertThat(declared.method()).containsExactly(RequestMethod.GET, RequestMethod.PUT);
        assertThat(declared.tier()).isEqualTo("admin");
        assertThat(declared.aliases()).containsExactly("native.legacy");
        assertThat(declared.middleware()).containsExactly("auth");
    }

    private static <T> Map<String, RequestMappingInfo> mappingsOf(Class<T> controllerType) {
        T instance = instantiate(controllerType);
        var context = new GenericWebApplicationContext();
        context.registerBean(controllerType.getSimpleName(), controllerType, () -> instance);
        context.refresh();

        var mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();

        Map<String, RequestMappingInfo> byHandler = new LinkedHashMap<>();
        mapping.getHandlerMethods().forEach((info, handlerMethod) ->
                byHandler.put(handlerMethod.getMethod().getName(), info));
        return byHandler;
    }

    private static <T> T instantiate(Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("测试用控制器必须有不带参构造器：" + type.getName(), e);
        }
    }
}
