package io.github.routeforge.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
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
    @DisplayName("编译基线为 Java 17：class 文件 major version 恒为 61")
    void targetsJava17() throws IOException {
        // 读自身 class 的头 8 字节（CAFEBABE + minor(2) + major(2)）——钉的是编译产物，不是构建机 JVM。
        // 旧断言用 Runtime.version().feature()==21 是反直觉的：换台 JDK 25 的机器就假红，而把
        // options.release 降到 17 之后它照样绿（构建机仍是 21），既不证明基线真降了、也拦不住有人改回 21。
        byte[] header = new byte[8];
        try (InputStream in = BuildConventionTest.class.getResourceAsStream("BuildConventionTest.class")) {
            assertThat(in).as("读不到自身 class 文件，无法验证编译基线").isNotNull();
            assertThat(in.readNBytes(header, 0, header.length))
                    .as("class 头不足 8 字节")
                    .isEqualTo(header.length);
        }
        assertThat(header[0]).isEqualTo((byte) 0xCA);
        assertThat(header[1]).isEqualTo((byte) 0xFE);
        assertThat(header[2]).isEqualTo((byte) 0xBA);
        assertThat(header[3]).isEqualTo((byte) 0xBE);
        int majorVersion = ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
        assertThat(majorVersion)
                .as("options.release 漂移：产物 major 应为 61（Java 17）")
                .isEqualTo(61);
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
