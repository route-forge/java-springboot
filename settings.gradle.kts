// Route Forge — 模块与仓库源声明
// 注意：镜像加速属个人环境配置，一律放在 GRADLE_USER_HOME/init.d（不入库），
// 保证 CI 与外部贡献者按官方仓库源解析。
rootProject.name = "route-forge-springboot"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // 统一在 settings 声明仓库源；个人镜像经本地 .gradle/init.d 前置注入，不改本文件
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
}

include("forge-core")
include("forge-spring-boot-starter")
// 示例后端：演示接入 + 后续 Vue/React 前端联调与 Laravel golden 端到端对等的宿主
include("example")
