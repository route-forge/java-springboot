package io.github.routeforge.core.alias;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 别名解析结果。
 *
 * <p>三个映射都保持插入顺序：命令清单、管理器页面与 d.ts 的输出顺序依赖它。
 *
 * @param aliases    生效的别名 → 真实路由名
 * @param warnings   非致命问题文案（撞车丢弃、宏重复声明、宏与 config 目标冲突、无名路由上的宏声明）
 * @param collisions 被忽略的撞车声明（别名 → 其声明指向），供 {@code list} 表格红行展示；
 *                   <b>不进入</b>端点元信息与 {@code --json} 的 routes
 */
public record AliasResolution(Map<String, String> aliases, List<String> warnings, Map<String, String> collisions) {

    public AliasResolution {
        aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
        warnings = List.copyOf(warnings);
        collisions = Collections.unmodifiableMap(new LinkedHashMap<>(collisions));
    }

    /** 无任何别名时的空结果（避免调用方各处 new 三份空容器）。 */
    public static AliasResolution empty() {
        return new AliasResolution(Map.of(), List.of(), Map.of());
    }
}
