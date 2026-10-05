plugins {
    `java-library`
}

description = "Route Forge 框架无关核心：层级解析、别名、严格模式扫描、类型生成、缓存语义"

dependencies {
    // 核心层零运行时依赖：不引 Spring、不引 Jackson。
    // 对齐 route-forge/common 的定位——纯算法，任何后端宿主都能复用同一套语义。
    // BOM 只服务于测试依赖的版本裁决，不泄漏给消费者（故不用 api(platform(..))）。
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
