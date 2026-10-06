<?php

declare(strict_types=1);

/**
 * 参照器：仓库层产物（层级端点 / 摘要 / 全量数据 / 严格模式拦截 / 缓存交互）。
 *
 * 生成：php fixtures/php/oracle-repository.php > fixtures/php/expected/repository.json
 *
 * 这里直调 RouteRepository——PHP 侧控制器本来就只是「调仓库 + 序列化」，所以拿到的就是端点响应的
 * 同一 producer 真值；序列化细节（键序、null 省略、空对象形态）留给 P6 用真实 Laravel HTTP 响应再对一层。
 *
 * 每个用例显式给出 ops 调用序列：缓存调用日志与 ops 顺序强相关，隐式序列会让 Java 侧无从复现。
 */

require __DIR__ . '/bootstrap.php';

use RouteForge\Common\Cache\RouteCache;
use RouteForge\Common\Contract\CacheInterface;
use RouteForge\Common\Contract\ForgeExceptionContract;
use RouteForge\Common\Contract\RouteNormalizerInterface;
use RouteForge\Common\Dto\RouteInfo;
use RouteForge\Common\Filter\RouteNameFilter;
use RouteForge\Common\Repository\RouteRepository;
use RouteForge\Common\Tier\TierResolver;

/** 输入已是 RouteInfo，归一化即恒等：仓库层用例不需要任何框架对象。 */
final class IdentityNormalizer implements RouteNormalizerInterface
{
    public function normalize(mixed $route): RouteInfo
    {
        return $route;
    }
}

/** 记录调用序列的缓存桩：断言「谁在什么时候碰了缓存、以什么 TTL」。 */
final class RecordingCache implements CacheInterface
{
    /** @var list<string> */
    public array $calls = [];

    /** @var array<string,mixed> */
    public array $values = [];

    public function get(string $key): mixed
    {
        $this->calls[] = 'get:' . $key;

        return $this->values[$key] ?? null;
    }

    public function put(string $key, mixed $value, ?int $seconds): void
    {
        $this->calls[] = 'put:' . $key . ':' . ($seconds === null ? 'null' : $seconds);
        $this->values[$key] = $value;
    }

    public function forget(string $key): void
    {
        $this->calls[] = 'forget:' . $key;
        unset($this->values[$key]);
    }
}

$LEVELS_TWO = [
    'admin' => ['description' => '系统管理接口', 'load' => 'lazy', 'match' => ['prefix' => ['admin']]],
    'public' => ['description' => '公共接口', 'load' => 'eager', 'match' => ['prefix' => ['auth']]],
];

