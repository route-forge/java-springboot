package io.github.routeforge.spring.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.contract.RouteSource;
import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.repository.RepositoryConfig;
import io.github.routeforge.core.cache.RouteCache;
import io.github.routeforge.core.support.WarningSink;
import io.github.routeforge.spring.cache.InMemoryCacheStore;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * 命令行三命令的直测：不启 Spring、不碰 {@code System.exit}，直接喂命令对象 {@link StringWriter}
 * 支撑的 {@code PrintWriter}，断言<b>产物原文 + 退出码 + 流归属（out/stderr）</b>。
 *
 * <p>数据源用一个返回固定 {@link RouteInfo} 列表的 {@link RouteSource}，注册表按真实装配构造
 * （{@link LevelsConfig} / {@link RepositoryConfig} / {@link RouteCache}）——命令逻辑只依赖注册表的
 * {@code analyze()} / {@code levelNames()} / {@code normalizedEndpointPrefix()} / 缓存失效四个口，
 * 因此这里覆盖到的正是它接宿主时的那条通路。d.ts 正文的逐字节对等由核心层 {@code TypeGeneratorOracleTest}
 * 钉死，本类只验「命令行把正确的层级、前缀、时间戳喂给了生成器，以及违规时的产出/拒绝」。
 */
class ForgeCliCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXED_STAMP = "2026-01-01T00:00:00.000Z";

    // 层级定义：admin/client 靠显式 tier，manage 靠 match.prefix
    private static Map<String, Object> levels() {
        Map<String, Object> levels = new LinkedHashMap<>();
        levels.put("admin", map("description", "系统管理", "load", "lazy"));
        levels.put("client", map("description", "客户端", "load", "eager"));
        levels.put("manage", map("description", "运营", "match", map("prefix", List.of("/manage"))));
        return levels;
    }

    // 五条命名路由 + 一条未命名：orphan 无 tier 无 match（宽松=unassigned，严格=违规）；
    // /manage/anon 未命名但被 match 命中（进 unnamed / warnings，严格下=missing_name 违规）
    private static List<RouteInfo> routes() {
        return List.of(
                get("admin.users.index", "/admin/users", "admin"),
                get("client.orders.list", "/client/orders", "client"),
                get("manage.reports.index", "/manage/reports/index", null),
                get("orphan.route", "/orphan/thing", null),
                get("admin.users.show", "/admin/users/{id}", "admin", List.of("id")),
                anon("/manage/anon"));
    }

    private static ForgeRouteRegistry registry(boolean strict, Map<String, Object> aliases) {
        RouteSource source = ForgeCliCommandTest::routes;
        LevelsConfig levelsConfig = new LevelsConfig(levels());
        RepositoryConfig config = new RepositoryConfig(1, strict, "/_forge/routes", null, 3600);
        RouteCache cache = new RouteCache(new InMemoryCacheStore(), false, 3600);
        return new ForgeRouteRegistry(source, levelsConfig, aliases, config, cache, null, WarningSink.NOOP);
    }

    // -------------------------------------------------------------- list

    @Nested
    @DisplayName("--forge:list")
    class ListCommand {

        @Test
        @DisplayName("表格模式：Tier counts 汇总 + 未命名 match 告警 + 各层级行，退 0")
        void tableMode() {
            Result r = runList(registry(false, Map.of()), CliOptions.none());

            assertThat(r.code()).isZero();
            assertThat(r.out())
                    .contains("Tier counts: admin: 2 | client: 1 | manage: 1 | unassigned: 1")
                    .contains("1 route(s) are unassigned")
                    .contains("/manage/anon") // warnings 通道：被 match 命中却无名
                    .contains("admin.users.index").contains("manage.reports.index").contains("orphan.route");
            assertThat(r.out()).doesNotContain("No routes found");
            assertThat(r.err()).isEmpty();
        }

        @Test
        @DisplayName("--json：结构/计数/别名外键走 stdout，退出码 0；此例无违例")
        void jsonMode() throws Exception {
            Result r = runList(registry(false, Map.of("legacy.name", "admin.users.show")),
                    new CliOptions(null, true, false, false, false, null));

            assertThat(r.code()).isZero();
            JsonNode payload = JSON.readTree(r.out());
            assertThat(textList(payload.get("levels"))).containsExactly("admin", "client", "manage", "unassigned");
            assertThat(payload.get("count").asInt()).isEqualTo(6); // 5 真实 + 1 别名行
            assertThat(payload.get("tier_counts").get("admin").asInt()).isEqualTo(3); // 2 真实 + 别名计入目标层
            assertThat(payload.get("warnings")).hasSize(1);
            JsonNode alias = findRoute(payload, "legacy.name");
            assertThat(alias.get("alias_of").asText()).isEqualTo("admin.users.show");
            // filter 缺省为 null；未过滤时不下发 filter 对象内容
            assertThat(payload.get("filter").isNull()).isTrue();
        }

        @Test
        @DisplayName("--level 过滤：只出该层级行，count 与 routes 同步收敛")
        void levelFilter() throws Exception {
            Result r = runList(registry(false, Map.of()),
                    new CliOptions("admin", false, false, false, false, null));

            JsonNode payload = JSON.readTree(runList(registry(false, Map.of()),
                    new CliOptions("admin", true, false, false, false, null)).out());
            assertThat(r.code()).isZero();
            assertThat(payload.get("count").asInt()).isEqualTo(2);
            assertThat(payload.get("filter").get("level").asText()).isEqualTo("admin");
        }

        @Test
        @DisplayName("严格模式有违例：表格照常出全表 + 末尾红色清单 + 退 1")
        void strictTableStillProduces() {
            Result r = runList(registry(true, Map.of()), CliOptions.none());

            assertThat(r.code()).isEqualTo(1);
            assertThat(r.out())
                    .contains("admin.users.index") // 全表仍在
                    .contains("Route Forge strict_mode found 2 route configuration problem(s):")
                    .contains("orphan.route") // unassigned 违规
                    .contains("/manage/anon"); // missing_name 违规
        }

        @Test
        @DisplayName("严格模式 --json：JSON 走 stdout、违例清单走 stderr、退 1")
        void strictJsonDivertsViolationsToStderr() throws Exception {
            Result r = runList(registry(true, Map.of()), new CliOptions(null, true, false, false, false, null));

            assertThat(r.code()).isEqualTo(1);
            JSON.readTree(r.out()); // stdout 必须是合法纯 JSON
            assertThat(r.out()).doesNotContain("strict_mode found");
            assertThat(r.err()).contains("Route Forge strict_mode found 2");
        }

        @Test
        @DisplayName("--unnamed 独立视图：全量未命名分组，退 0")
        void unnamedView() {
            Result r = runList(registry(false, Map.of()),
                    new CliOptions(null, false, false, false, true, null));

            assertThat(r.code()).isZero();
            assertThat(r.out()).contains("unnamed route(s) total")
                    .contains("/manage/anon");
        }

        @Test
        @DisplayName("--unnamed 与 --json 互斥：报错退 1")
        void unnamedConflict() {
            Result r = runList(registry(false, Map.of()),
                    new CliOptions(null, true, false, false, true, null));

            assertThat(r.code()).isEqualTo(1);
            assertThat(r.err()).contains("--unnamed is a standalone view");
        }

        @Test
        @DisplayName("未知层级：退 1 + Available levels 提示")
        void unknownLevel() {
            Result r = runList(registry(false, Map.of()),
                    new CliOptions("ghost", false, false, false, false, null));

            assertThat(r.code()).isEqualTo(1);
            assertThat(r.err()).contains("Unknown level: ghost").contains("admin, client, manage");
        }

        @Test
        @DisplayName("悬空别名 → analyze 抛 RF_BE_008：打 [code] 消息退 1（诊断走 stderr）")
        void danglingAliasError() {
            Result r = runList(registry(false, Map.of("broken.alias", "no.such.route")), CliOptions.none());

            assertThat(r.code()).isEqualTo(1);
            assertThat(r.err()).contains("[RF_BE_008]");
            assertThat(r.out()).isEmpty();
        }

        @Test
        @DisplayName("--level=unassigned 合法（特殊层级可过滤）")
        void unassignedLevelAllowed() {
            Result r = runList(registry(false, Map.of()),
                    new CliOptions("unassigned", false, false, false, false, null));
            assertThat(r.code()).isZero();
            assertThat(r.out()).contains("orphan.route");
        }
    }

    // -------------------------------------------------------------- types

    @Nested
    @DisplayName("--forge:types")
    class TypesCommand {

        @Test
        @DisplayName("d.ts：用注册表层级 + 规范化前缀 + 注入时间戳；unassigned 路由不入产物")
        void dtsProduct() {
            Result r = runTypes(registry(false, Map.of()), CliOptions.none());

            assertThat(r.code()).isZero();
            assertThat(r.out())
                    .startsWith("// AUTO-GENERATED by route:forge:types. Do not edit.")
                    .contains("// 生成时间: " + FIXED_STAMP)
                    .contains("// 端点: /_forge/routes")
                    .contains("export type ForgeLevel = 'admin' | 'client' | 'manage';")
                    .contains("admin.users.index").contains("manage.reports.index");
            assertThat(r.out()).doesNotContain("orphan.route"); // 未归级不生成类型
            // types 成功路径把 warnings 走 stderr（stdout 恒为纯产物）；本例有一条未命名 match 告警
            assertThat(r.err()).contains("/manage/anon");
            assertThat(r.out()).doesNotContain("/manage/anon");
        }

        @Test
        @DisplayName("--json：二级结构按层级索引，method 取首个非 HEAD")
        void jsonProduct() throws Exception {
            Result r = runTypes(registry(false, Map.of()), new CliOptions(null, true, false, false, false, null));

            assertThat(r.code()).isZero();
            JsonNode root = JSON.readTree(r.out());
            assertThat(root.has("admin")).isTrue();
            JsonNode show = root.get("admin").get("admin.users.show");
            assertThat(show.get("method").asText()).isEqualTo("GET");
            assertThat(textList(show.get("params"))).containsExactly("id");
        }

        @Test
        @DisplayName("严格模式违例：拒绝产出（stdout 全空），清单走 stderr，退 1")
        void violationsRefuseProduct() {
            Result r = runTypes(registry(true, Map.of()), CliOptions.none());

            assertThat(r.code()).isEqualTo(1);
            assertThat(r.out()).isEmpty(); // 绝不给「看起来是对的」错契约
            assertThat(r.err()).contains("Route Forge strict_mode found 2");
        }

        @Test
        @DisplayName("--out 写文件：stdout 空、确认走 stderr、文件内容 = d.ts")
        void outToFile(@TempDir Path dir) throws Exception {
            Path target = dir.resolve("nested/forge-routes.d.ts");
            Result r = runTypes(registry(false, Map.of()),
                    new CliOptions(null, false, false, false, false, target.toString()));

            assertThat(r.code()).isZero();
            assertThat(r.out()).isEmpty();
            assertThat(r.err()).contains("Written to: " + target);
            String written = Files.readString(target, StandardCharsets.UTF_8);
            assertThat(written).startsWith("// AUTO-GENERATED by route:forge:types").contains("ForgeLevel");
        }

        @Test
        @DisplayName("types 的 --level 只认已配置层级：unassigned 被拒")
        void levelValidation() {
            Result r = runTypes(registry(false, Map.of()),
                    new CliOptions("unassigned", false, false, false, false, null));
            assertThat(r.code()).isEqualTo(1);
            assertThat(r.err()).contains("Unknown level: unassigned");
        }

        @Test
        @DisplayName("--level=admin 收窄：ForgeLevel 联合只剩 admin")
        void levelNarrowsTargets() {
            Result r = runTypes(registry(false, Map.of()),
                    new CliOptions("admin", false, false, false, false, null));

            assertThat(r.code()).isZero();
            assertThat(r.out()).contains("export type ForgeLevel = 'admin';")
                    .doesNotContain("client.orders.list");
        }
    }

    // -------------------------------------------------------------- clear

    @Nested
    @DisplayName("--forge:clear")
    class ClearCommand {

        @Test
        @DisplayName("无 --level：清空全部，退 0（状态走 stdout）")
        void clearAll() {
            Result r = runClear(registry(false, Map.of()), CliOptions.none());
            assertThat(r.code()).isZero();
            assertThat(r.out()).contains("Route Forge cache cleared successfully.");
        }

        @Test
        @DisplayName("--level=admin：连带失效 summary 的措辞，退 0")
        void clearLevel() {
            Result r = runClear(registry(false, Map.of()),
                    new CliOptions("admin", false, false, false, false, null));
            assertThat(r.code()).isZero();
            assertThat(r.out()).contains("cleared for level: admin").contains("summary cache invalidated");
        }

        @Test
        @DisplayName("--level=unassigned 合法；未知层级退 1")
        void levelValidation() {
            assertThat(runClear(registry(false, Map.of()),
                    new CliOptions("unassigned", false, false, false, false, null)).code()).isZero();

            Result bad = runClear(registry(false, Map.of()),
                    new CliOptions("ghost", false, false, false, false, null));
            assertThat(bad.code()).isEqualTo(1);
            assertThat(bad.out()).contains("Unknown level: ghost").contains("unassigned");
        }
    }

    // -------------------------------------------------------------- runner

    @Nested
    @DisplayName("ForgeCliRunner 分派（不触发 System.exit）")
    class Runner {

        @Test
        @DisplayName("无 forge flag → executeIfRequested 返回 null（no-op）")
        void noOpWhenNoFlag() {
            assertThat(runner(new StringWriter(), new StringWriter())
                    .executeIfRequested(new DefaultApplicationArguments("--spring.profiles.active=dev"))).isNull();
        }

        @Test
        @DisplayName("--forge:list → 走 list 命令、产物落 out writer、退出码透传")
        void dispatchList() {
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            Integer code = runner(out, err).executeIfRequested(new DefaultApplicationArguments("--forge:list", "--json"));

            assertThat(code).isZero();
            assertThat(out.toString()).contains("\"tier_counts\"");
        }

        @Test
        @DisplayName("--forge:clear --level=ghost → 退出码 1 回传")
        void dispatchClearBadLevel() {
            StringWriter out = new StringWriter();
            Integer code = runner(out, new StringWriter())
                    .executeIfRequested(new DefaultApplicationArguments("--forge:clear", "--level=ghost"));
            assertThat(code).isEqualTo(1);
            assertThat(out.toString()).contains("Unknown level: ghost");
        }
    }

    // -------------------------------------------------------------- 工具

    private record Result(int code, String out, String err) {
    }

    private static ForgeCliRunner runner(StringWriter out, StringWriter err) {
        ForgeRouteRegistry registry = registry(false, Map.of());
        return new ForgeCliRunner(new ForgeListCommand(registry),
                new ForgeTypesCommand(registry, () -> FIXED_STAMP),
                new ForgeClearCommand(registry),
                new PrintWriter(out), new PrintWriter(err));
    }

    private static Result runList(ForgeRouteRegistry registry, CliOptions options) {
        return capture((out, err) -> new ForgeListCommand(registry).execute(options, out, err));
    }

    private static Result runTypes(ForgeRouteRegistry registry, CliOptions options) {
        return capture((out, err) -> new ForgeTypesCommand(registry, () -> FIXED_STAMP).execute(options, out, err));
    }

    private static Result runClear(ForgeRouteRegistry registry, CliOptions options) {
        return capture((out, err) -> new ForgeClearCommand(registry).execute(options, out, err));
    }

    private static Result capture(CommandCall call) {
        StringWriter so = new StringWriter();
        StringWriter se = new StringWriter();
        PrintWriter out = new PrintWriter(so);
        PrintWriter err = new PrintWriter(se);
        int code = call.run(out, err);
        out.flush();
        err.flush();
        return new Result(code, so.toString(), se.toString());
    }

    @FunctionalInterface
    private interface CommandCall {
        int run(PrintWriter out, PrintWriter err);
    }

    private static RouteInfo get(String name, String uri, String tier) {
        return get(name, uri, tier, List.of());
    }

    private static RouteInfo get(String name, String uri, String tier, List<String> params) {
        return new RouteInfo(name, uri, List.of("GET", "HEAD"), params, Map.of(), List.of(), tier, List.of(), null);
    }

    private static RouteInfo anon(String uri) {
        return new RouteInfo(null, uri, List.of("GET", "HEAD"), List.of(), Map.of(), List.of(), null, List.of(), null);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static List<String> textList(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static JsonNode findRoute(JsonNode payload, String name) {
        for (JsonNode route : payload.get("routes")) {
            if (name.equals(route.get("name").asText())) {
                return route;
            }
        }
        throw new AssertionError("route not found: " + name);
    }
}
