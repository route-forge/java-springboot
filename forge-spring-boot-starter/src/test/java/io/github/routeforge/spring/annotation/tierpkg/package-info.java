/**
 * 层级归属示例包：用于验证 {@code package-info.java} 上的 {@link io.github.routeforge.spring.annotation.ForgeTier}
 * 等价 Laravel 的外层 {@code Route::group} —— 包内所有 controller 未自行标注时继承该层级。
 */
@ForgeTier("client")
package io.github.routeforge.spring.annotation.tierpkg;

import io.github.routeforge.spring.annotation.ForgeTier;
