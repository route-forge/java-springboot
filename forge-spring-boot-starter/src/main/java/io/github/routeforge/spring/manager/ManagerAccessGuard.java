package io.github.routeforge.spring.manager;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理器端点的 IP 白名单守卫（SPEC §3、§5.3）。纯字符串判定、零 WebRequest/Servlet 依赖，故可脱离容器直接单测。
 *
 * <p>语义（与 {@code forge.manager.allowed-ips} 默认 {@code [127.0.0.1, ::1]} 一致）：
 * <ul>
 *   <li>含 {@code "*"} → 放行任意来源；</li>
 *   <li>空列表 → 不限制（视为放行任意；「关掉白名单」的显式写法）；</li>
 *   <li>否则：来源 IP 归一后必须命中白名单某项；来源不可解析（{@code null}）→ 拒绝。</li>
 * </ul>
 *
 * <p><b>取值口径</b>：控制器经 Spring 的 {@link org.springframework.web.context.request.WebRequest#getRemoteAddress()}
 * 拿地址（不自己读 {@code X-Forwarded-For}）。反向代理场景须由宿主启用 {@code ForwardedHeaderFilter} 让
 * {@code getRemoteAddress()} 反映真实来源，否则代理 IP 恒等于本机、白名单形同虚设——这一点写进 SPEC §5.3。
 */
public final class ManagerAccessGuard {

    private final boolean allowAny;
    private final List<String> allowed;

    public ManagerAccessGuard(List<String> allowedIps) {
        List<String> raw = allowedIps == null ? List.of() : allowedIps;
        this.allowAny = raw.isEmpty() || raw.stream().anyMatch(s -> "*".equals(trim(s)));
        List<String> normalized = new ArrayList<>();
        for (String entry : raw) {
            String n = normalize(entry);
            if (!n.isEmpty() && !"*".equals(n)) {
                normalized.add(n);
            }
        }
        this.allowed = List.copyOf(normalized);
    }

    boolean allows(String remoteIp) {
        return allowAny || (remoteIp != null && allowed.contains(normalize(remoteIp)));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * IPv6 回环多形态归一：{@code 0:0:0:0:0:0:0:1} 与 {@code ::1} 视为同；去方括号与 {@code %zone} 作用域后缀、转小写。
     * 这是「默认仅回环」在 Linux/Windows 上都能被本机访问命中的关键（JDK 常把 v6 回环吐成全展开形态）。
     */
    static String normalize(String ip) {
        String s = trim(ip);
        if (s.startsWith("[") && s.endsWith("]") && s.length() > 2) {
            s = s.substring(1, s.length() - 1);
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        s = s.toLowerCase();
        if (s.equals("0:0:0:0:0:0:0:1")) {
            return "::1";
        }
        return s;
    }
}
