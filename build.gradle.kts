plugins {
    base
}

// 全仓共性约定集中在此，子模块只声明「依赖差异」，避免两处漂移。
subprojects {
    group = "io.github.route-forge"
    version = "0.1.0"

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            // 不用 toolchain 自动下载：编译目标由 options.release 锁定，跑 Gradle 的 JDK 由本机决定
            withSourcesJar()
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        // Java 21 为库的公开基线（Spring Boot 4 自身要求 17+）
        options.release.set(21)
        options.encoding = "UTF-8"
        // @ConfigurationProperties 构造绑定、@PathVariable 名字推断都依赖参数名元数据，默认是关的
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Windows 控制台默认 GBK：中文错误码文案与 golden fixture 比对必须锁 UTF-8
        systemProperty("file.encoding", "UTF-8")
        systemProperty("stdout.encoding", "UTF-8")
        systemProperty("stderr.encoding", "UTF-8")
        // PHP 参照器产物的位置（跨语言对等测试读取）。用绝对路径注入，避免各模块工作目录差异。
        systemProperty("forge.fixtures.dir", rootProject.file("fixtures/php/expected").absolutePath)
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
