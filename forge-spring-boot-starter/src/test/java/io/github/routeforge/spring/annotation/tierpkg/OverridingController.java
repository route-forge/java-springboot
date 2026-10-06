package io.github.routeforge.spring.annotation.tierpkg;

import io.github.routeforge.spring.annotation.ForgeTier;

/** 类级标注应覆盖包级；方法级再覆盖类级（内层覆盖外层）。 */
@ForgeTier("manage")
public class OverridingController {

    public String byClass() {
        return "";
    }

    @ForgeTier("admin")
    String byMethod() {
        return "";
    }
}
