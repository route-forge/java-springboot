package io.github.routeforge.core.dto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 统一路由信息：框架适配层把原生路由对象归一成它，核心层的所有业务逻辑
 * （层级解析 / 别名 / 仓库 / 类型生成）只消费本结构，不接触任何框架类。
 *
 * <p>与 Laravel 适配包的 {@code RouteForge\Common\Dto\RouteInfo} 逐字段对应。
 *
 * @param name             路由名；未命名路由为 {@code null}（这是「进不了任何元信息」的判据）
 * @param uri              已归一化的 URI 模板（Spring 侧须先剥离 {@code {x:regex}} 约束）
 * @param methods          HTTP 方法集合，保留原始值（含 HEAD），大写
 * @param parameters       路径参数名，按 URI 中出现顺序
 * @param parameterDefaults 路径参数默认值；无默认值时为空 Map（不得为 null，端点要序列化成 {@code {}}）
 * @param middleware       该路由的中间件标签集合（gathered，已去框架包装）
 * @param tier             显式或继承得到的层级标记；未标注为 {@code null}
 * @param forgeAliases     注解通道声明的别名列表
 * @param source           原始框架路由对象引用，供 classifier 回调取回宿主类型使用；核心层不读它
 */
public record RouteInfo(
        String name,
        String uri,
        List<String> methods,
        List<String> parameters,
        Map<String, Object> parameterDefaults,
        List<String> middleware,
        String tier,
        List<String> forgeAliases,
        Object source) {

    public RouteInfo {
        methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        parameterDefaults = Map.copyOf(Objects.requireNonNull(parameterDefaults, "parameterDefaults"));
        middleware = List.copyOf(Objects.requireNonNull(middleware, "middleware"));
        forgeAliases = List.copyOf(Objects.requireNonNull(forgeAliases, "forgeAliases"));
        Objects.requireNonNull(uri, "uri");
    }

    /** 是否显式标注过层级（优先级链第 1、2 级的入口判据）。 */
    public boolean hasExplicitTier() {
        return tier != null && !tier.isBlank();
    }

    public boolean isUnnamed() {
        return name == null || name.isBlank();
    }
}
