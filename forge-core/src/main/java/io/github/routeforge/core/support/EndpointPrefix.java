package io.github.routeforge.core.support;

/**
 * 端点前缀规范化：保证前导 {@code /}、去掉尾部 {@code /}。
 *
 * <p>三处必须同源，否则前端按下发值拼出的 URL 与实际注册路径不一致：
 * 端点注册路径、摘要端点 {@code config.endpoint_prefix} 下发值、d.ts 文件头「端点」注释。
 * 另外 URI 段级排除也用本函数的结果（见 {@code RouteNameFilter}）。
 */
public final class EndpointPrefix {

    private EndpointPrefix() {
    }

    /**
     * 与 common 层 {@code RouteRepository::normalizeEndpointPrefix()} 同式：
     * {@code '/' . ltrim(rtrim($prefix, '/'), '/')}。
     */
    public static String normalize(String prefix) {
        String trimmed = stripTrailing(prefix);
        return "/" + stripLeading(trimmed);
    }

    /** 去尾部斜杠（多去若干）。 */
    public static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    /** 去前导斜杠（多去若干）。 */
    public static String stripLeading(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') {
            start++;
        }
        return value.substring(start);
    }
}
