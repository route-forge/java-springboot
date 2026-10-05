plugins {
    `java-library`
}

description = "Route Forge 的 Spring Boot 适配层：注解 / 路由扫描 / 元信息端点 / 缓存 / CLI / 管理器"

dependencies {
    // BOM 只进 compileOnly/test 路径：绝不 api(platform(..))，
    // 否则本库会把 Spring 版本约束强加给宿主工程。
    compileOnly(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))

    api(project(":forge-core"))

    // Spring 一律 compileOnly：宿主提供 spring-boot-starter-webmvc，库不该往传递依赖里塞版本
    compileOnly(libs.spring.web)
    compileOnly(libs.spring.webmvc)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.jakarta.servlet.api)
    compileOnly(libs.jackson.annotations)
    compileOnly(libs.slf4j.api)

    // 可选能力：classpath 有则启用、无则降级并记告警（见 SPEC 端点保护 / 分布式缓存）
    compileOnly(libs.spring.security.config)
    compileOnly(libs.spring.security.web)
    compileOnly(libs.spring.data.redis)
    // 管理器写回 forge-levels.yml 用：YAML 产物的正确性不能靠手写字符串赌，
    // 且 Boot 应用本身已带同一版本（由 BOM 裁决），实际不增加传递负担。
    implementation(libs.snakeyaml)

    // 只为 IDE 生成 forge.* 补全元数据，不进入运行时依赖
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.webmvc)
    testImplementation(libs.spring.web)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.jakarta.servlet.api)
    testImplementation(libs.jackson.annotations)
    testImplementation(libs.snakeyaml)
    testRuntimeOnly(libs.junit.platform.launcher)
}
