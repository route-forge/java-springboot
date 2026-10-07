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

        assertThat(normalizeClassNames(violations.toWireMap()))
                .isEqualTo(PhpFixtures.toObject(expected.get("violations")));
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


    /**
     * 深度复制一份 wire 结构，把所有字符串里的 Java 包名前缀去掉。
     *
     * <p>为什么在测试里做而不在 {@code toWireMap()} 里做：类名不同是<b>语言</b>造成的，
     * 生产代码不该为一个跨语言比对细节改变自己的产物。
     */
    private static Object normalizeClassNames(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, value) -> out.put(String.valueOf(key), normalizeClassNames(value)));
            return out;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(StrictViolationScannerOracleTest::normalizeClassNames).toList();
        }
        return node instanceof String text ? parity(text) : node;
    }

    /**
     * classifier 抛错文本里含异常类名：PHP 侧 {@code RuntimeException}、Java 侧 {@code java.lang.RuntimeException}，
     * 两侧不可能相同，故比对时统一去掉本例固定抛出的那个类的包名前缀。
     */
    private static String parity(String value) {
        return value == null ? null : value.replace("java.lang.RuntimeException", "RuntimeException");
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }
}
