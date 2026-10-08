package io.github.routeforge.spring.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ManagerAccessGuard} 的纯判定测试：白名单三态 + IPv6 回环归一。 */
class ManagerAccessGuardTest {

    @Test
    @DisplayName("空列表 = 不限制：任意来源都放行（含 null 之外的地址）")
    void emptyAllowsAll() {
        ManagerAccessGuard guard = new ManagerAccessGuard(List.of());
        assertThat(guard.allows("203.0.113.7")).isTrue();
        assertThat(guard.allows("8.8.8.8")).isTrue();
    }

    @Test
    @DisplayName("null 入参按空处理 → 不限制（与 ForgeProperties.Manager 默认不同，那条默认是回环）")
    void nullTreatedAsNoRestriction() {
        assertThat(new ManagerAccessGuard(null).allows("1.2.3.4")).isTrue();
    }

    @Test
    @DisplayName("含 \"*\" 放行任意")
    void wildcardAllowsAll() {
        assertThat(new ManagerAccessGuard(List.of("*")).allows("9.9.9.9")).isTrue();
    }

    @Test
    @DisplayName("白名单命中 v4/v6 回环；展开式 v6 归一到 ::1；名单外与 null 拒绝")
    void loopbackWhitelist() {
        ManagerAccessGuard guard = new ManagerAccessGuard(List.of("127.0.0.1", "::1"));

        assertThat(guard.allows("127.0.0.1")).isTrue();
        assertThat(guard.allows("::1")).isTrue();
        assertThat(guard.allows("0:0:0:0:0:0:0:1")).as("JDK 常把 v6 回环吐成全展开形态").isTrue();
        assertThat(guard.allows("10.0.0.9")).isFalse();
        assertThat(guard.allows(null)).as("来源不可解析按拒绝").isFalse();
    }

    @Test
    @DisplayName("归一：去方括号与 %zone、转小写")
    void normalizeStripsBracketsZoneAndCase() {
        assertThat(ManagerAccessGuard.normalize("[fe80::1%eth0]")).isEqualTo("fe80::1");
        assertThat(ManagerAccessGuard.normalize("0:0:0:0:0:0:0:1")).isEqualTo("::1");
        assertThat(ManagerAccessGuard.normalize(" 127.0.0.1 ")).isEqualTo("127.0.0.1");
    }
}
