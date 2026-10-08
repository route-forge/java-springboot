package io.github.routeforge.spring.summary;

import io.github.routeforge.core.summary.SummaryRenderer;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;

/**
 * 首页内嵌摘要的框架无关渲染入口（SPEC §5.4「纯 Java 渲染 API」）。
 *
 * <p>把摘要端点的返回值交给核心层 {@link SummaryRenderer}，产出一段可直接内嵌进 HTML {@code <head>}
 * 的 {@code <script>}（{@code window.__ROUTE_FORGE__} 一次性自删访问器），让前端 {@code @route-forge/core}
 * 初始化时跳过首屏那次摘要 HTTP 往返。
 *
 * <p><b>producer 复用</b>：摘要只来自 {@link ForgeRouteRegistry#summary()}，与摘要端点同一份结构、同一套
 * {@code RouteCache}——缓存、{@code debug} 旁路、包自身路由排除等语义因此自动继承，本类不另起扫描。
 *
 * <p>宿主用法：任何模板都能取到这段 HTML，但必须<b>原样输出、不能再转义一次</b>（里面的 {@code <}/{@code >}
 * 已按前端消费契约安全编码）：
 * <ul>
 *   <li>Thymeleaf：已注册 {@code forgeSummary} 方言时用 {@code th:utext="${#forgeSummary.summary}"}；
 *       未引 Thymeleaf 依赖也能走 Spring Bean 引用 {@code th:utext="${@forgeSummaryEmbed.script()}"}；</li>
 *   <li>其它模板/JSP/Servlet：把 {@link #script()} 的返回写进响应，注意用「非转义」输出通道。</li>
 * </ul>
 *
 * <p>安全边界（如实）：一次性自删只缩小数据在 {@code window} 的运行时驻留面；摘要仍随 HTML 源码可见，
 * 不是抗 XSS / 抗窃取的硬边界。且与摘要端点同规：{@code strict-mode} 有违例时 {@code summary()} 会抛
 * {@code RF_BE_009}，本方法随之抛出（宿主页面 500），不会静默降级。
 */
public class ForgeSummaryEmbed {

    private final ForgeRouteRegistry registry;

    public ForgeSummaryEmbed(ForgeRouteRegistry registry) {
        this.registry = registry;
    }

    /** 渲染内嵌摘要的 {@code <script>} 原文（已安全编码，调用方须原样输出）。 */
    public String script() {
        return SummaryRenderer.render(registry.summary());
    }
}
