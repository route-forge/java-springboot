package io.github.routeforge.spring.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.routeforge.spring.endpoint.ForgeTestApplication;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 管理器的门禁与只读端点契约（SPEC §5.3 / §4.7）：三道关卡逐一钉住，外加铁律 3——
 * 管理器的 {@code /_forge/manager/**} 路由必须能被访问，同时被排除在 forge 自己的元信息扫描之外。
 *
 * <p>MockMvc 默认 remoteAddr = {@code 127.0.0.1}，命中默认白名单；测 403 时用 post-processor 改来源 IP。
 * 用 {@link TempStore} 把 {@link ForgeLevelsStore} 指向临时目录，避免写进工作目录。
 */
@SpringBootTest(classes = {ForgeTestApplication.class, ForgeManagerAccessContractTest.TempStore.class})
@AutoConfigureMockMvc
class ForgeManagerAccessContractTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class TempStore {
        @Bean
        ForgeLevelsStore tempStore() throws IOException {
            Path dir = Files.createTempDirectory("rf-mgr-access");
            dir.toFile().deleteOnExit();
            return new ForgeLevelsStore(dir.resolve("forge-levels.yml"));
        }
    }

    private int statusOfGet(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
    }

    @Nested
    @DisplayName("默认（未开管理器）")
    class Disabled {

        @Test
        @DisplayName("页面与 api/routes 均 404：bean 未注册")
        void endpointsAbsent() throws Exception {
            assertThat(statusOfGet("/_forge/manager")).isEqualTo(404);
            assertThat(statusOfGet("/_forge/manager/api/routes")).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("manager.enabled=true 但 debug 未开")
    @TestPropertySource(properties = "forge.manager.enabled=true")
    class EnabledWithoutDebug {

        @Test
        @DisplayName("仅 enabled 不够，缺 debug 仍不注册（两道 AND）")
        void stillAbsent() throws Exception {
            assertThat(statusOfGet("/_forge/manager")).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("debug=true 且 manager.enabled=true")
    @TestPropertySource(properties = {"debug=true", "forge.manager.enabled=true"})
    class Live {

        @Autowired
        private ForgeRouteRegistry registry;

        @Test
        @DisplayName("页面 200、自包含 HTML")
        void pageServed() throws Exception {
            var res = mockMvc.perform(get("/_forge/manager")).andReturn();
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(res.getResponse().getContentAsString()).contains("Route Forge 管理器");
        }

        @Test
        @DisplayName("api/routes 200，含命名路由（数据源＝allRoutesWithTiers）")
        void routesServed() throws Exception {
            var res = mockMvc.perform(get("/_forge/manager/api/routes")).andReturn();
            assertThat(res.getResponse().getStatus()).isEqualTo(200);
            assertThat(res.getResponse().getContentAsString()).contains("admin.users.index");
        }

        @Test
        @DisplayName("来源 IP 不在白名单 → 页面与 api/routes 均 403")
        void foreignIpForbidden() throws Exception {
            int page = mockMvc.perform(get("/_forge/manager")
                    .with(r -> {
                        r.setRemoteAddr("203.0.113.7");
                        return r;
                    })).andReturn().getResponse().getStatus();
            int routes = mockMvc.perform(get("/_forge/manager/api/routes")
                    .with(r -> {
                        r.setRemoteAddr("203.0.113.7");
                        return r;
                    })).andReturn().getResponse().getStatus();

            assertThat(page).isEqualTo(403);
            assertThat(routes).isEqualTo(403);
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("铁律 3：管理器路由能被访问，却不出现在 forge 自己的元信息视图里")
        void managerRoutesExcludedFromForgeScan() {
            // 上面两个 200 证明 /_forge/manager/** 确已注册；这里证明它们被 URI 维排除，
            // 否则 strict 模式下包会把自己报成宿主的 missing_name（见 ForgeUriExclusionTest 同族）
            Map<String, Object> view = registry.allRoutesWithTiers();
            List<Map<String, Object>> routes = (List<Map<String, Object>>) view.get("routes");

            assertThat(routes).isNotEmpty();
            assertThat(routes)
                    .as("/_forge/manager 与 /_forge/routes 都属包自身，须经 URI 维排除")
                    .noneMatch(row -> {
                        String uri = String.valueOf(row.get("uri"));
                        return uri.startsWith("/_forge/manager") || uri.startsWith("/_forge/routes");
                    });
        }
    }
}
