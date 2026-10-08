package io.github.routeforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 示例后端的端到端冒烟测试：证明 starter 在<b>真实 Boot 应用</b>（内嵌 Tomcat + Jackson 转换器）里接入可用——
 * 两个元信息端点出正确层级与路由，且 {@code manage} 层级完全靠配置 {@code match.prefix} 归级（AGENTS 铁律 1 的活证）。
 *
 * <p>逐字段字节级契约由 starter 的 {@code ForgeEndpointsContractTest} 锁；这里按子串抽查，验证「宿主能跑通」即可。
 */
@SpringBootTest(classes = ExampleApplication.class)
@AutoConfigureMockMvc
class ExampleApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    private MvcResult get200(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("HTTP 状态：%s（响应体：%s）", path, result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result;
    }

    @Test
    @DisplayName("摘要端点：三个层级齐全 + config 下发")
    void summaryHasAllLevels() throws Exception {
        String body = get200("/_forge/routes").getResponse().getContentAsString();

        assertThat(body).contains("\"admin\"").contains("\"client\"").contains("\"manage\"")
                .contains("\"endpoint_prefix\":\"/_forge/routes\"");
    }

    @Test
    @DisplayName("admin 层级：显式 tier 命名 + URI 模板 {id} 参数被解析")
    void adminLevelFromExplicitTier() throws Exception {
        String body = get200("/_forge/routes/admin").getResponse().getContentAsString();

        assertThat(body).contains("admin.users.index").contains("/admin/users")
                .contains("admin.users.show").contains("{id}")
                .as("URI 模板参数 id 应被解析进 parameters").contains("\"parameters\":[\"id\"]");
    }

    @Test
    @DisplayName("manage 层级：无 tier，纯靠配置 match.prefix 归级（端到端跑通配置通道）")
    void manageLevelFromConfigMatch() throws Exception {
        String body = get200("/_forge/routes/manage").getResponse().getContentAsString();

        assertThat(body).contains("manage.reports.index").contains("/manage/reports");
    }

    @Test
    @DisplayName("client 层级：显式 tier + eager 预加载")
    void clientLevel() throws Exception {
        assertThat(get200("/_forge/routes/client").getResponse().getContentAsString())
                .contains("client.orders.list");
    }

    @Test
    @DisplayName("真实业务路由本身可访问（端点之外，宿主 controller 正常服务）")
    void businessRoutesServe() throws Exception {
        assertThat(get200("/admin/users").getResponse().getContentAsString()).contains("alice");
    }
}
