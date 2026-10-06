package io.github.routeforge.core.repository;

/**
 * 摘要端点要下发的运行时配置。
 *
 * <p>刻意与 Spring 的 {@code @ConfigurationProperties} 解耦：适配层负责从 {@code forge.*} 读出值并归一，
 * 核心层只按契约下发。两处归一化规则与 PHP 逐字一致：
 *
 * <ul>
 *   <li>{@code cacheTtl}：统一为 {@code Integer|null}（环境变量给字符串也转成整数），
 *       <b>负值归一为 null</b>——必须与 {@code RouteCache} 构造函数的同一归一化对齐，
 *       否则摘要告诉前端「缓存 -5 秒」、实际行为却是「不缓存」。</li>
 *   <li>{@code urlPrefix}：空串按 {@code null} 处理（前端视为无前缀）。</li>
 *   <li>{@code endpointPrefix}：此处保留原值，下发时经 {@code normalizeEndpointPrefix} 规范化，
 *       与端点注册路径同源。</li>
 * </ul>
 *
 * @param schemeVersion 摘要格式版本（{@code schemeVersion}）
 * @param strictMode    严格模式（{@code config.strict_mode}）
 * @param endpointPrefix 端点前缀原值
 * @param urlPrefix     应用路由前缀，可为 null
 * @param cacheTtl      统一缓存 TTL，可为 null
 */
public record RepositoryConfig(Integer schemeVersion, boolean strictMode, String endpointPrefix, String urlPrefix,
        Integer cacheTtl) {

    public RepositoryConfig {
        // 与 RouteCache 同款归一化：负值视为不缓存
        if (cacheTtl != null && cacheTtl < 0) {
            cacheTtl = null;
        }
        if (urlPrefix != null && urlPrefix.isEmpty()) {
            urlPrefix = null;
        }
    }

    /** 未配置（null）时回落契约默认值；显式配置 0 也按 0 下发（与 PHP 的 ?? 判据一致）。 */
    public int schemeVersionOrDefault() {
        return schemeVersion == null ? RouteRepository.SCHEME_VERSION : schemeVersion;
    }
}
