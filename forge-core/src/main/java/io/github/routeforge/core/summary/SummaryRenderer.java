package io.github.routeforge.core.summary;

import io.github.routeforge.core.support.JsSafeEncoder;
import io.github.routeforge.core.support.JsonWriter;

/**
 * 首页内嵌摘要渲染器：把摘要端点的返回值以一段 {@code <script>} 内嵌进服务端渲染的 HTML，
 * 供前端 {@code @route-forge/core} 初始化时直接消费，跳过首屏的一次摘要 HTTP 往返。
 *
 * <p>框架无关：纯静态渲染。各框架的模板入口（Laravel 的 {@code @forgeSummary}、Thymeleaf 方言片段）
 * 调用本类，模板层不得自己拼这段脚本。
 *
 * <p>契约（对齐前端消费实现，勿单方面变更）：
 * <ul>
 *   <li>暴露的全局 key 固定为 {@link #GLOBAL_KEY}；</li>
 *   <li>值 = 与 {@code GET {endpoint_prefix}} 摘要端点逐字段一致的 JSON（复用同一 producer）；</li>
 *   <li>形态 = {@code defineProperty} 一次性 getter：读后即 delete、{@code enumerable:false}、
 *       {@code configurable:true}。</li>
 * </ul>
 *
 * <p>红线：
 * <ol>
 *   <li><b>producer 复用</b>：摘要只能来自 {@code RouteRepository.summary()}，与摘要端点同一份结构，
 *       不另起扫描——缓存、debug 旁路、包自身路由排除等语义因此自动继承；</li>
 *   <li><b>只嵌摘要</b>，绝不嵌层级路由表：受保护层级的明细不得预置进公开 HTML，各层级仍走 HTTP 懒加载；</li>
 *   <li><b>XSS 安全编码</b>：内嵌 JSON 必须经 {@link JsonWriter}（第一层 HEX 转义）+
 *       {@link JsSafeEncoder}（第二层字符串转义），禁裸拼；</li>
 *   <li>不递增 {@code schemeVersion}：这是既有摘要契约的「投递方式」扩展，非协议变更。</li>
 * </ol>
 *
 * <p>安全边界（如实说明）：一次性自删只缩小数据在 {@code window} 上的运行时驻留面；摘要数据仍随 HTML
 * 源码可见，不是抗 XSS / 抗网络窃取的硬边界。文档与实现都不得把它夸大成加密或安全机制。
 */
public final class SummaryRenderer {

    /** 前端约定消费的全局 key（勿改，与 {@code @route-forge/core} 的消费实现对齐）。 */
    public static final String GLOBAL_KEY = "__ROUTE_FORGE__";

    private SummaryRenderer() {
    }

    /**
     * 渲染可直接放进 HTML {@code <head>}（早于前端 bundle）的一段 {@code <script>}。
     *
     * <p>返回的是已安全编码的原始 HTML：里面的 {@code <} / {@code >} 已被转义，
     * 调用方必须<b>原样输出</b>，不能再经模板转义一次（否则前端拿到的是 HTML 实体，解析必失败）。
     */
    public static String render(Object summary) {
        return renderFromJson(JsonWriter.hexCompact(summary));
    }

    /**
     * 同上，但输入是第一层已经序列化好的 JSON 文本。
     *
     * <p>适配层若已用手上的 ObjectMapper 产出了摘要 JSON（带等价的 HEX 转义配置），走本方法可避免
     * 二次序列化带来的键序漂移。
     */
    public static String renderFromJson(String summaryJson) {
        String expression = JsSafeEncoder.asJsonParseExpression(summaryJson);

        return "<script>\n"
                + "Object.defineProperty(window, '" + GLOBAL_KEY + "', {\n"
                + "  configurable: true,\n"
                + "  enumerable: false,\n"
                + "  get: function () {\n"
                + "    var v = " + expression + ";\n"
                + "    delete window." + GLOBAL_KEY + ";\n"
                + "    return v;\n"
                + "  }\n"
                + "});\n"
                + "</script>";
    }
}
