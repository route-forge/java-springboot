package io.github.routeforge.core.contract;

/**
 * Route Forge 后端错误的统一契约：错误码 + HTTP 状态码。
 *
 * <p>调用方推荐只按本接口捕获（{@code catch (ForgeException e)} 后读 {@code code()}），
 * 而不是 catch 具体子类——这样跨语言、跨版本新增异常类型时宿主代码无需改动。
 *
 * <p>错误码语义与 Laravel 适配包逐字一致，见 {@code .docs/SPEC.md} §6。
 */
public interface ForgeException {

    /** 错误码，形如 {@code RF_BE_001}。 */
    String code();

    /** 该错误对应的 HTTP 状态码（端点读路径用；CLI 侧只看退出码）。 */
    int httpStatus();
}
