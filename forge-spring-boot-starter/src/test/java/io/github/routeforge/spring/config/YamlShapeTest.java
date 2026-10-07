package io.github.routeforge.spring.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link YamlShape} 的边界：只把「全数字键的 Map」当列表，其它一律保持原样。
 *
 * <p>判错的代价不对称：漏转会让 match 规则失效（实测踩过）；<b>误转</b>更糟——层级配置里
 * 名字恰好全是数字键的普通映射会被吃掉。所以两种情况都要钉住。
 */
class YamlShapeTest {

    @Test
    @DisplayName("索引 Map 还原为列表，并按索引升序（乱序键也要还原对）")
    void indexKeyedMapBecomesList() {
        Map<String, Object> indexed = new LinkedHashMap<>();
        indexed.put("1", "/beta");
        indexed.put("0", "/alpha");

        assertThat(YamlShape.value(indexed)).isEqualTo(List.of("/alpha", "/beta"));
    }

    @Test
    @DisplayName("普通映射不受影响（description/load/match 这类键不是数字）")
    void ordinaryMapIsKept() {
        Map<String, Object> level = new LinkedHashMap<>();
        level.put("description", "运营接口");
        level.put("load", "lazy");

        assertThat(YamlShape.value(level)).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) YamlShape.value(level)).containsKeys("description", "load");
    }

    @Test
    @DisplayName("嵌套递归：match.prefix 与 middleware_match 的 DNF 列表都能还原")
    void normalizesRecursively() {
        Map<Object, Object> prefix = new LinkedHashMap<>();
        prefix.put(0, "/manage");
        Map<Object, Object> clause = new LinkedHashMap<>();
        clause.put(0, 0);
        clause.put(1, 1);
        Map<Object, Object> dnf = new LinkedHashMap<>();
        dnf.put(0, clause);
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("prefix", prefix);
        match.put("middleware_match", dnf);
        Map<String, Object> level = new LinkedHashMap<>();
        level.put("match", match);

        Map<String, Object> normalized = YamlShape.levels(Map.of("manage", level));

        @SuppressWarnings("unchecked")
        Map<String, Object> matchOut = (Map<String, Object>) ((Map<String, Object>) normalized.get("manage")).get("match");
        assertThat(matchOut.get("prefix")).isEqualTo(List.of("/manage"));
        assertThat(matchOut.get("middleware_match")).isEqualTo(List.of(List.of(0, 1)));
    }

    @Test
    @DisplayName("层级声明顺序在归一后仍保留（last-wins 优先级依赖它）")
    void keepsLevelOrder() {
        Map<String, Object> levels = new LinkedHashMap<>();
        levels.put("zzz", Map.of("load", "lazy"));
        levels.put("aaa", Map.of("load", "lazy"));

        assertThat(YamlShape.levels(levels).keySet()).containsExactly("zzz", "aaa");
    }
}
