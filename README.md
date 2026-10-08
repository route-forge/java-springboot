# Route Forge for Spring Boot

> **Status: pre-release.** The artifacts are not published to Maven Central yet, and the
> end-to-end frontend integration pass is still ahead. Everything described below is
> implemented and gated by tests, but there is no installable coordinate to copy — see
> [Install](#install-pre-release).

**语言 / Language:** [English](./README.md) · [简体中文](./README_zh.md)

Expose your Spring Boot route table to a Vue / React SPA **by name, per tier** — so the
frontend builds URLs and calls APIs from `tier + route name` instead of hardcoded strings,
and without shipping the whole route table to the browser.

Part of the Route Forge family. The wire contract is shared verbatim with
[php-laravel](https://github.com/route-forge/php-laravel), so the existing frontend packages
work against a Spring Boot backend with **zero changes**.

## What problem it solves

A SPA that calls a Spring Boot backend usually hardcodes paths (`/admin/users/42`) or keeps a
second hand-written list of URLs in TypeScript. Both drift: the backend renames a path, the
frontend finds out at runtime.

Route Forge publishes the route table as metadata, split into tiers:

- `GET /_forge/routes` returns a small summary — tier names, descriptions, route counts, and
  where to fetch each tier.
- `GET /_forge/routes/{level}` returns the routes of **one** tier only.

A browser that only ever touches the `public` tier never downloads the `admin` route table, let
alone the `internal` one. TypeScript declarations are generated from the same source of truth.

## Why this adapter

- **No annotation tax.** `@ForgeRoute` is optional. A route with no forge annotation at all still
  gets scanned, URI-normalized and tier-assigned — you only name what the frontend actually calls.
- **Guard annotations are read, never re-declared.** Tier labels can be derived from the
  `@PreAuthorize` / `@Secured` / `@RolesAllowed` you already wrote. The matcher is by annotation
  **type name**, so the library carries **zero Spring Security dependency**, registers **no**
  `SecurityFilterChain`, and never reorders or alters your security configuration.
- **`forge-core` has no Spring and no Jackson.** The parsing, tier resolution, alias, strict-mode
  and d.ts logic is plain Java; the adapter layer is the only thing that touches Spring types.
- **Fail loudly, in the right place.** A mistyped tier, a duplicate route name, an alias pointing
  at nothing, or a `defaults` entry that is not a URI variable all raise a structured error
  (`RF_BE_*`) rather than being silently dropped.
- **Cross-language parity is tested, not claimed.** The Java assertions run against real
  php-common output frozen in `fixtures/php/expected/` — 294 tests, of which the core layer is
  compared field by field against the PHP implementation.

## Feature overview

| | |
|---|---|
| Route metadata | Summary + per-tier endpoints, contract-identical to the Laravel adapter |
| Tier assignment | Five-level priority: explicit → class/package annotation → `RouteClassifier` bean → config `match` rules → `unassigned` |
| Route naming | `@ForgeRoute` / `@Forge` / native `@RequestMapping(name=...)` / pluggable `RouteNamingStrategy` |
| URI normalization | Spring regex constraints stripped (`{id:\d+}` → `{id}`), optional segments emitted as `{page?}` |
| Aliases | Stable public names pointing at real routes, declared on the route or in config |
| Strict mode | All tier problems aggregated into one report; violations only served when `debug=true` |
| Caching | Pluggable `CacheStore` SPI with TTL, per-tier invalidation and `debug` bypass; in-memory driver |
| d.ts generation | `--forge:types` emits TypeScript declarations from the same resolver the endpoints use |
| CLI | `--forge:list` / `--forge:types` / `--forge:clear` |
| Framework route exclusion | Built-in `{/error, /actuator}` URI exclusions, host config can only add |
| Manager UI / Redis cache / Thymeleaf embed | **Not implemented yet** — see [What is not here yet](#what-is-not-here-yet) |

## Requirements

| | |
|---|---|
| Java | 17 or later |
| Spring Boot | **tested on 4.x** (developed and tested on 4.1); Boot 3.5+ compiles but is untested and not committed; Boot 2 and below are explicitly excluded |
| Web stack | Servlet (`spring-boot-starter-webmvc`) with a JSON message converter — **WebFlux is not supported**, the scanner reads `RequestMappingHandlerMapping` |
| Frontend | `@route-forge/core`, `@route-forge/vue`, `@route-forge/react` ≥ 3.1.0 |

## Install (pre-release)

No Maven Central coordinate yet. If you want to try it from source today, a Gradle composite
build resolves the project by its `io.github.route-forge` coordinates:

```kotlin
// settings.gradle.kts
includeBuild("/path/to/route-forge-springboot")

// build.gradle.kts
dependencies {
    implementation("io.github.route-forge:forge-spring-boot-starter")
}
```

This path has not been validated end to end yet. Formal coordinates, the POM and the signing
setup land with the publishing stage.

No `@Import` and no `@Enable...` annotation are needed — the auto-configuration registers itself
and activates only for servlet web applications.

## Quickstart

### 1. Define tiers

```yaml
forge:
  strict-mode: true
  cache-ttl: 3600
  levels:
    public:
      description: Anonymous pages
      load: eager
    admin:
      description: Admin console
      load: lazy
      match:
        prefix: /admin
        middleware: [admin]
        middleware-match: all
```

### 2. Name the routes the frontend calls

```java
@RestController
@ForgeTier("admin")                       // every route in this class inherits the tier
class AdminUserController {

    @ForgeRoute(name = "admin.users.show", method = RequestMethod.GET,
            path = "/admin/users/{user:\\d+}")
    UserDto show(@PathVariable long user) { ... }

    @GetMapping("/admin/users")           // untouched: still scanned, simply unnamed
    List<UserDto> index() { ... }

    @Forge(name = "admin.users.page", tier = "admin")
    @GetMapping("/admin/users/page/{page}")
    PageDto page(@PathVariable int page) { ... }   // native mapping annotation + side annotation
}
```

What reaches the frontend is normalized and safe to interpolate:

```json
{
  "admin.users.show": {
    "uri": "/admin/users/{user}",
    "methods": ["GET", "HEAD"],
    "parameters": ["user"],
    "parameter_defaults": {}
  }
}
```

### 3. Optional: derive tier labels from your existing guards

```java
@PreAuthorize("hasRole('ADMIN')")          // → label "ADMIN"
@Secured("ROLE_SUPER")                     // → label "ROLE_SUPER"
@PreAuthorize("isAuthenticated()")         // → label "__authenticated"
@PreAuthorize("hasRole('A') and isAuthenticated()")  // → two labels, split on top-level `and`
@PreAuthorize("hasAnyRole('A','B')")       // → "expression:hasAnyRole('A','B')" — kept verbatim
```

Nothing is inferred about semantics the library cannot read losslessly: anything that would
widen or narrow the requirement falls back to the raw expression prefixed with `expression:`.
Role prefixes are **never** added or stripped, because `hasRole('ADMIN')` and
`hasRole('ROLE_ADMIN')` are two different requirements.

These labels are metadata for tier classification only. They are **not** a security boundary, and
this library never checks whether method security is actually enabled.

### 4. Consume it from the frontend

The frontend packages are unchanged — point them at the summary endpoint and they auto-discover
the tiers. See [route-forge](https://github.com/route-forge/route-forge).

## CLI

Spring Boot has no artisan, so the commands are JVM arguments handled by an `ApplicationRunner`.
This is a Java-side extension, not a port of the Laravel command names. Absent flags make the
runner a complete no-op, so normal application startup and plain `@SpringBootTest` contexts are
unaffected.

```bash
java -jar app.jar --forge:list                      # tier assignment table
java -jar app.jar --forge:list --level=admin --json
java -jar app.jar --forge:types --out=src/api/routes.d.ts
java -jar app.jar --forge:clear --level=admin
```

`list` and `types` deliberately diverge when strict mode reports violations: `list` still prints
the whole table and exits 1 (you need the full picture while debugging), while `types` refuses to
emit **any** artifact — a d.ts file gets committed, and a plausible-looking wrong contract is
worse than no file. stdout carries artifacts only, all feedback goes to stderr.

## Configuration (`forge.*`)

| Key | Type | Default | Notes |
|---|---|---|---|
| `levels.<name>.description` | `string` | `""` | Human-readable tier description |
| `levels.<name>.match.prefix` | `string[]` | `[]` | URI prefix, matched per segment |
| `levels.<name>.match.middleware` | `string[]` | `[]` | Label set used for classification |
| `levels.<name>.match.middleware-match` | `any`\|`all`\|DNF | `any` | DNF is a `List<List<Integer>>` |
| `levels.<name>.load` | `eager`\|`lazy` | `lazy` | Preload hint handed to the frontend |
| `endpoint-prefix` | `string` | `/_forge/routes` | Metadata endpoint prefix |
| `url-prefix` | `string?` | `null` | URL prefix delivered to the frontend |
| `cache-ttl` | `int?` | `3600` | `null` = no cache, `0` = forever, negative normalized to `null` |
| `cache-driver` | `string` | `memory` | Anything else fails at startup rather than silently falling back |
| `strict-mode` | `bool` | `false` | Aggregate tier problems into one `RF_BE_009` report |
| `scheme-version` | `int` | `1` | Summary format version |
| `aliases` | `map` | `{}` | Alias → real route name |
| `exclude-uri-prefixes` | `string[]` | `[]` | **Java-only extension**: *appended* to the built-in `{/error, /actuator}` set |

Array-valued keys accept a single value (`forge.levels.admin.match.prefix=api/admin` is a
one-element list), matching the PHP side's normalization.

A custom classifier is a `RouteClassifier` bean, not a config entry.

Development mode is Spring's own top-level `debug` flag — **not** `forge.debug`. It bypasses the
metadata cache so a route or config edit takes effect immediately, and unlocks the structured
`violations` list on error responses.

## Endpoints

```
GET {endpoint-prefix}           → { schemeVersion, levels{...route}, config{...} }
GET {endpoint-prefix}/{level}   → { level, routes{...} }
```

`levels` and `config` are always present, an empty tier serializes to `{}` (never `[]`), and URI
templates never carry regex constraints — the frontend's placeholder pattern cannot substitute
`{name:regex}`. Errors return `{"error": {"code": "RF_BE_...", "message": "..."}}`; the
structured `violations` list is included only when `debug=true`, because it enumerates route
names and paths.

Protecting `/_forge/**` is your job: declare it in your own `authorizeHttpRequests` rules.

## Known limits

- `RouterFunction` (functional endpoints) are not scanned — their path predicates do not expose a
  template string.
- WebFlux is not supported.
- Tier names bind at startup; flipping `forge.strict-mode` at runtime inside one JVM is not
  possible.

## What is not here yet

The manager web UI (with its IP allow-list) and YAML write-back, the Redis cache driver, the
Thymeleaf summary embed, Maven Central publishing and the Vue/React end-to-end integration pass.
The current stage list lives in [`.docs/PROGRESS.md`](.docs/PROGRESS.md).

## Documentation

- [`.docs/SPEC.md`](.docs/SPEC.md) — Java-side contract, Spring-to-forge concept mapping, and every
  place where this adapter deliberately diverges from Laravel.
- The authoritative cross-language contract description lives in
  [php-laravel's `.docs/SPEC.md`](https://github.com/route-forge/php-laravel/tree/main/.docs).

## License

MIT — see [LICENSE](./LICENSE).
