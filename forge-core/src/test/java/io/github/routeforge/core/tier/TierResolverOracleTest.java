package io.github.routeforge.core.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.exception.ForgeRuntimeException;
import io.github.routeforge.core.support.WarningSink;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 跨语言对等测试：用例与期望值全部来自 {@code fixtures/php/expected/tier-resolver.json}，
 * 由 {@code fixtures/php/oracle-tier.php} 调<b>真实 php-common 代码</b>产出。
 *
 * <p>为什么要跑真 PHP 而不是照文档写断言：层级解析的细节里有不少「文档没写但行为存在」的东西——
 * {@code prefix !== ''} 的严格比较、{@code in_array} 的严格类型、DNF 越界索引、null 名字插值进错误消息。
 * 自写断言只会把「我以为的语义」测一遍，测不到对面那套实现的真实语义。
 *
 * <p>唯一按宽松比对的字段是 classifier 抛错的 {@code error}：内容含异常类名，两侧必然不同
 * （PHP {@code RuntimeException} / Java {@code java.lang.RuntimeException}），故按后缀比对。
 */
class TierResolverOracleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURE = Path.of(System.getProperty("forge.fixtures.dir"), "tier-resolver.json");

    @TestFactory
    Stream<DynamicNode> matchesPhpOracle() throws IOException {
        JsonNode root = MAPPER.readTree(FIXTURE.toFile());
        assertThat(root.get("provenance").get("generator").asText())
                .as("fixture 必须由 php-common 真实代码产出，不是手写样例")
                .isEqualTo("fixtures/php/oracle-tier.php");

        List<JsonNode> cases = new ArrayList<>();
        root.get("cases").forEach(cases::add);
        assertThat(cases).as("用例表不应静默变空").isNotEmpty();

        return cases.stream()
                .map(entry -> DynamicTest.dynamicTest(
                        entry.get("input").get("name").asText(), () -> runCase(entry)));
    }

    private static void runCase(JsonNode entry) {
        JsonNode input = entry.get("input");
        JsonNode expected = entry.get("expected");

        List<String> warnings = new ArrayList<>();
        TierResolver resolver = new TierResolver(
                toLevels(input.get("levels")),
                toClassifier(input.get("classifier")),
                input.get("strict").asBoolean(),
                (WarningSink) warnings::add);
        RouteInfo route = toRoute(input.get("route"));

        if ("probe".equals(expected.get("mode").asText())) {
            assertProbe(resolver.probe(route), expected.get("result"));
        } else {
            assertResolve(resolver, route, expected);
        }

        assertThat(warnings)
                .as("warning 的条数与文案都须与 PHP 逐字一致（命令行 warnings 通道与 CI 门禁依赖它）")
                .isEqualTo(strings(expected.get("warnings")));

        JsonNode parallel = expected.get("probeParallel");
        if (parallel != null && !parallel.isNull()) {
            assertThat(parallel.isObject())
                    .as("php-common 的 probe() 抛了异常，说明「probe 永不抛」契约已被破坏：%s", parallel)
                    .isTrue();
            assertThatCode(() -> assertProbe(resolver.probe(route), parallel))
                    .as("resolve() 与 probe() 共用同一套优先级求值，结果必须一致")
                    .doesNotThrowAnyException();
        }
    }

    private static void assertResolve(TierResolver resolver, RouteInfo route, JsonNode expected) {
        JsonNode exception = expected.get("exception");
        if (exception == null || exception.isNull()) {
            String expectedLevel = expected.get("result").isNull() ? null : expected.get("result").asText();

            assertThat(resolver.resolve(route))
                    .as("PHP 侧未抛异常，期望层级 %s", expectedLevel)
                    .isEqualTo(expectedLevel);
            return;
        }

        assertThatThrownBy(() -> resolver.resolve(route))
                .isInstanceOfSatisfying(ForgeRuntimeException.class, error -> {
                    assertThat(error.code()).isEqualTo(exception.get("code").asText());
                    assertThat(error.httpStatus()).isEqualTo(exception.get("httpStatus").asInt());
                    // 整句比对：错误消息是跨语言契约的一部分，命令行清单与 HTTP 错误体同源同措辞
                    assertThat(error.getMessage()).isEqualTo(exception.get("message").asText());
                });
    }

    private static void assertProbe(TierProbe actual, JsonNode expectedProbe) {
        assertThat(actual.level()).isEqualTo(textOrNull(expectedProbe.get("level")));
        assertThat(actual.source().wireName()).isEqualTo(expectedProbe.get("source").asText());
        assertThat(actual.requested()).isEqualTo(textOrNull(expectedProbe.get("requested")));

        String expectedError = textOrNull(expectedProbe.get("error"));
        if (expectedError == null) {
            assertThat(actual.error()).isNull();
        } else {
            assertThat(actual.error())
                    .as("classifier 抛错消息只比对「: 消息」这一不变部分")
                    .endsWith(expectedError);
        }
    }

    private static LevelsConfig toLevels(JsonNode node) {
        return new LevelsConfig(MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        }));
    }

    private static RouteInfo toRoute(JsonNode node) {
        return new RouteInfo(
                textOrNull(node.get("name")),
                node.get("uri").asText(),
                List.of("GET", "HEAD"),
                List.of(),
                Map.of(),
                strings(node.get("middleware")),
                textOrNull(node.get("tier")),
                List.of(),
                null);
    }

    /** classifier 描述符 → 回调，与 oracle 里 PHP 闭包的三种形态一一对应。 */
    private static RouteClassifier toClassifier(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.has("return")) {
            String value = textOrNull(node.get("return"));
            return route -> value;
        }
        if (node.has("returnRaw")) {
            Object raw = MAPPER.convertValue(node.get("returnRaw"), Object.class);
            return route -> raw;
        }
        if (node.has("throw")) {
            String message = node.get("throw").asText();
            return route -> {
                throw new RuntimeException(message);
            };
        }
        throw new IllegalStateException("未知 classifier 描述符：" + node);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(item -> out.add(item.asText()));
        return out;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
