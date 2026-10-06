package io.github.routeforge.core.type;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.routeforge.core.PhpFixtures;
import io.github.routeforge.core.alias.AliasResolver;
import io.github.routeforge.core.analyzer.RouteAnalyzer;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.filter.RouteNameFilter;
import io.github.routeforge.core.support.WarningSink;
import io.github.routeforge.core.tier.TierResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * d.ts 与 {@code --json} 产物的跨语言对等测试：<b>整文</b>比对，不抽样。
 *
 * <p>d.ts 是要 commit 进前端仓库的契约文本，排版差异（缩进、空行、分隔线宽度、键是否加引号）
 * 会让两个后端产出的文件在 diff 里看起来像「后端改了什么」。所以这里连空格都算差异。
 *
 * <p>唯一被归一的是文件头的生成时间：PHP 侧 {@code generateDts()} 内部取当前时间，
 * Java 侧把它做成显式入参（可测），比对时两侧都替换为占位符。
 */
class TypeGeneratorOracleTest {

    private static final String FIXED_TIMESTAMP = "2026-01-01T00:00:00.000Z";

    @TestFactory
    Stream<DynamicTest> matchesPhpOracle() {
        JsonNode cases = PhpFixtures.read("types.json").get("typeCases");
        List<JsonNode> entries = new ArrayList<>();
        cases.forEach(entries::add);
        assertThat(entries).as("类型生成用例表不应静默变空").isNotEmpty();

        return entries.stream().map(entry -> DynamicTest.dynamicTest(entry.get("name").asText(),
                () -> runCase(entry)));
    }

    private static void runCase(JsonNode entry) {
        JsonNode input = entry.get("input");
        JsonNode expected = entry.get("expected");

        LevelsConfig levels = PhpFixtures.toLevels(input.get("levels"));
        TierResolver resolver = new TierResolver(levels, null, false, WarningSink.NOOP);
        RouteNameFilter filter = new RouteNameFilter();
        var infos = PhpFixtures.toRoutes(input.get("infos"));
        var analyzer = new RouteAnalyzer(resolver,
                new AliasResolver(PhpFixtures.toMap(input.get("aliases")), filter), filter);

        TypeGenerator generator = new TypeGenerator();
        List<String> targets = PhpFixtures.strings(input.get("targets"));
        Map<String, Map<String, TypeGenerator.TypeEntry>> collected =
                generator.collectTargets(analyzer.analyze(infos).rows(), targets);

        assertThat(normalizeTimestamp(generator.generateDts(collected, input.get("endpointPrefix").asText(),
                FIXED_TIMESTAMP)))
                .as("d.ts 必须逐字节一致")
                .isEqualTo(expected.get("dts").asText());
        assertThat(generator.generateJson(collected))
                .as("--json 产物必须逐字节一致")
                .isEqualTo(expected.get("json").asText());
    }

    private static String normalizeTimestamp(String dts) {
        return dts.replace("// 生成时间: " + FIXED_TIMESTAMP, "// 生成时间: {{TIMESTAMP}}");
    }
}
