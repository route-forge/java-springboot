package io.github.routeforge.spring.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.exception.ConflictingRouteDeclarationException;
import io.github.routeforge.spring.annotation.Forge;
import io.github.routeforge.spring.annotation.ForgeRoute;
import io.github.routeforge.spring.annotation.ForgeTier;
import io.github.routeforge.spring.naming.RouteNamingStrategy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 扫描层的翻译契约：Spring 的映射表 → 核心层的 {@link RouteInfo}。
 *
 * <p>这一层是「三条声明通道 + Spring 原生属性 + 运行期能力」汇合的地方，出错方式通常是<b>静默丢信息</b>
 * （名字没读到、可选段没拼上、方法集下发成空数组），所以断言逐条查产物字段，不满足于「没抛异常」。
 */
class HandlerMethodRouteSourceTest {

    @Controller
    static class DemoController {

        @ForgeRoute(name = "admin.users.show", path = "/admin/users/{user:\\d+}", method = RequestMethod.GET,
                tier = "admin", middleware = "auth", aliases = "admin.members.show")
        public String composed() {
            return "";
        }

        @GetMapping(name = "native.only", path = "/native")
        public String nativeNamed() {
            return "";
        }

        @GetMapping(path = "/forge/{page}")
        @Forge(name = "client.page", optional = "page", defaults = "page=1", tier = "client")
        public String optionalWithDefault() {
            return "";
        }

        @RequestMapping(path = {"/multi/a", "/multi/b"}, method = RequestMethod.GET, name = "multi.route")
        public String multiPath() {
            return "";
        }

        @RequestMapping(path = "/any", name = "any.route")
        public String anyMethod() {
            return "";
        }

        @PostMapping(path = "/write", name = "write.op")
        public String write() {
            return "";
        }
    }

    /** 默认值名字写错的独立夹具：扫描会整体抛异常，不能与其它用例共用控制器。 */
    @Controller
    static class BadDefaultController {

        @GetMapping(path = "/bad-default/{page}")
        @Forge(name = "bad.default", defaults = "sort=1")
        public String badDefaultName() {
            return "";
        }
    }

    @ForgeTier("manage")
    @Controller
    static class TieredByClassController {

        @GetMapping(path = "/t/class", name = "t.class")
        public String byClass() {
            return "";
        }

        @ForgeTier("admin")
        @GetMapping(path = "/t/method", name = "t.method")
        public String byMethod() {
            return "";
        }
    }

    @Controller
    static class ConflictingController {

        @ForgeRoute(name = "dup.a", path = "/dup", method = RequestMethod.GET)
        @Forge(name = "dup.b")
        public String conflict() {
            return "";
        }
    }

    @Controller
    static class UnnamedController {

        @GetMapping("/unnamed/plain")
        public String plain() {
            return "";
        }
    }

    @Test
    @DisplayName("组合注解：剥约束、GET 附 HEAD、tier/别名/中间件全部落到 RouteInfo")
    void composedRouteIsTranslated() {
        RouteInfo info = routeFor(DemoController.class, "/admin/users/{user}");

        assertThat(info.name()).isEqualTo("admin.users.show");
        assertThat(info.uri()).isEqualTo("/admin/users/{user}");
        assertThat(info.methods()).containsExactly("GET", "HEAD");
        assertThat(info.parameters()).containsExactly("user");
        assertThat(info.parameterDefaults()).isEmpty();
        assertThat(info.tier()).isEqualTo("admin");
        assertThat(info.middleware()).containsExactly("auth");
        assertThat(info.forgeAliases()).containsExactly("admin.members.show");
        assertThat(info.source()).isNotNull();
    }

    @Test
    @DisplayName("Spring 原生 name 也是一条命名通道，不强制要求 forge 注解")
    void nativeNameIsAccepted() {
        assertThat(routeFor(DemoController.class, "/native").name()).isEqualTo("native.only");
    }

    @Test
    @DisplayName("副注解驱动可选段与默认值：产物 URI 变 {page?}，默认值进 parameter_defaults")
    void secondaryAnnotationDrivesOptionalAndDefaults() {
        RouteInfo info = routeFor(DemoController.class, "/forge/{page?}");

        assertThat(info.name()).isEqualTo("client.page");
        assertThat(info.parameters()).containsExactly("page");
        assertThat(info.parameterDefaults()).containsEntry("page", "1");
        assertThat(info.tier()).isEqualTo("client");
    }

