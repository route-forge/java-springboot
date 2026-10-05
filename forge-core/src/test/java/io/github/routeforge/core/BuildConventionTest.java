package io.github.routeforge.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 构建约定守卫：把「看不见但一改就崩」的编译前提固化成测试。
 *
 * <p>这三条都是后续阶段的隐性依赖——参数名丢失会让 Spring 侧的 {@code @PathVariable} 与配置构造绑定全线失效，
 * 编码漂移会让中文错误码文案与跨语言 golden fixture 比对出现假失败。
 */
class BuildConventionTest {

    /** 形参名探针：仅用于检测 class 文件里是否保留了方法参数名。 */
    static String probe(String tierName) {
        return tierName;
    }

    @Test
    @DisplayName("编译基线为 Java 21")
    void targetsJava21() {
        assertThat(Runtime.version().feature()).isEqualTo(21);
    }

    @Test
    @DisplayName("-parameters 已开启：路由参数名可从 class 反射得到")
    void compilerKeepsParameterNames() throws NoSuchMethodException {
        Method method = BuildConventionTest.class.getDeclaredMethod("probe", String.class);

        assertThat(method.getParameters()[0].isNamePresent())
                .as("缺少 -parameters 时 @PathVariable 与配置构造绑定会在运行期才炸")
                .isTrue();
        assertThat(method.getParameters()[0].getName()).isEqualTo("tierName");
    }

    @Test
    @DisplayName("默认字符集为 UTF-8：中文错误码文案不依赖平台编码")
    void defaultCharsetIsUtf8() {
        assertThat(Charset.defaultCharset()).isEqualTo(UTF_8);
    }
}
