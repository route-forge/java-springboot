package io.github.routeforge.spring.config;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code forge.*} 配置绑定（键表见 SPEC §3）。
 *
 * <p><b>层级配置刻意绑成 {@code Map<String, Map<String, Object>>} 而不是强类型 record</b>：
 * 核心层的匹配语义要复刻 PHP 侧对「配置写错形态」的宽容与告警（{@code prefix} 写成单值、
 * {@code middleware-match} 写成 int/未知字符串等，SPEC §3.1.2 类型归一化）。强类型绑定会在
 * 反序列化阶段就把这些情况挡成启动失败，等于<b>静默改变了行为</b>；这里原样收下，交给
 * {@code LevelsConfig} + {@code TierResolver} 按同一口径归一并开口告警。
 *
 * <p>数组型键接受单值（{@code forge.levels.admin.match.prefix=api/admin} 等价单元素列表），
 * 与 PHP 侧归一口径一致；这一条由 Spring 的宽松绑定完成，因此断言里专门压一例。
 *
 * @param levels            层级名 → 该层级的原始配置（保序：顺序即 last-wins 优先级）
 * @param endpointPrefix    端点前缀（下发前经规范化）
 * @param urlPrefix         下发给前端的 URL 前缀，可为 null
 * @param endpointMiddleware 摘要端点的访问要求；<b>Java 侧仅作声明值</b>（SPEC §4.4 差异 4）
 * @param cacheTtl          统一 TTL 秒；null 不缓存、0 永久、负值归一为不缓存
 * @param cacheDriver       memory / redis
 * @param strictMode        严格模式
 * @param schemeVersion     摘要格式版本；未配置时回落 1
 * @param aliases           别名映射（键=别名，值=真实路由名）
 * @param manager           管理器页面相关开关
 */
@ConfigurationProperties(prefix = "forge")
public record ForgeProperties(
        Map<String, Map<String, Object>> levels,
        String endpointPrefix,
        String urlPrefix,
        List<String> endpointMiddleware,
        Integer cacheTtl,
        String cacheDriver,
        Boolean strictMode,
        Integer schemeVersion,
        Map<String, Object> aliases,
        Manager manager) {

    /** 未显式配置时的契约默认端点前缀。 */
    public static final String DEFAULT_ENDPOINT_PREFIX = "/_forge/routes";

    /** 与 Laravel 的 config/forge.php 同默认值。 */
    public static final int DEFAULT_CACHE_TTL = 3600;

    /**
     * @param enabled    管理器页面开关（与 {@code debug=true} 取 AND，SPEC §4.7）
     * @param allowedIps IP 白名单：默认仅回环；{@code "*"} 放行任意来源；空列表 = 不限制
     */
    public record Manager(Boolean enabled, List<String> allowedIps) {

        public Manager {
            enabled = enabled != null && enabled;
            allowedIps = allowedIps == null ? List.of("127.0.0.1", "::1") : List.copyOf(allowedIps);
        }

        /** 未配置整个 {@code forge.manager} 块时的默认形态。 */
        public static Manager disabled() {
            return new Manager(false, null);
        }
    }

    public ForgeProperties {
        // 层级顺序即 last-wins 优先级，也是摘要 levels 的键序：Map.copyOf 会把两者都打散
        levels = levels == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(levels));
        endpointPrefix = endpointPrefix == null || endpointPrefix.isBlank() ? DEFAULT_ENDPOINT_PREFIX : endpointPrefix;
        endpointMiddleware = endpointMiddleware == null ? List.of() : List.copyOf(endpointMiddleware);
        cacheTtl = cacheTtl == null ? DEFAULT_CACHE_TTL : cacheTtl;
        cacheDriver = cacheDriver == null || cacheDriver.isBlank() ? "memory" : cacheDriver;
        strictMode = strictMode != null && strictMode;
        aliases = aliases == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(aliases)); // 别名声明顺序进产物
        manager = manager == null ? Manager.disabled() : manager;
    }

    /**
     * 交给核心层的层级配置：把 Boot 在 {@code Object} 位置上绑出的「索引 Map」还原成列表。
     *
     * <p>直接喂 {@link #levels()} 会让 {@code match.prefix: [/admin]} 变成 {@code {0=/admin}}，
     * 于是只靠 match 归级的路由全部掉进 unassigned（实测踩过）。归一逻辑与理由见 {@link YamlShape}。
     */
    public Map<String, Object> normalizedLevels() {
        return YamlShape.levels(levels);
    }
}
