package io.github.routeforge.spring.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.routeforge.spring.annotation.tierpkg.InheritedController;
import io.github.routeforge.spring.annotation.tierpkg.OverridingController;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ForgeTiers} 的就近覆盖方向测试：方法 &gt; 类 &gt; 包。
 *
 * <p>这个方向就是 Laravel「单条 {@code ->tier()} 胜过 group、内层 group 胜过外层 group」的等价物。
 * 方向一旦反了，就会出现「在 controller 上标了 admin，结果被包级标注盖掉」这种极难定位的分歧，
 * 所以单独钉一层，不混进扫描器测试里。
 */
class ForgeTiersTest {

    /** 三层都没标注：不表态。 */
    static class UntouchedController {

        String plain() {
            return "";
        }
    }

    @Test
    @DisplayName("包级标注被包内 controller 继承")
    void packageTierIsInherited() throws Exception {
        Method list = InheritedController.class.getDeclaredMethod("list");

        assertThat(ForgeTiers.resolve(list)).isEqualTo("client");
        assertThat(ForgeTiers.sourceOf(list)).isEqualTo("package io.github.routeforge.spring.annotation.tierpkg");
    }

    @Test
    @DisplayName("类级覆盖包级，方法级覆盖类级")
    void nearerDeclarationWins() throws Exception {
        Method byClass = OverridingController.class.getDeclaredMethod("byClass");
        Method byMethod = OverridingController.class.getDeclaredMethod("byMethod");

        assertThat(ForgeTiers.resolve(byClass)).isEqualTo("manage");
        assertThat(ForgeTiers.sourceOf(byClass)).isEqualTo("class @OverridingController");
        assertThat(ForgeTiers.resolve(byMethod)).isEqualTo("admin");
        assertThat(ForgeTiers.sourceOf(byMethod)).isEqualTo("method @OverridingController#byMethod");
    }

    @Test
    @DisplayName("三层都没有标注时返回 null：交给 classifier / match / unassigned，而不是猜一个")
    void nothingDeclaredMeansNoOpinion() throws Exception {
        Method plain = UntouchedController.class.getDeclaredMethod("plain");

        assertThat(ForgeTiers.resolve(plain)).isNull();
        assertThat(ForgeTiers.sourceOf(plain)).isEqualTo("none");
    }
}
