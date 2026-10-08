package io.github.routeforge.spring.support;

import java.util.Optional;
import org.springframework.util.ClassUtils;

/**
 * 「classpath 上有没有 Spring Security」的启动提示（SPEC §4.4：本包不代宿主配 {@code SecurityFilterChain}）。
 *
 * <p>当宿主没引入 Spring Security 时，{@code /_forge/**} 元信息端点（含 P5 管理器）就是<b>裸</b>的——
 * 包自身不做也不该做鉴权（铁律 3），但必须<b>如实提醒</b>，否则运维以为默认受保护。检测到缺 Security
 * 即在启动打一条 WARN（由 {@code ForgeAutoConfiguration} 触发一次）。
 *
 * <p>探针选 {@code org.springframework.security.web.SecurityFilterChain}（spring-security-web，即「有 web 层
 * 安全配置能力」的标志）而非 core——只有方法级安全、没有 web {@code SecurityFilterChain} 的宿主，其 HTTP 端点
 * 同样不受保护，正是本提示要覆盖的情形。探针常量、{@code ClassLoader} 可注入，故可脱离容器直接单测。
 */
public final class ForgeSecurityAdvisory {

    static final String SECURITY_PROBE = "org.springframework.security.web.SecurityFilterChain";

    private final boolean securityPresent;

    public ForgeSecurityAdvisory(boolean securityPresent) {
        this.securityPresent = securityPresent;
    }

    /** 按给定类加载器探测 Spring Security 是否在 classpath 上。 */
    public static ForgeSecurityAdvisory detect(ClassLoader classLoader) {
        return new ForgeSecurityAdvisory(ClassUtils.isPresent(SECURITY_PROBE, classLoader));
    }

    public boolean securityPresent() {
        return securityPresent;
    }

    /** 缺 Security 时的告警文案；有 Security 则空（不打扰）。 */
    public Optional<String> warningMessage() {
        if (securityPresent) {
            return Optional.empty();
        }
        return Optional.of("Route Forge：classpath 未检测到 Spring Security（" + SECURITY_PROBE + " 缺失）。"
                + "本包不注册/改写宿主的 SecurityFilterChain（SPEC §4.4 铁律 3），故 /_forge/** 元信息端点"
                + "（含管理器）当前无鉴权保护。需要保护请宿主自行 "
                + "authorizeHttpRequests(auth -> auth.requestMatchers(\"/_forge/**\").authenticated())。");
    }
}
