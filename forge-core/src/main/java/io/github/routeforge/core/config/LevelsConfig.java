package io.github.routeforge.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 层级配置表：按声明顺序保序的层级集合。
 *
 * <p><b>为什么层级配置内部保留原始结构而不是强类型字段</b>：PHP 侧的匹配语义要处理一批「配置写错类型」的
 * 路径（{@code prefix} 写成单值、{@code middleware_match} 写成 int、写成未知字符串等），每种都有既定的
 * 归一化与告警口径（SPEC §3.1.2 类型归一化）。强类型模型会在反序列化阶段就把这些情况挡掉，
 * 等于静默改变了行为——跨语言对等要求这里与 PHP 一样宽容，并同样开口告警。
 *
 * <p>顺序即优先级：多层级同时命中时取最后一个（last-wins），因此新层级追加到末尾即可生效。
 *
 * <p>值类型宽容：PHP 的空关联数组经 {@code json_encode} 会产出 {@code []}（而非 {@code {}}），
 * 所以从 fixture 读回的层级配置可能是空 List 而非 Map；本类一律按「空配置」处理，不抛类型异常。
 *
 * @param levels 层级名 → 该层级的原始配置，保序
 */
public record LevelsConfig(Map<String, Object> levels) {

    public LevelsConfig {
        // LinkedHashMap 保序 + 只读视图：迭代顺序就是 last-wins 的判定顺序
        levels = Collections.unmodifiableMap(new LinkedHashMap<>(levels));
    }

    public static LevelsConfig empty() {
        return new LevelsConfig(Map.of());
    }

    /** 层级名列表（声明顺序）。 */
    public List<String> names() {
        return List.copyOf(levels.keySet());
    }

    public boolean containsLevel(String name) {
        return levels.containsKey(name);
    }

    /** 某层级的原始配置 Map；非 Map（空数组等畸形形态）按空表处理。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> rawOf(String name) {
        return levels.get(name) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** 取某层级的 {@code match} 子表；缺键或类型不符按空表处理（与 PHP 的 {@code ?? []} 同）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> matchOf(String name) {
        return rawOf(name).get("match") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** 某层级的任意标量/集合配置项，缺失返回 null。 */
    public Object optionOf(String name, String key) {
        return rawOf(name).get(key);
    }

    public int size() {
        return levels.size();
    }

    public boolean isEmpty() {
        return levels.isEmpty();
    }
}
