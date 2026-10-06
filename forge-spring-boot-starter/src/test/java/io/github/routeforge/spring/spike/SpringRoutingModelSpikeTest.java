package io.github.routeforge.spring.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Spring 路由模型契约测试：把 P2 设计所依赖的 Framework 7 事实钉成回归。
 *
 * <p>这三件事决定适配层的写法，而它们在各版本间确实会变（Framework 7 就把
 * {@code PathPattern.getVariableNames()} 收成私有、把 {@code RequestMappingInfo} 挪了包）：
 * <ol>
 *   <li>Spring 没有「命名路由」，{@code @RequestMapping(name = ...)} 里的 name 能不能在扫描期读回来；</li>
 *   <li>meta-annotated {@code @RequestMapping} 的<b>组合注解</b>能否真的注册出映射（{@code @ForgeRoute} 的前提）；</li>
 *   <li>URI 模板里的正则约束与可选段以什么形态出现在产物上（前端只认 {@code {name}} 与 {@code {name?}}）。</li>
 * </ol>
 *
 * <p>本类刻意不依赖 forge 自身的任何代码：它测的是地基，不是我们的实现。
 */
class SpringRoutingModelSpikeTest {

    /** 组合注解：模拟 @ForgeRoute 的形态（meta @RequestMapping + @AliasFor 透传 + 自有属性）。 */
    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @RequestMapping
    @interface ForgeRouteLike {

        @AliasFor(annotation = RequestMapping.class, attribute = "path")
        String[] path() default {};

        @AliasFor(annotation = RequestMapping.class, attribute = "method")
        RequestMethod[] method() default {};

        @AliasFor(annotation = RequestMapping.class, attribute = "name")
        String name() default "";

        /** forge 自有属性：必须不影响 Spring 的映射注册。 */
        String tier() default "";

        /**
         * 可选路径参数名。之所以必须是<b>独立属性</b>而不是写在路径里：Spring 的
         * {@code PathPatternParser} 直接拒绝 {@code {period?}}（见 {@link #optionalMarkerIsRejectedBySpringParser}），
         * 而 Laravel 侧契约要求产物里出现 {@code {period?}} —— 只能由适配层自己承载语义再拼进 URI。
         */
        String[] optional() default {};
    }

    @Controller
    static class AdminController {

        @GetMapping(path = "/admin/users/{user:\\d+}", name = "admin.users.show")
        String show(@PathVariable("user") String user) {
            return user;
        }

        @PostMapping(path = "/admin/users", name = "admin.users.store")
        String store(@RequestBody String body) {
            return body;
        }

        @DeleteMapping(path = "/admin/users/{user}", name = "admin.users.destroy")
        String destroy(@PathVariable String user) {
            return user;
        }

        /** 组合注解入口：路径与方法都经 @AliasFor 透传给 @RequestMapping。 */
        @ForgeRouteLike(path = "/admin/reports/{period}", method = RequestMethod.GET,
                name = "admin.reports.index", tier = "admin", optional = "period")
        String reports(@PathVariable("period") String period) {
            return period;
        }

        @GetMapping("/admin/bare")
        String bare() {
            return "";
        }
    }

    private static RequestMappingHandlerMapping mapping;
    private static Map<RequestMappingInfo, org.springframework.web.method.HandlerMethod> handlers;

    @BeforeAll
    static void setUp() {
        var context = new GenericWebApplicationContext();
        context.registerBean("adminController", AdminController.class, AdminController::new);
        context.refresh();

        mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
        handlers = mapping.getHandlerMethods();
    }

    /** 按 handler 方法名取映射：测试读得懂，比拿 toString 去猜稳。 */
    private static RequestMappingInfo infoOf(String methodName) {
        return handlers.entrySet().stream()
                .filter(entry -> entry.getValue().getMethod().getName().equals(methodName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到 handler 方法 " + methodName + "，已注册："
                        + handlers.keySet().stream().map(Object::toString).toList()));
    }

