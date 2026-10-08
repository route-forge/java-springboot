plugins {
    java
    // 唯一允许 apply Boot 插件的模块：产出可执行 bootJar，供 bootRun / 前端联调。库模块严禁 apply（见 libs.versions.toml 注释）。
    alias(libs.plugins.spring.boot)
}

description = "Route Forge Spring Boot 示例后端：演示 @ForgeRoute 接入、层级归派与两个元信息端点（非发布产物）"

dependencies {
    // 版本一律交 Boot BOM 裁决（示例是宿主，可用 platform）
    implementation(platform(libs.spring.boot.bom))

    // 被测对象：本家族的 starter（其 api 传递 core）
    implementation(project(":forge-spring-boot-starter"))

    // 真实 web 宿主：带内嵌 Tomcat + Jackson + DispatcherServlet（starter 的端点靠 Jackson 序列化 Map）
    implementation(libs.spring.boot.starter.web)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    // Boot 4 拆分：@AutoConfigureMockMvc 在 spring-boot-webmvc-test
    testImplementation(libs.spring.boot.webmvc.test)
    // Boot 4 的 starter-test 不再传递 JUnit Platform launcher，测试运行期缺它会「Failed to load JUnit Platform」
    testRuntimeOnly(libs.junit.platform.launcher)
}

// 示例不当库发布，但根构建给所有 java 模块挂了 sourcesJar；这里关掉，避免产出无意义的 example-sources.jar
tasks.named<org.gradle.jvm.tasks.Jar>("sourcesJar") {
    enabled = false
}
