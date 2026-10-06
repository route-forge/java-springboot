package io.github.routeforge.spring.annotation.tierpkg;

/** 自身不标层级：应继承包级 {@code @ForgeTier("client")}，等价 Laravel 的外层 group。 */
public class InheritedController {

    public String list() {
        return "";
    }
}
