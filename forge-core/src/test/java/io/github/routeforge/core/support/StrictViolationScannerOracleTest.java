package io.github.routeforge.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.routeforge.core.PhpFixtures;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.filter.RouteNameFilter;
import io.github.routeforge.core.tier.TierResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 严格模式扫描的跨语言对等测试，期望值来自 {@code fixtures/php/expected/alias-strict.json}。
 *
 * <p>比对分两层：结构化清单（分组、字段、排序）与渲染后的整句清单文本。后者是 HTTP 错误 message
 * 与命令行红色清单的唯一措辞来源，两侧必须逐字一致——宿主脚本与前端错误提示都按行匹配它。
 */
class StrictViolationScannerOracleTest {

    @TestFactory
    Stream<DynamicTest> matchesPhpOracle() {
        JsonNode cases = PhpFixtures.read("alias-strict.json").get("strictCases");
        List<JsonNode> entries = new ArrayList<>();
        cases.forEach(entries::add);
        assertThat(entries).as("严格模式用例表不应静默变空").isNotEmpty();

        return entries.stream().map(entry -> DynamicTest.dynamicTest(
                entry.get("input").get("name").asText(), () -> runCase(entry)));
    }

    private static void runCase(JsonNode entry) {
        JsonNode input = entry.get("input");
        JsonNode expected = entry.get("expected");

        LevelsConfig levels = PhpFixtures.toLevels(input.get("levels"));
        TierResolver resolver = new TierResolver(
                levels,
                PhpFixtures.toClassifier(input.get("classifier")),
                true,
                WarningSink.NOOP);
        RouteNameFilter filter = new RouteNameFilter(
                RouteNameFilter.FORGE_PREFIXES,
                PhpFixtures.strings(input.get("uriPrefixes")));
        var scanner = new StrictViolationScanner(resolver, filter);

        var violations = scanner.scan(PhpFixtures.toRoutes(input.get("infos")));

        assertThat(toWire(violations)).isEqualTo(PhpFixtures.toObject(expected.get("violations")));
        assertThat(violations.count()).isEqualTo(expected.get("count").asInt());
        assertThat(violations.format().stream().map(StrictViolationScannerOracleTest::parity).toList())
                .isEqualTo(texts(expected.get("lines")));
        assertThat(parity(String.join("\n", violations.format())))
                .as("异常 message 形态：与命令行清单同一字符串")
                .isEqualTo(expected.get("message").asText());
        assertThat(expected.get("memoized").asBoolean())
                .as("PHP 侧同实例二次扫描命中记忆；Java 侧由下面的断言验证同一行为")
                .isTrue();
        assertThat(scanner.scan(PhpFixtures.toRoutes(input.get("infos"))).count())
                .isEqualTo(violations.count());
    }

    /** Java 记录 → 与 PHP 侧完全同构的 Map（键名 snake_case、顺序一致），供逐字段比对。 */
    private static Map<String, Object> toWire(StrictViolationScanner.Violations violations) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("missing_name", violations.missingName().stream().map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uri", entry.uri());
            row.put("methods", entry.methods());
            row.put("level", entry.level());
            row.put("source", entry.source());
            return row;
        }).toList());
        wire.put("unassigned", violations.unassigned().stream().map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.name());
            row.put("uri", entry.uri());
            row.put("methods", entry.methods());
            row.put("middleware", entry.middleware());
            return row;
        }).toList());
        wire.put("unresolved", violations.unresolved().stream().map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.name());
            row.put("uri", entry.uri());
            row.put("reason", parity(entry.reason()));
            return row;
        }).toList());
        return wire;
    }

    /**
     * classifier 抛错文本里含异常类名：PHP 侧 {@code RuntimeException}、Java 侧 {@code java.lang.RuntimeException}，
     * 两侧不可能相同，故比对时统一去掉本例固定抛出的那个类的包名前缀。
     */
    private static String parity(String value) {
        return value.replace("java.lang.RuntimeException", "RuntimeException");
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }
}
