package io.github.routeforge.core.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一行路由（含别名行）。字段顺序与 {@code list --json} 的产物一致。
 *
 * @param name     显示名：真实路由名，或别名
 * @param level    归属层级名；未归级时为 {@code unassigned}（展示口径）
 * @param tier     解析结果原值；未归级为 {@code null}——{@code --unassigned} 按它判定，
 *                 不能用 {@code level}（后者已被兜底成字符串）
 * @param aliasOf  别名行的目标真实名；真实路由行为 {@code null}
 */
public record Row(
        String name,
        String level,
        String uri,
        List<String> methods,
        List<String> parameters,
        Map<String, Object> parameterDefaults,
        List<String> middleware,
        String tier,
        String aliasOf) {

    public Row {
        // unmodifiable 包装而非 copyOf：配置与默认值里允许 null 元素（PHP 侧同样宽容），
        // copyOf 会把归一化变成构造期 NPE。
        methods = Collections.unmodifiableList(new ArrayList<>(methods));
        parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        middleware = Collections.unmodifiableList(new ArrayList<>(middleware));
        parameterDefaults = Collections.unmodifiableMap(new LinkedHashMap<>(parameterDefaults));
    }

    /** 真实路由行（{@code alias_of} 为空）。 */
    public static Row ofReal(String name, String level, String uri, List<String> methods, List<String> parameters,
            Map<String, Object> parameterDefaults, List<String> middleware, String tier) {
        return new Row(name, level, uri, methods, parameters, parameterDefaults, middleware, tier, null);
    }

    /**
     * 命令层视图：与 PHP 侧 row 数组的键序逐一对应。
     *
     * <p>{@code parameter_defaults} 走<b>裸数组</b>语义（空 → 空数组），与层级端点里刻意强转成对象的
     * 空对象形态不同——分析器的行只喂命令行与管理器，PHP 那边它本来就是普通数组。
     * 命令行与管理器都从本方法取视图，避免同一形态规则写两份。
     */
    public Map<String, Object> asMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("level", level);
        row.put("uri", uri);
        row.put("methods", methods);
        row.put("parameters", parameters);
        row.put("parameter_defaults", parameterDefaults.isEmpty() ? List.of() : parameterDefaults);
        row.put("middleware", middleware);
        row.put("tier", tier);
        row.put("alias_of", aliasOf);
        return row;
    }

    /** 别名行：除 name 与 alias_of 外全部复制目标行（元信息层不可区分）。 */
    public Row asAliasOf(String alias, String target) {
        return new Row(alias, level, uri, methods, parameters, parameterDefaults, middleware, tier, target);
    }
}
