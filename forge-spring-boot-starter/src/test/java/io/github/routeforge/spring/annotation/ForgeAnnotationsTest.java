package io.github.routeforge.spring.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.routeforge.spring.naming.RouteCandidate;
import io.github.routeforge.spring.naming.RouteNamingStrategy;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 注解层的读取契约：三条命名/事实通道各自能被读到，且副注解与命名策略都<b>不</b>产生映射。
 *
 * <p>后一条是关键防呆：{@link Forge} 只是元信息，若它被误当成 mapping 注解（或反过来，宿主以为标了
 * {@link Forge} 就有路由了），扫描结果会与 Spring 实际可访问的端点分叉。
 */
class ForgeAnnotationsTest {

    @Controller
    static class SecondaryOnlyController {

        @GetMapping("/orders/{order}")
        @Forge(name = "client.orders.show", tier = "client", middleware = {"auth", "tenant"},
                optional = "order", defaults = "order=1", aliases = {"client.order.show"})
        String show() {
            return "";
        }

        @DeleteMapping("/orders/{order}")
        @Forge(name = "client.orders.destroy")
        String destroy() {
            return "";
        }

        /** 只标 @Forge：不应产生任何映射。 */
        @Forge(name = "phantom.route")
        String phantom() {
            return "";
        }
    }

    @Test
    @DisplayName("副注解的 forge 事实逐项可读，且不影响 Spring 的映射")
    void secondaryAnnotationIsReadableWithoutAffectingMapping() throws Exception {
        Method method = SecondaryOnlyController.class.getDeclaredMethod("show");
        Forge declared = method.getAnnotation(Forge.class);

        assertThat(declared).isNotNull();
        assertThat(declared.name()).isEqualTo("client.orders.show");
        assertThat(declared.tier()).isEqualTo("client");
        assertThat(declared.middleware()).containsExactly("auth", "tenant");
        assertThat(declared.optional()).containsExactly("order");
        assertThat(declared.defaults()).containsExactly("order=1");
        assertThat(declared.aliases()).containsExactly("client.order.show");

        Map<String, RequestMappingInfo> registered = registeredMappings();
        assertThat(registered).containsKeys("show", "destroy").doesNotContainKey("phantom");
        assertThat(registered.get("show").getPatternValues()).containsExactly("/orders/{order}");
        // 实测：未标注 name 时 Framework 7 的 getName() 给 null（不是空串），判缺省两者都要认
        assertThat(registered.get("show").getName())
                .as("副注解不参与映射注册，原生 @GetMapping 也没给 name")
                .isNull();
    }

    @Test
    @DisplayName("注解默认值全为空：留空即「未表态」，不能与显式空串混淆")
    void annotationDefaultsAreEmpty() throws Exception {
        Method method = SecondaryOnlyController.class.getDeclaredMethod("destroy");
        Forge declared = method.getAnnotation(Forge.class);

        assertThat(declared.tier()).isEmpty();
        assertThat(declared.middleware()).isEmpty();
        assertThat(declared.optional()).isEmpty();
        assertThat(declared.defaults()).isEmpty();
        assertThat(declared.aliases()).isEmpty();
    }

    @Test
    @DisplayName("命名策略拿到的是原文模板，派生逻辑自己处理约束")
    void namingStrategyReceivesRawPattern() {
        RouteNamingStrategy strategy = candidate -> candidate.handlerType().getSimpleName()
                .replace("Controller", "").toLowerCase() + "." + candidate.handlerMethod();
        RouteCandidate candidate = new RouteCandidate(SecondaryOnlyController.class, "show", "/orders/{order}");

        assertThat(strategy.nameOf(candidate)).isEqualTo("secondaryonly.show");
        assertThat(new RouteCandidate(SecondaryOnlyController.class, "show", "/x/{id:\\d+}").pattern())
                .as("候选给的是未归一化的原文模板")
                .isEqualTo("/x/{id:\\d+}");
    }

    private static Map<String, RequestMappingInfo> registeredMappings() {
        var context = new GenericWebApplicationContext();
        context.registerBean("secondary", SecondaryOnlyController.class, SecondaryOnlyController::new);
        context.refresh();

        var mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();

        Map<String, RequestMappingInfo> byHandler = new LinkedHashMap<>();
        mapping.getHandlerMethods()
                .forEach((info, handlerMethod) -> byHandler.put(handlerMethod.getMethod().getName(), info));
        return byHandler;
    }
}
