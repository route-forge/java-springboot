package io.github.routeforge.spring.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.routeforge.spring.endpoint.ForgeTestApplication;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 保存回环（SPEC §5.3 决策 D1「热生效」）：{@code PUT /api/config} 一次调用内
 * 「写文件成功 → 换内存层级 → 清缓存」，于是同进程立即按新层级生效、无需重启。
 *
 * <p>用独立上下文（另开 {@code @SpringBootTest}），因为本用例会<b>替换</b>整个层级集，
 * 若与只读用例共享上下文会造成顺序污染。
 */
@SpringBootTest(classes = {ForgeTestApplication.class, ForgeManagerSaveContractTest.TempStore.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"debug=true", "forge.manager.enabled=true"})
class ForgeManagerSaveContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ForgeLevelsStore store;

    @Autowired
    private ForgeRouteRegistry registry;

    @TestConfiguration
    static class TempStore {
        @Bean
        ForgeLevelsStore tempStore() throws IOException {
            Path dir = Files.createTempDirectory("rf-mgr-save");
            dir.toFile().deleteOnExit();
            return new ForgeLevelsStore(dir.resolve("forge-levels.yml"));
        }
    }

    @Test
    @DisplayName("PUT levels 后：文件落盘、内存层级即时替换为新的（旧层级不再存在）")
    void saveWritesFileAndHotAppliesLevels() throws Exception {
        // 前置：基线 application.yaml 给了 admin/client/manage/audit，且不含 qa
        assertThat(registry.levelNames()).doesNotContain("qa").contains("admin");

        String body = """
                {"levels": {
                   "qa": { "description": "QA 环境", "match": { "prefix": ["/qa"] }, "load": "eager" }
                }}""";

        var res = mockMvc.perform(put("/_forge/manager/api/config")
                        .with(r -> {
                            r.setRemoteAddr("127.0.0.1");
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(res.getResponse().getStatus())
                .as("保存响应体：%s", res.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(res.getResponse().getContentAsString()).contains("qa");

        // ① 写文件：forge-levels.yml 落盘且含新层级（顶层 forge.levels 结构，供 config.import 读回）
        assertThat(store.file()).exists();
        String yaml = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertThat(yaml).contains("qa");

        // ② 热换内存：注册表层级即时替换——PUT 是全量覆盖，旧层级消失、新层级出现
        assertThat(registry.levelNames())
                .as("保存后层级集应被 PUT 的 levels 全量替换")
                .containsExactly("qa");
    }

    @Test
    @DisplayName("请求体缺 levels → 400（不臆造 RF_BE 码，管理器错误走 HTTP 状态）")
    void missingLevelsIsBadRequest() throws Exception {
        var res = mockMvc.perform(put("/_forge/manager/api/config")
                        .with(r -> {
                            r.setRemoteAddr("127.0.0.1");
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("levels");
    }
}