    @Test
    @DisplayName("Framework 7 的 RequestMappingInfo 带 getName()，@RequestMapping(name) 可在扫描期读回")
    void requestMappingNameIsReadable() {
        RequestMappingInfo info = infoOf("show");

        assertThat(info.getName()).isEqualTo("admin.users.show");
    }

    @Test
    @DisplayName("组合注解能注册出映射，且 name/path/method 经 @AliasFor 落到 @RequestMapping")
    void composedAnnotationRegistersMapping() {
        Set<String> patterns = handlers.keySet().stream()
                .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream())
                .collect(Collectors.toSet());

        assertThat(patterns).contains("/admin/reports/{period}");

        RequestMappingInfo info = infoOf("reports");
        assertThat(info.getName()).isEqualTo("admin.reports.index");
        assertThat(info.getMethodsCondition().getMethods()).containsExactly(RequestMethod.GET);
    }

    @Test
    @DisplayName("Spring 模板语法拒绝 {name?}：Laravel 风格的可选标记只能由适配层承载")
    void optionalMarkerIsRejectedBySpringParser() {
        var parser = new org.springframework.web.util.pattern.PathPatternParser();

        // 报错原文：Char '?' is not allowed in a captured variable name
        assertThatThrownBy(() -> parser.parse("/admin/reports/{period?}"))
                .isInstanceOf(org.springframework.web.util.pattern.PatternParseException.class)
                .hasMessageContaining("'?' is not allowed");
    }

    @Test
    @DisplayName("GET 映射的声明条件只含 GET（运行期能否响应 HEAD 由另一条用例证明）")
    void getMappingDoesNotImpliedHead() {
        assertThat(infoOf("show").getMethodsCondition().getMethods())
                .isEqualTo(Set.of(RequestMethod.GET));
    }

    @Test
    @DisplayName("URI 模板原样保留正则约束与 ? 标记：归一化必须在适配层做")
    void patternKeepsRegexAndOptionalMarker() {
        List<String> values = List.copyOf(infoOf("show").getPathPatternsCondition().getPatternValues());

        assertThat(values).containsExactly("/admin/users/{user:\\d+}");
    }

    @Test
    @DisplayName("Spring 自己接受的模板形态界定了解析器的输入空间：正则里的花括号必须转义")
    void springDefinesTheTemplateInputSpace() {
        var parser = new org.springframework.web.util.pattern.PathPatternParser();

        // 量词与转义右括号都合法；未转义的 } 在字符类里 Spring 直接拒绝
        assertThat(parser.parse("/x/{id:\\d{4}}").getPatternString()).isEqualTo("/x/{id:\\d{4}}");
        assertThat(parser.parse("/x/{code:[a-z\\}]+}").getPatternString()).isEqualTo("/x/{code:[a-z\\}]+}");
        assertThatThrownBy(() -> parser.parse("/x/{code:[a-z}]+}"))
                .isInstanceOf(org.springframework.web.util.pattern.PatternParseException.class);
        // 名字起始不能是数字、也不能含 +：这类模板不会来自 Spring，解析器仍按不可参数化兜住
        assertThatThrownBy(() -> parser.parse("/x/{9lives}"))
                .isInstanceOf(org.springframework.web.util.pattern.PatternParseException.class);
        assertThatThrownBy(() -> parser.parse("/x/{a+b}"))
                .isInstanceOf(org.springframework.web.util.pattern.PatternParseException.class);
    }

    @Test
    @DisplayName("GET 映射在运行期确实服务 HEAD（Servlet 语义），但映射条件里不写 HEAD")
    void getMappingServesHeadAtRuntimeOnly() throws Exception {
        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new HeadProbeController())
                .build();

        // 声明侧：条件里只有 GET
        assertThat(infoOfHeadProbe().getMethodsCondition().getMethods())
                .containsExactly(RequestMethod.GET);
        // 运行侧：HEAD 请求真的能打通（FrameworkServlet 走 doGet 并抑制响应体）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .head("/probe/returns-204"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/probe/returns-204"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
    }

    @Controller
    static class HeadProbeController {

        @GetMapping("/probe/returns-204")
        org.springframework.http.ResponseEntity<String> hit() {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
    }

    private static RequestMappingInfo infoOfHeadProbe() {
        var context = new GenericWebApplicationContext();
        context.registerBean("headProbe", HeadProbeController.class, HeadProbeController::new);
        context.refresh();
        var probeMapping = new RequestMappingHandlerMapping();
        probeMapping.setApplicationContext(context);
        probeMapping.afterPropertiesSet();
        return probeMapping.getHandlerMethods().keySet().iterator().next();
    }

    @Test
    @DisplayName("无路径条件的映射只匹配根路径：pattern 是 [\"\", \"/\"]，可表达为 \"/\"")
    void pathlessMappingMatchesRootOnly() throws Exception {
        var context = new GenericWebApplicationContext();
        context.registerBean("pathless", PathlessController.class, PathlessController::new);
        context.refresh();
        var pathlessMapping = new RequestMappingHandlerMapping();
        pathlessMapping.setApplicationContext(context);
        pathlessMapping.afterPropertiesSet();

        RequestMappingInfo info = pathlessMapping.getHandlerMethods().keySet().iterator().next();
        // Spring 给无 path 的映射 materialize 出两个 pattern：空串与 "/"，语义上是同一个根路径
        assertThat(info.getPatternValues()).containsExactlyInAnyOrder("", "/");

        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new PathlessController()).build();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/").header("X-Only", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        // 深路径打不通：所以它不是「匹配任意路径」，可以正常进元信息（uri = "/"）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/deep/any").header("X-Only", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Controller
    static class PathlessController {

        @RequestMapping(headers = "X-Only")
        @org.springframework.web.bind.annotation.ResponseBody
        String any() {
            return "hit";
        }
    }

    @Test
    @DisplayName("未声明 method 的映射条件为空集（不限方法），扫描侧必须自己决定下发什么")
    void emptyMethodsConditionMeansAnyMethod() throws Exception {
        var context = new GenericWebApplicationContext();
        context.registerBean("anyMethod", AnyMethodController.class, AnyMethodController::new);
        context.refresh();
        var anyMapping = new RequestMappingHandlerMapping();
        anyMapping.setApplicationContext(context);
        anyMapping.afterPropertiesSet();

        RequestMappingInfo info = anyMapping.getHandlerMethods().keySet().iterator().next();
        assertThat(info.getMethodsCondition().getMethods()).isEmpty();

        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new AnyMethodController()).build();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/probe/any-method"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/probe/any-method"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Controller
    static class AnyMethodController {

        @RequestMapping("/probe/any-method")
        String any() {
            return "";
        }
    }

    @Test
    @DisplayName("PathPattern 在 Framework 7 不再公开 getVariableNames()：参数名须自解析模板")
    void pathPatternDoesNotExposeVariableNamesPublicly() {
        var pattern = infoOf("show").getPathPatternsCondition().getPatterns().iterator().next();

        assertThat(List.of(pattern.getClass().getMethods()).stream()
                .map(java.lang.reflect.Method::getName)
                .filter(name -> name.equals("getVariableNames"))
                .findAny())
                .as("若哪天 SDK 又公开了取变量名的 API，本断言会失败，届时应改用官方出口而非自解析")
                .isEmpty();
        assertThat(pattern.getPatternString()).isEqualTo("/admin/users/{user:\\d+}");
    }

    @Test
    @DisplayName("未标注 name 的映射 getName() 给 null：命名通道判缺省以 null 为准")
    void unnamedMappingHasNoName() {
        RequestMappingInfo info = infoOf("bare");

        // 实测给 null（一度按「空串」写过断言，被副注解测试反证）：判缺省只需认 null
        assertThat(info.getName()).isNull();
    }
}
