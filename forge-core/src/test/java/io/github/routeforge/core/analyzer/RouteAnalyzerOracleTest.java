package io.github.routeforge.core.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.routeforge.core.PhpFixtures;
import io.github.routeforge.core.alias.AliasResolver;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.filter.RouteNameFilter;
import io.github.routeforge.core.repository.RouteRepository;
import io.github.routeforge.core.support.WarningSink;
import io.github.routeforge.core.tier.TierResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 分析器的跨语言对等测试：期望值来自 {@code fixtures/php/expected/analyzer.json}，
 * 逐输出比对 {@code json_encode} 后的文本——命令行与渲染层的全部口径在这里锁死。
 *
 * <p>比文本而不是比对象，锁的是三类容易被「顺手整理」掉的东西：键的顺序、空集合是
 * {@code []} 还是 {@code {}}（{@code tier_counts} 空时 PHP 给 {@code []}，别名表空时给 {@code {}}）、
 * 以及 warnings 的整句措辞（下游有按 substring grep 的脚本与「warnings 非空即失败」的 CI 门禁）。
 */
class RouteAnalyzerOracleTest {

    @TestFactory
    Stream<DynamicTest> matchesPhpOracle() {
        JsonNode cases = PhpFixtures.read("analyzer.json").get("cases");
        List<JsonNode> entries = new ArrayList<>();
        cases.forEach(entries::add);
        assertThat(entries).as("分析器用例表不应静默变空").isNotEmpty();

        return entries.stream().map(entry -> DynamicTest.dynamicTest(entry.get("name").asText(),
                () -> runCase(entry)));
    }

    private static void runCase(JsonNode entry) {
        JsonNode outputs = entry.get("outputs");
        JsonNode input = entry.get("input");

        LevelsConfig levels = PhpFixtures.toLevels(input.get("levels"));
        boolean strict = input.get("strict").asBoolean();
        TierResolver resolver = new TierResolver(levels, PhpFixtures.toClassifier(input.get("classifier")), strict,
                WarningSink.NOOP);
        RouteNameFilter filter = new RouteNameFilter(RouteNameFilter.FORGE_PREFIXES,
                PhpFixtures.strings(input.get("uriPrefixes")));
        List<RouteInfo> infos = PhpFixtures.toRoutes(input.get("infos"));
        RouteAnalyzer analyzer = new RouteAnalyzer(
                resolver, new AliasResolver(PhpFixtures.toMap(input.get("aliases")), filter), filter);

        RouteAnalyzer.Analysis analysis = analyzer.analyze(infos);
        List<String> levelNames = levels.names();

        assertSame(outputs, "rows", rowsAsMaps(analysis.rows()));
        // tier_counts 是 PHP 普通数组：空 → []，与别名/撞车表被刻意强转成 {} 的形态不同
        assertSame(outputs, "tier_counts", analysis.tierCounts().isEmpty() ? List.of() : analysis.tierCounts());
        assertSame(outputs, "warnings", analysis.warnings());
        assertSame(outputs, "aliases", analysis.aliases());
        assertSame(outputs, "collisions", analysis.collisions());
        assertSame(outputs, "unnamed", analysis.unnamed().stream().map(RouteAnalyzer.Unnamed::asMap).toList());
        assertSame(outputs, "violations", analysis.violations().toWireMap());
        assertThat(analysis.violations().count())
                .isEqualTo(outputs.get("violationCount").asInt());
        assertSame(outputs, "unnamedWarnings", RouteAnalyzer.unnamedWarnings(analysis.unnamed(), levelNames));

        // --unnamed 视图的过滤组合：不过滤 / 每个真实层级 / unassigned / unresolved / 不存在的层级
        List<String> filters = new ArrayList<>();
        filters.add(null);
        filters.addAll(levelNames);
        filters.add(RouteRepository.UNASSIGNED_LEVEL);
        filters.add("unresolved");
        filters.add("不存在的层级");
        for (int index = 0; index < filters.size(); index++) {
            String filterValue = filters.get(index);
            String label = "formatUnnamed#" + index + ":" + (filterValue == null ? "all" : filterValue);

            assertThat(parity(write(RouteAnalyzer.formatUnnamed(analysis.unnamed(), levelNames, filterValue))))
                    .as("--unnamed 视图 %s", label)
                    .isEqualTo(outputs.get(label).asText());
        }

        // list --json 的过滤组合，顺序与 oracle 里的 combos 一一对应
        List<Object[]> combos = List.of(
                new Object[] {null, false, false},
                new Object[] {levelNames.isEmpty() ? null : levelNames.get(0), false, false},
                new Object[] {null, true, false},
                new Object[] {null, false, true},
                new Object[] {"unassigned", true, false});
        for (int index = 0; index < combos.size(); index++) {
            String level = (String) combos.get(index)[0];
            boolean onlyUnassigned = (Boolean) combos.get(index)[1];
            boolean onlyAliases = (Boolean) combos.get(index)[2];

            List<Row> rows = RouteAnalyzer.filterRows(analysis.rows(), level, onlyUnassigned, onlyAliases);
            Map<String, Object> payload = RouteAnalyzer.listPayload(levelNames, rows, analysis.tierCounts(),
                    analysis.warnings(), level, onlyUnassigned, onlyAliases);

            assertSame(outputs, "listPayload#" + index, payload);
        }
    }

    private static void assertSame(JsonNode outputs, String label, Object actual) {
        assertThat(parity(write(actual)))
                .as("输出 %s 必须与 PHP 逐字节一致", label)
                .isEqualTo(outputs.get(label).asText());
    }

    private static List<Map<String, Object>> rowsAsMaps(List<Row> rows) {
        return rows.stream().map(Row::asMap).toList();
    }


    /** classifier 抛错文本含异常类名（PHP 无包前缀）：比对时统一去掉 Java 侧的包名前缀。 */
    private static String parity(String value) {
        return value.replace("java.lang.RuntimeException", "RuntimeException");
    }

    /**
     * 极简 JSON 写出器，形态对齐 PHP {@code json_encode(UNESCAPED_UNICODE|UNESCAPED_SLASHES)}：
     * 紧凑无空格、斜杠与非 ASCII 不转义。仅测试用——生产侧的序列化归适配层的 Jackson。
     */
    private static String write(Object value) {
        StringBuilder out = new StringBuilder();
        emit(value, out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void emit(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            out.append('"').append(escape(text)).append('"');
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(escape(entry.getKey())).append("\":");
                emit(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                emit(list.get(i), out);
            }
            out.append(']');
        } else {
            throw new IllegalStateException("无法序列化：" + value.getClass());
        }
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