$cases = [
    // ---------------------------------------------------------------- 层级端点
    'level-routes-basic' => [
        'levels' => $LEVELS_TWO,
        'ttl' => 3600,
        'infos' => [
            info('admin.users.index', 'admin/users'),
            info('admin.users.show', 'admin/users/{user}', ['parameters' => ['user']]),
            info('auth.login', 'auth/login', ['methods' => ['POST']]),
            info('client.orders', 'client/orders'),
        ],
        'ops' => [['level', 'admin'], ['level', 'public'], ['level', 'client']],
    ],
    'empty-level-must-serialize-as-object' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]], 'empty' => ['match' => ['prefix' => ['nope']]]],
        'ttl' => 3600,
        'infos' => [info('admin.users', 'admin/users')],
        'ops' => [['level', 'empty']],
    ],
    'unassigned-level' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 3600,
        'infos' => [
            info('admin.users', 'admin/users'),
            info('debug.info', '_debug/info'),
            info('metrics', '_metrics', ['middleware' => ['auth']]),
        ],
        'ops' => [['level', 'unassigned']],
    ],
    'aliases-injected-into-target-level' => [
        'levels' => [
            'admin' => ['match' => ['prefix' => ['admin']]],
            'client' => ['match' => ['prefix' => ['client']]],
        ],
        'ttl' => 3600,
        'aliases' => ['client.orders.list' => 'client.orders.index'],
        'infos' => [
            info('admin.users', 'admin/users'),
            info('client.orders.index', 'client/orders'),
        ],
        'ops' => [['level', 'client'], ['level', 'admin']],
    ],
    'alias-follows-unassigned-target' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 3600,
        'aliases' => ['legacy.orphan' => 'free.route'],
        'infos' => [info('free.route', 'free/route')],
        'ops' => [['level', 'unassigned'], ['level', 'admin']],
    ],
    'own-endpoints-excluded' => [
        // 包自身层级端点：未命名、带 manage 中间件、URI 命中 endpoint_prefix —— 三者叠加必须零泄漏
        'levels' => ['manage' => ['match' => ['middleware' => ['manage']]]],
        'ttl' => 3600,
        'uriPrefixes' => ['/_forge/routes'],
        'infos' => [
            info(null, '_forge/routes/manage', ['middleware' => ['manage']]),
            info('forge.routes.manage', '_forge/routes/manage/named', ['middleware' => ['manage']]),
            info('manage.reports', 'manage/reports', ['middleware' => ['manage']]),
        ],
        'ops' => [['level', 'manage'], ['summary']],
    ],
    'unknown-level-throws' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 3600,
        'infos' => [info('admin.users', 'admin/users')],
        'ops' => [['level', 'ghost']],
    ],
    // -------------------------------------------------------------------- 摘要
    'summary-defaults' => [
        'levels' => ['admin' => ['description' => '系统管理接口', 'match' => ['prefix' => ['admin']]],
                     'public' => ['load' => 'eager', 'match' => ['prefix' => ['auth']]]],
        'ttl' => 3600,
        'infos' => [info('admin.users', 'admin/users'), info('other', 'x/y')],
        'ops' => [['summary']],
    ],
    'summary-custom-prefix-and-negative-ttl' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => -5,
        'runtime' => ['endpoint_prefix' => 'forge/routes/'],
        'infos' => [info('admin.users', 'admin/users')],
        'ops' => [['summary'], ['level', 'admin']],
    ],
    'summary-empty-url-prefix-becomes-null' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 0,
        'runtime' => ['url_prefix' => ''],
        'infos' => [info('admin.users', 'admin/users')],
        'ops' => [['summary']],
    ],
    'summary-url-prefix-passed-through' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 3600,
        'runtime' => ['url_prefix' => 'https://api.example.com/v1'],
        'infos' => [info('admin.users', 'admin/users')],
        'ops' => [['summary']],
    ],
    'summary-scheme-version-override' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 60,
        'runtime' => ['scheme_version' => 2],
        'infos' => [info('admin.users', 'admin/users')],
        'ops' => [['summary']],
    ],
    'summary-counts-include-aliases-once' => [
        'levels' => ['client' => ['match' => ['prefix' => ['client']]]],
        'ttl' => 3600,
        'aliases' => ['a.one' => 'client.x', 'a.two' => 'client.x'],
        'infos' => [info('client.x', 'client/x'), info('client.y', 'client/y')],
        'ops' => [['summary']],
    ],
    'summary-strict-mode-reported-first' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 3600,
        'strict' => true,
        'infos' => [info('admin.users', 'admin/users'), info('loose', 'loose/route')],
        'ops' => [['summary']],
    ],
    // -------------------------------------------------------- 全量数据（管理器）
    'manager-data-shape' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 3600,
        'aliases' => ['legacy.users' => 'admin.users'],
        'infos' => [
            info('admin.users', 'admin/users', ['middleware' => ['auth']]),
            info('admin.posts', 'admin/posts/{page}', ['methods' => ['GET'], 'parameters' => ['page']]),
            info('free.route', 'free/route', ['methods' => ['PUT']]),
            info(null, 'unnamed/route'),
        ],
        'ops' => [['manager']],
    ],
    'manager-data-alias-spreads-across-duplicate-levels' => [
        // 同一目标名被多次注册、命中不同层级（改名过渡期手误）：别名逐层级出现，计数只加一次
        'levels' => [
            'admin' => ['match' => ['prefix' => ['admin']]],
            'manage' => ['match' => ['prefix' => ['manage']]],
        ],
        'ttl' => 3600,
        'aliases' => ['legacy.thing' => 'dup.thing'],
        'infos' => [
            info('dup.thing', 'admin/dup', ['methods' => ['GET']]),
            info('dup.thing', 'manage/dup', ['methods' => ['GET']]),
        ],
        'ops' => [['manager'], ['summary']],
    ],
    // ---------------------------------------------------------------- 严格模式
    'strict-mode-aggregates-violations' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 3600,
        'strict' => true,
        'infos' => [
            info('admin.users', 'admin/users'),
            info('loose.route', 'loose/route'),
            info(null, 'admin/unnamed'),
        ],
        'ops' => [['level', 'admin'], ['summary']],
    ],
    'strict-mode-clean-passes' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 3600,
        'strict' => true,
        'infos' => [info('admin.users', 'admin/users')],
        'ops' => [['level', 'admin'], ['summary']],
    ],
    // ---------------------------------------------------------------- 缓存交互
    'cache-hit-on-second-call' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 3600,
        'infos' => [info('admin.users', 'admin/users')],
        'ops' => [['level', 'admin'], ['level', 'admin'], ['summary'], ['summary']],
    ],
    'cache-disabled-by-null-ttl' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => null,
        'infos' => [info('admin.users', 'admin/users')],
        'ops' => [['level', 'admin'], ['level', 'admin']],
    ],
    'cache-permanent-on-zero-ttl' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'ttl' => 0,
        'infos' => [info('admin.users', 'admin/users')],
        'ops' => [['level', 'admin'], ['summary']],
    ],
];

