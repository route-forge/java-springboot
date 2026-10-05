package io.github.routeforge.core.alias;

import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.exception.AliasTargetException;
import io.github.routeforge.core.filter.RouteNameFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路由别名解析器（SPEC §3.1.7）。
 *
 * <p>把两个声明通道合并成一张「别名 → 真实路由名」映射：
 * <ol>
 *   <li>注解通道 {@code @ForgeRoute(aliases=...)} / {@code @Forge(aliases=...)}——别名写在<b>被指向</b>
 *       （真实名）的路由上，适配层提取进 {@link RouteInfo#forgeAliases()}；对应 Laravel 的
 *       {@code ->forgeAlias()} 宏；</li>
 *   <li>配置通道 {@code forge.aliases} —— 集中批量声明（键=别名，值=真实路由名）。</li>
 * </ol>
 *
 * <p>合并规则（对齐 tier 的「显式 &gt; 配置」优先级）：同一别名两通道都声明时注解通道优先、目标不同则告警；
 * 别名与真实路由名撞车时真实名优先、别名丢弃并告警；别名目标不存在（悬空）抛
 * {@link AliasTargetException}（RF_BE_008）fail-fast。
 *
 * <p>别名是<b>元信息层</b>概念：不参与层级解析与 strict_mode，条目在目标路由所在层级注入（见仓库层）。
 */
public final class AliasResolver {

    private final Map<String, Object> configAliases;
    private final RouteNameFilter filter;

    public AliasResolver(Map<String, Object> configAliases) {
        this(configAliases, new RouteNameFilter());
    }

    /**
     * @param configAliases {@code forge.aliases} 映射（键=别名，值=真实路由名）
     * @throws IllegalArgumentException 映射形态非法（空键/空目标/非字符串目标）——这是配置写错，
     *                                  与 PHP 侧一样在构造期即拒，不留到扫描阶段静默丢弃
     */
    public AliasResolver(Map<String, Object> configAliases, RouteNameFilter filter) {
        this.configAliases = configAliases;
        this.filter = filter;
        configAliases.forEach((alias, target) -> {
            if (alias == null || alias.isEmpty() || !(target instanceof String text) || text.isEmpty()) {
                throw new IllegalArgumentException(
                        "forge.aliases must be a map of [alias(string) => route name(string)]; got ["
                                + varExport(alias) + " => " + varExport(target) + "]");
            }
        });
    }

    /**
     * 从路由信息表解析别名映射。
     *
     * @param infos 适配层归一后的路由信息（本方法内部做排除过滤，调用方无需预筛）
     * @throws AliasTargetException 任一生效别名的目标路由名不在路由表中
     */
    public AliasResolution resolve(List<RouteInfo> infos) {
        Set<String> realNames = new LinkedHashSet<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> collisions = new LinkedHashMap<>();

        // 第一遍：收集真实路由名与注解通道声明（声明序，先声明者优先）
        Map<String, List<String>> macroDeclarations = new LinkedHashMap<>();

        for (RouteInfo info : infos) {
            String name = info.name();
            if (name == null || name.isEmpty()) {
                // 无名路由上的别名声明会随路由一起被忽略（别名跟随目标路由的命名元信息注入，
                // 无名路由没有元信息可挂载）——但必须告警，否则声明者以为别名已经生效。
                for (String alias : info.forgeAliases()) {
                    if (alias != null && !alias.isEmpty()) {
                        warnings.add("Alias [" + alias + "] is declared via ->forgeAlias() on an unnamed route ("
                                + info.uri() + "); the declaration is ignored because the route has no name. "
                                + "Add ->name(...) to the route or move the alias to a named route.");
                    }
                }
                continue;
            }
            if (filter.isNameExcluded(name)) {
                // forge 自身端点与框架内部路由不参与别名体系
                continue;
            }
            realNames.add(name);

            for (String alias : info.forgeAliases()) {
                if (alias == null || alias.isEmpty()) {
                    continue;
                }
                // 列表首元素即胜出者，后续元素只用于「多处重复声明」告警
                macroDeclarations.computeIfAbsent(alias, key -> new ArrayList<>()).add(name);
            }
        }

        // 第二遍：注解通道统一过「与真实名撞车」检查。撞车时真实路由优先、别名丢弃——
        // 缺这一步会让别名静默覆盖端点元信息里真实路由的条目，属数据污染。
        macroDeclarations.forEach((alias, targets) -> {
            String target = targets.get(0);
            if (realNames.contains(alias)) {
                collisions.put(alias, target);
                warnings.add("Alias [" + alias
                        + "] collides with a real route name; the real route wins and the alias is ignored.");
                return;
            }
            aliases.put(alias, target);
        });

        // 同一别名声明在多条路由上：先声明者优先，重复的告警（不算撞车，别名仍生效）
        macroDeclarations.forEach((alias, targets) -> {
            if (targets.size() < 2 || collisions.containsKey(alias)) {
                return;
            }
            String joined = String.join(", ", new LinkedHashSet<>(targets));
            warnings.add("Alias [" + alias + "] is declared via ->forgeAlias() on multiple routes (" + joined
                    + "); the first declaration wins and the later ones are ignored.");
        });

        // 合并配置通道：注解优先（已存在的别名不被覆盖）；撞车规则与注解通道一致
        configAliases.forEach((alias, rawTarget) -> {
            String target = String.valueOf(rawTarget);
            if (realNames.contains(alias)) {
                // 红行展示取首次记录的指向：注解通道先记录则保留注解指向
                collisions.putIfAbsent(alias, target);
                warnings.add("Alias [" + alias
                        + "] collides with a real route name; the real route wins and the alias is ignored.");
                return;
            }
            String existing = aliases.get(alias);
            if (existing != null) {
                if (!existing.equals(target)) {
                    warnings.add("Alias [" + alias + "] is declared both via ->forgeAlias() [→ " + existing
                            + "] and config [→ " + target + "]; the explicit macro wins.");
                }
                return;
            }
            aliases.put(alias, target);
        });

        // 悬空校验：目标必须是真实存在的用户路由名（撞车被忽略的声明不参与）
        aliases.forEach((alias, target) -> {
            if (!realNames.contains(target)) {
                throw new AliasTargetException("Alias [" + alias + "] points to route name [" + target
                        + "], which does not exist in the current route table. Update or remove the alias in "
                        + "config/forge.php or the ->forgeAlias() declaration.");
            }
        });

        return new AliasResolution(aliases, warnings, collisions);
    }

    /** 近似 PHP {@code var_export(..., true)}：字符串带单引号，null 为 NULL，标量原样。 */
    private static String varExport(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String text) {
            return "'" + text + "'";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return String.valueOf(value);
    }
}
