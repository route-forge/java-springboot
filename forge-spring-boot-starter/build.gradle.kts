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
    // 端点契约测试用 MockMvc：`result.getResponse()` 返回 MockHttpServletResponse，其 `getStatus()`
    // 需编译器能解析到父接口 jakarta.servlet.http.HttpServletResponse——测试源码里从不写 `jakarta.servlet`
    // 字样，但类路径缺它会「无法访问 HttpServletResponse / 找不到类文件」。Boot 4 拆分后 starter-test
    // 不再传递带 servlet-api，故显式补。(实测推翻了「test 也零 servlet 引用即可删」的判断：main 确实可删，test 不可。)
    testImplementation(libs.jakarta.servlet.api)
    testImplementation(libs.spring.webmvc)
    testImplementation(libs.spring.web)
    testImplementation(libs.spring.boot.autoconfigure)
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

// ---------------------------------------------------------------------------
// Boot 3.5 编译哨兵（capability floor 探针，不是支持承诺）
//
// 对外口径是「tested on Boot 4.x」+「Boot 3.5+ 物理兼容但不承诺」。「物理兼容」若不可证伪
// 就是空话，所以这里把同一份 src/main 再对着 Boot 3.5.16（实测对应 Framework 6.2.19）编一遍：编得过
// 只证明「能编译」，运行期行为（尤其 YamlShape 依赖的 Binder 索引 Map 形态 {0=/admin}）在
// 3.5 上未做验证——绝不允许据此写成「支持 Boot 3」。
//
// 刻意不接入 build/check、不跑测试：主门禁因此只需 Boot 4.x 的本地缓存，--offline 仍自足；
// 要探边界时手动跑 :forge-spring-boot-starter:compileBoot35SentinelJava（首次联网拉 3.5）。
// ---------------------------------------------------------------------------
val boot35Sentinel: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    description = "把 src/main 再对着 Boot 3.5（FW 6.2）编一遍的编译类路径"
}

dependencies {
    // 只放 src/main 实际 import 到的坐标；版本一律交给 3.5 BOM 裁决，与主线的 4.1.1 BOM 互不相干
    boot35Sentinel(platform(libs.spring.boot35.bom))
    boot35Sentinel(libs.spring.web)
    boot35Sentinel(libs.spring.webmvc)
    boot35Sentinel(libs.spring.context)
    boot35Sentinel(libs.spring.boot.autoconfigure)
    boot35Sentinel(libs.slf4j.api)
    boot35Sentinel(project(":forge-core"))
}

val compileBoot35SentinelJava by tasks.registering(JavaCompile::class) {
    description = "Boot 3.5 编译哨兵：只编 src/main 验证可编译；不承诺运行期、不跑测试、不入 build"
    group = "verification"
    source = layout.projectDirectory.dir("src/main/java").asFileTree
    classpath = boot35Sentinel
    destinationDirectory = layout.buildDirectory.dir("boot35-sentinel-classes")
    // 锁 17：语言级别是库基线，独立于主线 options.release 的漂移也要能编过 FW 6.2
    options.release.set(17)
    options.encoding = "UTF-8"
}