    @Test
    @DisplayName("默认值名字不在模板参数里 → 拒绝，不静默丢一个默认值")
    void unknownDefaultNameIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> routesFor(BadDefaultController.class))
                .withMessageContaining("[sort]")
                .withMessageContaining("[page]");
    }

    @Test
    @DisplayName("一个映射带多个路径时展开成多条记录（Laravel 天然是一条 URI 一条路由）")
    void multiPathExpandsToSeparateEntries() {
        assertThat(routesFor(MultiAndPathlessController.class))
                .filteredOn(route -> "multi.route".equals(route.name()))
                .extracting(RouteInfo::uri)
                .containsExactly("/multi/a", "/multi/b");
    }

    @Test
    @DisplayName("无 path 条件的映射进元信息且只出一条：空串与 / 归一为同一个根地址")
    void pathlessMappingBecomesSingleRootUri() {
        List<RouteInfo> routes = routesFor(MultiAndPathlessController.class);

        // 两个 handler 展开成三条：pathless 一条（根地址）+ 多路径两条
        assertThat(routes).hasSize(3);
        assertThat(routes).filteredOn(route -> route.name() == null)
                .singleElement()
                .satisfies(route -> assertThat(route.uri()).isEqualTo("/"));
    }

    @Test
    @DisplayName("未声明 method 的映射下发完整标准方法集而非空数组；声明了的按声明下发")
    void methodsFollowRuntimeCapability() {
        assertThat(new HandlerMethodRouteSource(mappingOf(DemoController.class)).routes())
                .filteredOn(route -> "any.route".equals(route.name()))
                .singleElement()
                .extracting(RouteInfo::methods)
                .asList()
                .contains("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

        assertThat(routeFor(DemoController.class, "/write").methods()).containsExactly("POST");
    }

    @Test
    @DisplayName("类级 @ForgeTier 作 group 继承，方法级就近覆盖")
    void tierInheritanceIsApplied() {
        assertThat(routeFor(TieredByClassController.class, "/t/class").tier()).isEqualTo("manage");
        assertThat(routeFor(TieredByClassController.class, "/t/method").tier()).isEqualTo("admin");
    }

    @Test
    @DisplayName("两条通道对同一事实给了不同值 → RF_BE_010，不静默择一")
    void conflictingChannelsFailFast() {
        assertThatExceptionOfType(ConflictingRouteDeclarationException.class)
                .isThrownBy(() -> routesFor(ConflictingController.class))
                .withMessageContaining("name")
                .withMessageContaining("dup.a")
                .withMessageContaining("dup.b");
    }

    @Test
    @DisplayName("命名策略是最后一级：显式名字优先，无策略时未命名路由保持无名")
    void namingStrategyIsLastResort() {
        RouteNamingStrategy strategy = candidate -> "derived." + candidate.handlerMethod();

        // DemoController 全部带显式名字 → 策略一次都不该生效
        assertThat(new HandlerMethodRouteSource(mappingOf(DemoController.class), strategy).routes())
                .allSatisfy(route -> assertThat(route.name()).isNotNull());

        List<RouteInfo> derived = new HandlerMethodRouteSource(mappingOf(UnnamedController.class), strategy).routes();
        assertThat(derived).singleElement()
                .satisfies(route -> {
                    assertThat(route.name()).isEqualTo("derived.plain");
                    assertThat(route.uri()).isEqualTo("/unnamed/plain");
                });

        assertThat(new HandlerMethodRouteSource(mappingOf(UnnamedController.class)).routes())
                .singleElement()
                .satisfies(route -> assertThat(route.name()).isNull());
    }

    /** 只放多路径与无路径条件两个方法：单独验展开与跳过，不受其它夹具的抛异常影响。 */
    @Controller
    static class MultiAndPathlessController {

        @RequestMapping(path = {"/multi/a", "/multi/b"}, method = RequestMethod.GET, name = "multi.route")
        public String multiPath() {
            return "";
        }

        @RequestMapping(headers = "X-Only")
        public String pathless() {
            return "";
        }
    }

    private static RouteInfo routeFor(Class<?> controllerType, String uri) {
        List<RouteInfo> routes = routesFor(controllerType);
        return routes.stream()
                .filter(route -> route.uri().equals(uri))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没扫到 uri=" + uri + "，实扫："
                        + routes.stream().map(RouteInfo::uri).toList()));
    }

    private static List<RouteInfo> routesFor(Class<?> controllerType) {
        return new HandlerMethodRouteSource(mappingOf(controllerType)).routes();
    }

    @SuppressWarnings("unchecked")
    private static RequestMappingHandlerMapping mappingOf(Class<?> controllerType) {
        Object instance = instantiate(controllerType);
        var context = new GenericWebApplicationContext();
        context.registerBean(controllerType.getSimpleName(), (Class<Object>) controllerType, () -> instance);
        context.refresh();

        var mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
        return mapping;
    }

    private static Object instantiate(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("测试用控制器必须有不带参构造器：" + type.getName(), e);
        }
    }
}
