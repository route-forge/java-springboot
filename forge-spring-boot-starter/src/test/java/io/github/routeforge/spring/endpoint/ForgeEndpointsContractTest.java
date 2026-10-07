package io.github.routeforge.spring.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 两个端点的契约测试：断言<b>响应体原文</b>，不做 JSON 路径抽查。
 *
 * <p>理由与前几层一致：这份产物是前端与脚本直接消费的字节，键序、{@code null} 是否出现、
 * 空层级是 {@code {}} 还是 {@code []}，抽查都会放过；只有整文比对锁得住。
 *
 * <p>层级配置放 {@code src/test/resources/application.yaml}（三组上下文共用），注解只覆盖差异项。
 * 前缀按 Spring 形态写（带前导斜杠），与下发的 {@code uri} 同一形态。
 *
 * <p>三组上下文分别覆盖：宽松模式（正常出数）、严格模式无 {@code debug}（500 只给 code/message/level）、
 * 严格模式带 {@code debug}（额外给 violations）。第三组同时钉住 AGENTS 铁律 3：违例数恰为 1 才说明
 * 包自身的 {@code /_forge/routes/**} 没被当成宿主的配置错误——否则一开严格模式端点必 500。
 */
@SpringBootTest(classes = ForgeTestApplication.class)
@AutoConfigureMockMvc
class ForgeEndpointsContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private io.github.routeforge.spring.config.ForgeProperties properties;

    private MvcResult fetch(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn();
    }

    private String body(String path, int expectedStatus) throws Exception {
        MvcResult result = fetch(path);
        assertThat(result.getResponse().getStatus())
                .as("HTTP 状态：%s", path)
                .isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString();
    }

    @Nested
    @DisplayName("宽松模式")
    @TestPropertySource(properties = {"forge.url-prefix=https://api.example.com/v1", "forge.cache-ttl=0"})
    class Lenient {

        @Test
        @DisplayName("摘要端点整文：层级顺序为配置顺序 + unassigned，config 四项齐全")
        void summaryIsByteExact() throws Exception {
            assertThat(body("/_forge/routes", 200)).isEqualTo("""
                    {"schemeVersion":1,"levels":{\
                    "admin":{"description":"系统管理接口","load":"lazy","route_count":1,\
                    "route":{"uri":"/_forge/routes/admin","methods":["GET","HEAD"]}},\
                    "client":{"description":"客户端接口","load":"eager","route_count":1,\
                    "route":{"uri":"/_forge/routes/client","methods":["GET","HEAD"]}},\
                    "manage":{"description":"运营接口","load":"lazy","route_count":1,\
                    "route":{"uri":"/_forge/routes/manage","methods":["GET","HEAD"]}},\
                    "unassigned":{"description":"未命中任何层级的路由","load":"lazy","route_count":0,\
                    "route":{"uri":"/_forge/routes/unassigned","methods":["GET","HEAD"]}}},\
                    "config":{"strict_mode":false,"endpoint_prefix":"/_forge/routes",\
                    "url_prefix":"https://api.example.com/v1","cache_ttl":0}}""");
        }

        @Test
        @DisplayName("层级端点整文：uri 保留前导斜杠、GET 附 HEAD、空 parameter_defaults 是 {}")
        void levelPayloadIsByteExact() throws Exception {
            assertThat(body("/_forge/routes/admin", 200)).isEqualTo("""
                    {"level":"admin","routes":{"admin.users.index":{"uri":"/admin/users",\
                    "methods":["GET","HEAD"],"parameters":[],"parameter_defaults":{}}}}""");
        }

        @Test
        @DisplayName("配置 match 通道生效：只靠 prefix 归级的命名路由进 manage 层级")
        void matchRuleAssignsLevel() throws Exception {
            // admin/client 都带显式 tier，只有这一条能证明 forge.levels.*.match 真的接通了
            assertThat(body("/_forge/routes/manage", 200)).isEqualTo("""
                    {"level":"manage","routes":{"manage.reports.index":{"uri":"/manage/reports/index",\
                    "methods":["GET","HEAD"],"parameters":[],"parameter_defaults":{}}}}""");
        }

        @Test
        @DisplayName("未命名路由不进任何元信息（严格模式下它只以 violations 现身）")
        void unnamedRoutesAreAbsent() throws Exception {
            assertThat(body("/_forge/routes/unassigned", 200)).isEqualTo("""
                    {"level":"unassigned","routes":{}}""");
        }

        @Test
        @DisplayName("未知层级名 → RF_BE_002 / 404，错误体带 level 上下文")
        void unknownLevelIsNotFound() throws Exception {
            assertThat(body("/_forge/routes/ghost", 404)).isEqualTo("""
                    {"error":{"code":"RF_BE_002","message":"Unknown level: ghost","level":"ghost"}}""");
        }

        @Test
        @DisplayName("缓存生效：第二次取数不再重扫（TTL=0 永久），产物完全一致")
        void secondCallIsCached() throws Exception {
            String first = body("/_forge/routes/client", 200);
            String second = body("/_forge/routes/client", 200);

            assertThat(second).isEqualTo(first);
        }
    }

    @Nested
    @DisplayName("严格模式 · debug=false")
    @TestPropertySource(properties = "forge.strict-mode=true")
    class StrictWithoutDebug {

        @Test
        @DisplayName("存在 missing_name 违规 → 500，错误体不给 violations")
        void violationsAreNotExposed() throws Exception {
            // 先确认属性真的进了这个上下文，否则失败信息会把配置问题误报成检出问题
            assertThat(properties.strictMode()).as("forge.strict-mode 未生效").isTrue();
            String body = body("/_forge/routes/admin", 500);

            assertThat(body).doesNotContain("violations")
                    .contains("\"code\":\"RF_BE_009\"")
                    .contains("\"level\":\"admin\"");
        }

        @Test
        @DisplayName("摘要端点同样被拦（取数前预扫描，不区分是哪个端点）")
        void summaryAlsoBlocked() throws Exception {
            String body = body("/_forge/routes", 500);

            // 摘要侧没有 level 上下文：错误体只有 code 与 message 两项
            assertThat(body).doesNotContain("\"level\"").doesNotContain("violations");
        }
    }

    @Nested
    @DisplayName("严格模式 · debug=true")
    @TestPropertySource(properties = {"forge.strict-mode=true", "debug=true"})
    class StrictWithDebug {

        @Test
        @DisplayName("错误体带结构化 violations，且违例恰为 1：包自身端点已被 URI 排除")
        void violationsExposedUnderDebug() throws Exception {
            String body = body("/_forge/routes/admin", 500);

            assertThat(body).contains("\"violations\":{");
            assertThat(occurrences(body, "\"uri\":\"/")).as("三组 violations 的条目总数").isEqualTo(1);
            assertThat(occurrences(body, "\"uri\":\"/_forge")).as("包自身端点不得出现在违规清单里").isZero();
            assertThat(body).contains("\"uri\":\"/manage/reports\"");
        }

        @Test
        @DisplayName("message 与命令行红色清单同源：整句在错误体里逐字对得上")
        void messageIsTheSharedRendering() throws Exception {
            String body = body("/_forge/routes/manage", 500);

            assertThat(body)
                    .contains("Route Forge strict_mode found 1 route configuration problem(s):")
                    .contains("GET /manage/reports -> level [manage] via a config match rule");
        }

        private static int occurrences(String haystack, String needle) {
            int count = 0;
            for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
                count++;
            }
            return count;
        }
    }
}
