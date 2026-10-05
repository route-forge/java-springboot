package io.github.routeforge.core.support;

import java.util.List;

/** HTTP 方法集合的展示辅助（框架无关）。 */
public final class HttpMethods {

    private HttpMethods() {
    }

    /**
     * 展示用方法列表：过滤 HEAD。
     *
     * <p>与端点元信息「含 HEAD」、而类型生成/命令行/管理器「去 HEAD」的口径区分开：
     * 本方法服务于后者。大小写不敏感，输出保序。
     */
    public static List<String> withoutHead(List<String> methods) {
        return methods.stream().filter(m -> !"HEAD".equalsIgnoreCase(m)).toList();
    }

    /** 取首个非 HEAD 方法（d.ts 的 {@code method} 字段口径）；全为 HEAD 或空列表时回落 GET。 */
    public static String primary(List<String> methods) {
        return withoutHead(methods).stream().findFirst().orElse("GET");
    }

    /** 是否需要 body 类型（POST / PUT / PATCH）。 */
    public static boolean hasBody(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
    }

    /**
     * 命令行与严格模式清单里的方法列：去 HEAD 后以竖线连接，全为 HEAD 时给破折号占位。
     *
     * <p>占位符用 {@code —}（em dash）而非 {@code -}，与 PHP 侧保持同一字符——清单文本要做整句比对。
     */
    public static String displayJoin(List<String> methods) {
        List<String> visible = withoutHead(methods);
        return visible.isEmpty() ? "—" : String.join("|", visible);
    }
}
