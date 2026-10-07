package io.github.routeforge.spring.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Spring Boot 配置绑定产出的「索引 Map」还原成列表形态。
 *
 * <p><b>为什么需要这一步</b>：{@code forge.levels} 为了让核心层保留 PHP 侧对畸形配置的宽容与告警
 * （SPEC §3.1.2），刻意绑成 {@code Map<String, Object>} 而不是强类型 record。而 Boot 的 Binder 在
 * 目标类型是 {@code Object} 时，会把 YAML 序列
 *
 * <pre>{@code
 * match:
 *   prefix:
 *     - /admin
 * }</pre>
 *
 * 绑成<b>索引为键的 Map</b>：{@code {0=/admin}}，而不是 {@code ["/admin"]}。实测就是端点把
 * 只靠 match 归级的路由掉进 unassigned 的原因。本类只修这个绑定产物，不改变任何匹配语义。
 *
 * <p>判定规则：Map 且<b>所有键都是非负十进制整数</b>才当列表处理（{@code match} 这类正常映射的键
 * 是 {@code description}/{@code load}/{@code match}，不受影响）；按索引升序取值，递归归一每个元素。
 */
final class YamlShape {

    private YamlShape() {
    }

    /** 递归归一一份层级配置：{@code {0=x,1=y}} → {@code [x,y]}，普通 Map 与标量原样保留。 */
    static Object value(Object node) {
        if (node instanceof Map<?, ?> map) {
            if (isIndexKeyed(map)) {
                List<Object> list = new ArrayList<>(map.size());
                // 补齐空位再按索引填值：YAML 允许跳号（0 与 2 有、1 缺），跳过的位保留 null
                map.forEach((key, val) -> {
                    int index = Integer.parseInt(String.valueOf(key));
                    while (list.size() <= index) {
                        list.add(null);
                    }
                    list.set(index, value(val));
                });
                // unmodifiable + 允许 null：配置写成 prefix: [null] 时 PHP 侧宽容归一，这里也必须跟着宽容
                return Collections.unmodifiableList(list);
            }
            return mapOf(map);
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(value(item)));
            return Collections.unmodifiableList(out);
        }
        return node;
    }

    /** 层级表的归一入口：保持层级声明顺序（last-wins 优先级依赖它）。 */
    static Map<String, Object> levels(Map<String, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((name, definition) -> out.put(name, value(definition)));
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Object> mapOf(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((key, val) -> out.put(String.valueOf(key), value(val)));
        return Collections.unmodifiableMap(out);
    }

    private static boolean isIndexKeyed(Map<?, ?> map) {
        if (map.isEmpty()) {
            return false;
        }
        for (Object key : map.keySet()) {
            String text = String.valueOf(key);
            if (text.isEmpty() || !text.chars().allMatch(Character::isDigit)) {
                return false;
            }
        }
        return true;
    }
}
