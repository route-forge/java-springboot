package io.github.routeforge.spring.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ForgeSecurityAdvisory} 的决策测试：有无 Security 两分支 + 空类加载器探测为「无」。 */
class ForgeSecurityAdvisoryTest {

    @Test
    @DisplayName("检测到 Security → 不出提示")
    void presentProducesNoWarning() {
        ForgeSecurityAdvisory advisory = new ForgeSecurityAdvisory(true);

        assertThat(advisory.securityPresent()).isTrue();
        assertThat(advisory.warningMessage()).isEmpty();
    }

    @Test
    @DisplayName("缺 Security → 提示点名 /_forge/** 无鉴权")
    void absentWarnsAboutUnprotectedEndpoints() {
        Optional<String> warning = new ForgeSecurityAdvisory(false).warningMessage();

        assertThat(warning).isPresent();
        assertThat(warning.get()).contains("/_forge").contains("SecurityFilterChain");
    }

    @Test
    @DisplayName("detect：空类加载器下探针类不可达 → 判定为无 Security")
    void detectWithEmptyClassLoaderIsAbsent() {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            assertThat(ForgeSecurityAdvisory.detect(empty).securityPresent())
                    .as("无任何可加载类的 classloader 里不该有 SecurityFilterChain")
                    .isFalse();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