function runRepositoryCase(string $caseName, array $case): array
{
    $store = new RecordingCache();
    $cache = new RouteCache($store, false, $case['ttl'] ?? 3600);
    $runtime = array_merge([
        'endpoint_prefix' => '/_forge/routes',
        'url_prefix' => null,
        'strict_mode' => (bool) ($case['strict'] ?? false),
        'cache_ttl' => $case['ttl'] ?? 3600,
    ], $case['runtime'] ?? []);

    $repository = new RouteRepository(
        $case['infos'],
        new IdentityNormalizer(),
        new TierResolver($case['levels'], null, (bool) ($case['strict'] ?? false), new CollectingLogger()),
        $cache,
        $case['levels'],
        $case['aliases'] ?? [],
        $runtime,
        new RouteNameFilter(RouteNameFilter::FORGE_PREFIXES, $case['uriPrefixes'] ?? []),
    );

    $results = [];
    foreach ($case['ops'] as $op) {
        [$kind, $argument] = [$op[0], $op[1] ?? null];
        $label = $kind === 'level' ? "level:$argument" : $kind;
        try {
            $value = match ($kind) {
                'level' => $repository->getRoutesByLevel($argument),
                'summary' => $repository->getSummary(),
                'manager' => $repository->getAllRoutesWithTiers(),
                'unassigned' => $repository->getUnassignedRoutes(),
            };
            // 冻结为 JSON 文本而不是 assoc 数组：json_decode(...,true) 会把空 stdClass 变成 []，
            // 而「空层级 / 空 parameter_defaults 必须是 {}」恰恰是这条契约最要命的细节。
            $results[$label] = ['ok' => true, 'json' => json_encode(
                $value,
                JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES,
            )];
        } catch (ForgeExceptionContract $e) {
            $results[$label] = [
                'ok' => false,
                'code' => $e->code(),
                'httpStatus' => $e->httpStatus(),
                'message' => $e->getMessage(),
            ];
        }
    }

    return [
        'input' => [
            'name' => $caseName,
            'levels' => $case['levels'],
            'ttl' => $case['ttl'] ?? 3600,
            'strict' => (bool) ($case['strict'] ?? false),
            'aliases' => (object) ($case['aliases'] ?? []),
            'uriPrefixes' => $case['uriPrefixes'] ?? [],
            'runtime' => $runtime,
            'ops' => $case['ops'],
            'infos' => array_map('infoAsArray', $case['infos']),
        ],
        'expected' => [
            'results' => $results,
            'cacheCalls' => $store->calls,
            'storedKeys' => array_keys($store->values),
        ],
    ];
}

dump([
    'provenance' => provenance('fixtures/php/oracle-repository.php'),
    'cases' => array_values(array_map(
        static fn (string $name, array $case) => runRepositoryCase($name, $case),
        array_keys($cases),
        $cases,
    )),
]);
