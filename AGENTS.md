# AGENTS.md — route-forge-springboot 工作约定

## 项目定位

Route Forge 家族的 Spring Boot 后端适配包。前端 `@route-forge/core|vue|react`（≥ 3.1.0）**零改动**接入：
契约就是两个 JSON 端点（摘要 + 层级）与可选的 `window.__ROUTE_FORGE__` 内嵌摘要。

- 跨语言端点契约的权威描述：`route-forge/php-laravel` 的 `.docs/SPEC.md`。
- 本仓 `.docs/SPEC.md`：Java 侧落地口径 + Spring 概念映射 + Java 专属扩展。
- 参照实现：`G:\Web\php-laravel`（适配层）、`G:\Web\php-common`（框架无关核心）、`G:\Web\php-thinkphp`（第二适配，看适配点清单）。

## 模块边界

- `forge-core`：纯算法，**零 Spring / 零 Jackson 依赖**。层级解析、别名、严格模式扫描、d.ts 生成、缓存语义。
- `forge-spring-boot-starter`：注解、路由扫描、端点、Security 守卫、缓存实现、CLI、管理器。
  Spring 侧一律 `compileOnly`；`api(platform(bom))` 禁止（不得把 Spring 版本约束强加给宿主）。

破坏这条边界算方向性错误，动手前先请示。

## 构建与验证

标准命令（`GRADLE_USER_HOME` 指向**本项目自己的** `.gradle`，与用户目录及其他工程完全隔离）：

```bash
export GRADLE_USER_HOME="$PWD/.gradle"        # 或每条命令前置 GRADLE_USER_HOME=...
./gradlew.bat build                           # 全量：编译 + 测试 + jar
./gradlew.bat :forge-core:test                # 单模块测试（迭代期用，最终仍需全量）
./gradlew.bat clean build --offline           # 缓存自足性检查（新增依赖后必须能离线跑通）
```

- Gradle：wrapper 9.7.0-all，发行包与依赖缓存都在 `<项目根>/.gradle`（已 gitignore）。首次拉取后 `--offline` 可全量重跑。
- 镜像加速：`<项目根>/.gradle/init.d/cn-mirrors.gradle.kts`（依赖走腾讯、插件走阿里云、官方源兜底）。
  它在 gitignore 内，**不进仓库**；`settings.gradle.kts` 只声明 mavenCentral，保证 CI 与他人 clone 可复现。
- 因此本项目的构建只在指定 `GRADLE_USER_HOME` 时才吃到镜像与本地缓存；不指定则回落到用户目录的缓存，功能不受影响。
- JDK：基线 Java 17（`options.release` 锁定，不用 toolchain 自动下载；产物 class major=61，由 `BuildConventionTest`
  读自身 class 头钉死）。支持边界：tested on Boot 4.x，Boot 3.5+ 物理兼容但 untested（`compileBoot35SentinelJava` 只证明
  可编译，不许据此写成「支持 Boot 3」），Boot 2 及以下明确排除。口径与四件改动见 `.docs/SPEC.md` §8.1 与 PROGRESS 已落地记录。
- 编码：全仓 UTF-8 强制（`-Dfile.encoding` / `stdout.encoding`）。断言与 fixture 含中文，Windows GBK 会造成假失败。
- `-parameters` 全仓强制：`@ConfigurationProperties` 构造绑定依赖它，丢了要到运行期才炸，故由 `BuildConventionTest` 把守。

## 行为一致性铁律

1. **契约以 Spring Boot 的习惯为准**，Laravel 是参照而非规范：字段集、语义、错误码与 Laravel 侧保持**功能等价**，
   但形态上遇到冲突时按 Spring 侧走（例：路由 `uri` 保留 Spring 原样的前导 `/`，不刻意改成 Laravel 的相对形态）。
   任何与 PHP 侧的分歧必须在本仓 `.docs/SPEC.md` 显式记为「Java 专属扩展」或「差异」，不许静默分叉。
2. 端点响应逐字段对齐：`levels`/`config` 必发、空层级必须是 `{}`、URI 模板必须剥离 `{name:regex}` 约束
   （前端替换不了带冒号的占位符）。
3. 包自身路由（层级/摘要/管理器端点）必须在**所有**元信息扫描中排除（按 handler 来源 + 按规范化 `endpoint_prefix`
   的段级 URI 前缀），否则 `strict_mode=true` 时包会把自己报成宿主的配置错误、端点必 500。
4. 前端校验语义不变：前端始终抛错、拒绝静默忽略；`strict_mode` 是后端语义，前端无对应开关。

## Git 约定

- 提交信息：`type(scope): 中文描述`，scope 用模块名（`core` / `starter` / `build` / `docs` / `example`）。
- 提交前必跑全量 `./gradlew.bat build`；提交后不 push，等指示。
- 临时产物一律落 `F:/tmp`，不进仓库。
