package io.github.routeforge.spring.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.routeforge.core.support.WarningSink;
import io.github.routeforge.spring.annotation.Forge;
import io.github.routeforge.spring.annotation.ForgeRoute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3-2：启动后一次性扫「forge 注解写在不会被消费的位置」的误用，只在 {@code debug} 或 {@code strict} 下开、
 * 恒为 warning、绝不抛。用一个自建 web 上下文 + 录音版 {@link WarningSink} 端到端验证三种形态。
 *
 * <p>之所以只能走真实上下文：判据是「带标记的方法是否落在已注册 handler 集外」，而 handler 集来自
 * {@code RequestMappingHandlerMapping}（只有真上下文才有）。用 {@code @Import} 精确放三条被测类，
 * 避免别的 bean 干扰录音。
 */
@SpringBootTest(classes = ForgeAnnotationSanityTest.App.class)
class ForgeAnnotationSanityTest {

    @EnableAutoConfiguration
    @Configuration
    @Import({ValidController.class, ComponentWithForgeRoute.class, ControllerWithInertForge.class})
    static class App {

        @Bean
        RecordingWarningSink recordingWarningSink() {
            return new RecordingWarningSink();
        }
    }

    /** 覆盖默认 Slf4jWarningSink（{@code @ConditionalOnMissingBean(WarningSink)}），收集全部提示供断言。 */
    static final class RecordingWarningSink implements WarningSink {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void warning(String message) {
            messages.add(message);
        }
    }

    /** 合法：命名 + 路径 + 类是 @RestController → 注册成 handler，不该被报。 */
    @RestController
    static class ValidController {
        @ForgeRoute(name = "sanity.valid", path = "/sanity/valid", method = RequestMethod.GET)
        String ok() {
            return "";
        }
    }

    /** 误用一：@ForgeRoute 标在 @Component（非 @Controller）上 → 类不是 handler，映射不生效。 */
    @Component
    static class ComponentWithForgeRoute {
        @ForgeRoute(name = "sanity.inert.comp", path = "/sanity/inert", method = RequestMethod.GET)
        String nope() {
            return "";
        }
    }

    /** 误用二：@RestController 里一个只带 @Forge、没有请求映射的方法 → 该方法永不成为 handler。 */
    @RestController
    static class ControllerWithInertForge {
        @GetMapping("/sanity/mapped")
        String mapped() {
            return "";
        }

        @Forge(name = "sanity.inert.helper")
        String helper() {
            return "";
        }
    }

    @Autowired
    private RecordingWarningSink sink;

    @Test
    @DisplayName("默认上下文（debug=false、strict=false）→ 不扫、不出任何『不会被消费的位置』提示")
    void silentWhenDisabled() {
        assertThat(sink.messages).noneMatch(m -> m.contains("不会被消费的位置"));
    }

    @Nested
    @TestPropertySource(properties = "debug=true")
    @DisplayName("debug=true 上下文")
    class Enabled {

        @Autowired
        private RecordingWarningSink enabledSink;

        @Test
        @DisplayName("@ForgeRoute 标在非 controller 的 bean：报『不是已注册的 @Controller』")
        void flagsForgeRouteOnNonController() {
            assertThat(enabledSink.messages)
                    .anyMatch(m -> m.contains("不会被消费的位置")
                            && m.contains("ComponentWithForgeRoute#nope")
                            && m.contains("@Controller"));
        }

        @Test
        @DisplayName("@Forge 标在无映射的方法：报『没有请求映射，永远不会成为 handler』")
        void flagsForgeOnUnmappedMethod() {
            assertThat(enabledSink.messages)
                    .anyMatch(m -> m.contains("不会被消费的位置")
                            && m.contains("ControllerWithInertForge#helper")
                            && m.contains("没有请求映射"));
        }

        @Test
        @DisplayName("合法映射的 @ForgeRoute 不误报；且这类是 warning、无 error 级、不阻断启动")
        void validHandlerNotReported() {
            assertThat(enabledSink.messages).noneMatch(m -> m.contains("ValidController#ok"));
            // 走到这里说明上下文正常启动（扫描器未抛异常拖垮 refresh）
            assertThat(true).isTrue();
        }
    }
}
