<?php

declare(strict_types=1);

/**
 * 参照器：别名解析 + 严格模式扫描。
 *
 * 生成：php fixtures/php/oracle-alias-strict.php > fixtures/php/expected/alias-strict.json
 */

require __DIR__ . '/bootstrap.php';

use RouteForge\Common\Alias\AliasResolver;
use RouteForge\Common\Contract\ForgeExceptionContract;
use RouteForge\Common\Filter\RouteNameFilter;
use RouteForge\Common\Support\StrictViolationScanner;
use RouteForge\Common\Tier\TierResolver;

// ------------------------------------------------------------------ 别名用例

/** @param list<RouteForge\Common\Dto\RouteInfo> $infos */
$aliasCases = [
    'macro-declaration-only' => [
        'infos' => [
            info('admin.members.index', 'admin/members', ['forgeAliases' => ['admin.users.index']]),
        ],
        'config' => [],
    ],
    'config-declaration-only' => [
        'infos' => [info('admin.members.index', 'admin/members')],
        'config' => ['admin.users.index' => 'admin.members.index'],
    ],
    'macro-and-config-same-target-is-silent' => [
        'infos' => [info('admin.members.index', 'admin/members', ['forgeAliases' => ['admin.users.index']])],
        'config' => ['admin.users.index' => 'admin.members.index'],
    ],
    'macro-wins-over-config-different-target' => [
        'infos' => [info('admin.members.index', 'admin/members', ['forgeAliases' => ['admin.users.index']])],
        'config' => ['admin.users.index' => 'admin.people.index'],
    ],
    'macro-declared-on-multiple-routes-first-wins' => [
        'infos' => [
            info('a.one', 'a/one', ['forgeAliases' => ['legacy.name']]),
            info('a.two', 'a/two', ['forgeAliases' => ['legacy.name']]),
            info('a.three', 'a/three', ['forgeAliases' => ['legacy.name', 'legacy.other']]),
        ],
        'config' => [],
    ],
    'macro-alias-collides-with-real-name' => [
        'infos' => [
            info('real.name', 'r/1'),
            info('other.name', 'o/1', ['forgeAliases' => ['real.name']]),
        ],
        'config' => [],
    ],
    'config-alias-collides-with-real-name' => [
        'infos' => [
            info('real.name', 'r/1'),
            info('other.name', 'o/1', ['forgeAliases' => ['real.name']]),
        ],
        'config' => ['real.name' => 'other.name'],
    ],
    'alias-declared-on-unnamed-route-is-ignored-with-warning' => [
        'infos' => [
            info('target.name', 't/1'),
            info(null, 'unnamed/route', ['forgeAliases' => ['legacy.name']]),
        ],
        'config' => [],
    ],
    'empty-alias-entry-skipped' => [
        'infos' => [info('target.name', 't/1', ['forgeAliases' => ['', 'legacy.name']])],
        'config' => [],
    ],
    'alias-chains-are-not-followed' => [
        // 别名的目标是另一个别名 → 目标不是真实名 → 悬空
        'infos' => [info('a.real', 'a/1', ['forgeAliases' => ['a.alias']])],
        'config' => ['a.other' => 'a.alias'],
    ],
    'alias-targeting-excluded-route-is-dangling' => [
        'infos' => [info('client.orders.index', 'client/orders')],
        'config' => ['old.orders' => 'forge.routes.admin'],
    ],
    'dangling-alias-throws' => [
        'infos' => [info('client.orders.index', 'client/orders')],
        'config' => ['old.orders' => 'client.orders.list'],
    ],
    'invalid-config-alias-shape' => [
        'infos' => [info('a.b', 'a/b')],
        'config' => ['legacy.name' => ''],
    ],
    'multiple-aliases-to-same-target' => [
        'infos' => [info('v2.route', 'v2/route', ['forgeAliases' => ['v1.route', 'legacy.route']])],
        'config' => [],
    ],
];

$aliasOut = [];
foreach ($aliasCases as $name => $case) {
    $entry = ['input' => ['name' => $name, 'infos' => array_map('infoAsArray', $case['infos']), 'config' => (object) $case['config']], 'expected' => null];

    try {
        $resolution = (new AliasResolver($case['config']))->resolve($case['infos']);
        $entry['expected'] = [
            'aliases' => (object) $resolution['aliases'],
            'warnings' => $resolution['warnings'],
            'collisions' => (object) $resolution['collisions'],
            'exception' => null,
        ];
    } catch (ForgeExceptionContract $e) {
        $entry['expected'] = [
            'exception' => ['code' => $e->code(), 'httpStatus' => $e->httpStatus(), 'message' => $e->getMessage()],
        ];
    } catch (Throwable $e) {
        $entry['expected'] = ['plainException' => get_class($e), 'message' => $e->getMessage()];
    }

    $aliasOut[] = $entry;
}

