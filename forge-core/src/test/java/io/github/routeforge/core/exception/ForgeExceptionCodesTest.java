package io.github.routeforge.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.routeforge.core.contract.ForgeException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 错误码表守卫：与 Laravel 适配包 SPEC §6 的错误码 / HTTP 状态逐条对齐。
 *
 * <p>这条表是跨语言契约的一部分——宿主前端与运维脚本按 code 分支处理，改码必须同步改 SPEC。
 */
class ForgeExceptionCodesTest {

    private static List<ForgeException> allErrors() {
        return List.of(
                new RouteTierNotAssignedException("m"),
                new UnknownLevelException("m"),
                new CacheDriverException("m"),
                new ClassifierException("m", new IllegalStateException("boom")),
                new RouteMissingNameException("m"),
                new UnknownClassifierTierException("m"),
                new AliasTargetException("m"),
                new ConflictingRouteDeclarationException("m"));
    }

    @ParameterizedTest
    @CsvSource({
        "RF_BE_001, 500",
        "RF_BE_002, 404",
        "RF_BE_003, 500",
        "RF_BE_004, 500",
        "RF_BE_005, 500",
        "RF_BE_006, 500",
        "RF_BE_008, 500",
    })
    @DisplayName("每个错误码对应的 HTTP 状态与 SPEC 一致")
    void codeToHttpStatus(String code, int status) {
        assertThat(allErrors())
                .anySatisfy(error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.httpStatus()).isEqualTo(status);
                });
    }

    @Test
    @DisplayName("RF_BE_010 是 Java 专属新增：命名三通道给出冲突事实")
    void javaSpecificConflictCode() {
        ForgeException error = new ConflictingRouteDeclarationException("m");

        assertThat(error.code()).isEqualTo("RF_BE_010");
        assertThat(error.httpStatus()).isEqualTo(500);
    }

    @Test
    @DisplayName("007 码位在 Spring 侧无对应物，不占用也不复用")
    void code007IsReservedForLaravelRegistrarSemantics() {
        assertThat(allErrors()).noneMatch(error -> error.code().equals("RF_BE_007"));
    }

    @Test
    @DisplayName("全部错误码唯一，且只以 RF_BE_ 开头")
    void codesAreUniqueAndWellFormed() {
        List<String> codes = allErrors().stream().map(ForgeException::code).toList();

        assertThat(codes).doesNotHaveDuplicates().allMatch(code -> code.matches("RF_BE_\\d{3}"));
    }

    @Test
    @DisplayName("classifier 原始异常保留在 cause 上，不被包装吞掉")
    void classifierKeepsCause() {
        IllegalStateException cause = new IllegalStateException("boom");

        assertThat(new ClassifierException("m", cause)).hasCause(cause);
    }
}
