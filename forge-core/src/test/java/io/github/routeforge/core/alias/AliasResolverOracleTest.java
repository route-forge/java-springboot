package io.github.routeforge.core.alias;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.routeforge.core.PhpFixtures;
import io.github.routeforge.core.exception.AliasTargetException;
import io.github.routeforge.core.exception.ForgeRuntimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 别名解析的跨语言对等测试，期望值来自 {@code fixtures/php/expected/alias-strict.json}。
 *
 * <p>别名规则里有大量「谁优先」的判断（注解通道 vs 配置、撞车时真实名优先、先声明者优先），
 * 这些优先级一旦在 Java 侧被「理顺」成另一种顺序，前端拿到的路由名集合就会与 Laravel 后端不同——
 * 而这是唯一会让使用者踩到跨后端行为差异的地方，所以按整份结果比对，不抽样。
 */
class AliasResolverOracleTest {

    @TestFactory
    Stream<DynamicTest> matchesPhpOracle() {
        JsonNode cases = PhpFixtures.read("alias-strict.json").get("aliasCases");
        List<JsonNode> entries = new ArrayList<>();
        cases.forEach(entries::add);
        assertThat(entries).as("别名用例表不应静默变空").isNotEmpty();

        return entries.stream().map(entry -> DynamicTest.dynamicTest(
                entry.get("input").get("name").asText(), () -> runCase(entry)));
    }

    private static void runCase(JsonNode entry) {
        JsonNode input = entry.get("input");
        JsonNode expected = entry.get("expected");
        var infos = PhpFixtures.toRoutes(input.get("infos"));
        var config = PhpFixtures.toMap(input.get("config"));
        // 配置形态非法在 PHP 侧是构造期即拒，所以构造动作也要留在被断言的表达式里
        JsonNode forgeException = expected.get("exception");
        if (forgeException != null && !forgeException.isNull()) {
            assertThatThrownBy(() -> new AliasResolver(config).resolve(infos))
                    .isInstanceOfSatisfying(ForgeRuntimeException.class, error -> {
                        assertThat(error.code()).isEqualTo(forgeException.get("code").asText());
                        assertThat(error.httpStatus()).isEqualTo(forgeException.get("httpStatus").asInt());
                        assertThat(error.getMessage()).isEqualTo(forgeException.get("message").asText());
                    });
            return;
        }

        if (expected.hasNonNull("plainException")) {
            // PHP 侧配置形态非法走的是普通 InvalidArgumentException（不占 Forge 错误码）：
            // 它属于「配置文件写错」，与运行期路由表无关，Java 侧同样用 IllegalArgumentException 对齐
            assertThatThrownBy(() -> new AliasResolver(config).resolve(infos))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(expected.get("message").asText());
            return;
        }

        AliasResolution actual = new AliasResolver(config).resolve(infos);

        assertThat(actual.aliases()).isEqualTo(PhpFixtures.toMap(expected.get("aliases")));
        assertThat(actual.collisions()).isEqualTo(PhpFixtures.toMap(expected.get("collisions")));
        assertThat(actual.warnings())
                .as("warnings 的条数、顺序与整句文案都是契约（CI 按「warnings 非空即失败」门禁）")
                .isEqualTo(texts(expected.get("warnings")));
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }
}