// -------------------------------------------------------------- 严格模式用例

/**
 * 每个用例：levels + classifier + uriPrefixes + infos → violations + format + count + message。
 */
$strictCases = [
    'named-unassigned-only' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'infos' => [
            info('client.orders', 'client/orders'),
            info('admin.users', 'admin/users'),
        ],
    ],
    'unnamed-matched-by-config-goes-missing-name' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['api']]]],
        'infos' => [info(null, 'api/reports', ['methods' => ['GET', 'HEAD'], 'middleware' => ['auth']])],
    ],
    'unnamed-explicit-tier-goes-missing-name' => [
        'levels' => ['admin' => []],
        'infos' => [info(null, 'api/x', ['tier' => 'admin', 'methods' => ['POST']])],
    ],
    'unnamed-via-classifier' => [
        'levels' => ['admin' => [], 'client' => []],
        'classifier' => ['return' => 'client'],
        'infos' => [info(null, 'weird/path')],
    ],
    'unnamed-and-unmatched-is-out-of-scope' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'infos' => [info(null, 'vendor/package/route')],
    ],
    'unnamed-with-unknown-tier-is-unresolved' => [
        'levels' => ['admin' => []],
        'infos' => [info(null, 'api/x', ['tier' => 'ghost'])],
    ],
    'unnamed-with-classifier-error-is-unresolved' => [
        'levels' => ['admin' => []],
        'classifier' => ['throw' => 'boom'],
        'infos' => [info(null, 'api/x')],
    ],
    'unnamed-with-classifier-unknown-tier-is-unresolved' => [
        'levels' => ['admin' => []],
        'classifier' => ['return' => 'ghost'],
        'infos' => [info(null, 'api/x')],
    ],
    'named-with-unknown-tier-is-unresolved-not-unassigned' => [
        'levels' => ['admin' => []],
        'infos' => [info('a.b', 'api/x', ['tier' => 'ghost'])],
    ],
    'forge-own-endpoints-are-excluded-by-uri' => [
        // 包自身层级端点：未命名、带 manage 中间件、会被该层级 match 命中——必须零泄漏
        'levels' => ['manage' => ['match' => ['middleware' => ['manage']]]],
        'uriPrefixes' => ['/_forge/routes'],
        'infos' => [
            info(null, '_forge/routes/manage', ['middleware' => ['manage']]),
            info(null, 'manage/reports', ['middleware' => ['manage']]),
        ],
    ],
    'named-forge-routes-excluded-by-name' => [
        'levels' => ['manage' => ['match' => ['prefix' => ['nope']]]],
        'infos' => [info('forge.routes.manage', 'nope/x'), info('storage.local', 'storage/x')],
    ],
    'sorting-and-counting-with-mixed-groups' => [
        'levels' => [
            'zzz' => ['match' => ['prefix' => ['zzz']]],
            'aaa' => ['match' => ['prefix' => ['aaa']]],
        ],
        'infos' => [
            info(null, 'zzz/2'),
            info(null, 'aaa/1'),
            info('b.name', 'unmatched/b'),
            info('a.name', 'unmatched/a'),
            info('z.name', 'unmatched/z', ['tier' => 'ghost']),
            info(null, 'aaa/0', ['tier' => 'ghost']),
        ],
    ],
    'all-clean' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'infos' => [info('admin.users', 'admin/users')],
    ],
];

$strictOut = [];
foreach ($strictCases as $name => $case) {
    $resolver = new TierResolver(
        $case['levels'] ?? [],
        makeClassifier($case['classifier'] ?? null),
        true,
        new CollectingLogger(),
    );
    $filter = new RouteNameFilter(
        RouteNameFilter::FORGE_PREFIXES,
        array_map(fn ($p) => is_string($p) ? $p : $p, $case['uriPrefixes'] ?? []),
    );
    $scanner = new StrictViolationScanner($resolver, $filter);

    $violations = $scanner->scan($case['infos']);
    $lines = StrictViolationScanner::format($violations);

    $strictOut[] = [
        'input' => [
            'name' => $name,
            'levels' => $case['levels'] ?? [],
            'classifier' => $case['classifier'] ?? null,
            'uriPrefixes' => $case['uriPrefixes'] ?? [],
            'infos' => array_map('infoAsArray', $case['infos']),
        ],
        'expected' => [
            'violations' => $violations,
            'count' => StrictViolationScanner::count($violations),
            'lines' => $lines,
            'message' => implode("\n", $lines),
            // 同实例二次调用必须返回同一份记忆结果
            'memoized' => $scanner->scan($case['infos']) === $violations,
        ],
    ];
}

dump([
    'provenance' => provenance('fixtures/php/oracle-alias-strict.php'),
    'aliasCases' => $aliasOut,
    'strictCases' => $strictOut,
]);
