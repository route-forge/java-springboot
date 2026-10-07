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

    // 可选能力：classpath 有则启用、无则降级并记告警（Redis 分布式缓存）。
    // Security **不在此列**：本包不注册、不改写宿主的 SecurityFilterChain（SPEC §4.4 铁律 3），
    // 守卫注解只按类型全名反射识别，因此 main 侧零 Security 依赖；测试才引真注解做断言。
    compileOnly(libs.spring.data.redis)
    // 管理器写回 forge-levels.yml 用：YAML 产物的正确性不能靠手写字符串赌，
    // 且 Boot 应用本身已带同一版本（由 BOM 裁决），实际不增加传递负担。
    implementation(libs.snakeyaml)

    // 只为 IDE 生成 forge.* 补全元数据，不进入运行时依赖
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    // Boot 4 拆分：@AutoConfigureMockMvc 等 Web MVC 测试自动配置在独立模块里
    testImplementation(libs.spring.boot.webmvc.test)
    testImplementation(libs.spring.webmvc)
    testImplementation(libs.spring.web)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.jakarta.servlet.api)
    testImplementation(libs.jackson.annotations)
    // 真实宿主由 spring-boot-starter-webmvc 带 Jackson；测试运行时得自己补，否则
    // 端点没有 JSON 转换器，MockMvc 直接 406（Not Acceptable）。
    testImplementation(libs.jackson.databind)
    testImplementation(libs.snakeyaml)
    // 守卫标签派生：对着真实注解跑（库本体零 Security 依赖，见上方 compileOnly 注释）
    testImplementation(libs.spring.security.core)
    testImplementation(libs.jakarta.annotation.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}
