<?php

declare(strict_types=1);

/**
 * 参照器公共装配：psr-4 装载 php-common、PSR-3 桩、路由与 classifier 构造助手。
 *
 * 各 oracle-*.php 复用本文件，保证「同一套构造方式」产出所有 fixture。
 */

$commonSrc = getenv('ROUTE_FORGE_COMMON_SRC') ?: 'G:/Web/php-common/src';

spl_autoload_register(function (string $class) use ($commonSrc): void {
    if (str_starts_with($class, 'RouteForge\\Common\\')) {
        $path = $commonSrc . '/' . str_replace('\\', '/', substr($class, strlen('RouteForge\\Common\\'))) . '.php';
        if (is_file($path)) {
            require $path;
        }
    }
});

if (!interface_exists(Psr\Log\LoggerInterface::class)) {
    eval('namespace Psr\Log; interface LoggerInterface {
        public function emergency($m, array $c = []); public function alert($m, array $c = []);
        public function critical($m, array $c = []); public function error($m, array $c = []);
        public function warning($m, array $c = []); public function notice($m, array $c = []);
        public function info($m, array $c = []); public function debug($m, array $c = []);
        public function log($l, $m, array $c = []);
    }');
}

/** 收集 warning 的 logger 桩：顺序与文案都要能被 Java 侧复现。 */
final class CollectingLogger implements Psr\Log\LoggerInterface
{
    /** @var list<string> */
    public array $messages = [];

    public function emergency($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function alert($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function critical($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function error($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function warning($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function notice($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function info($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function debug($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function log($level, $message, array $context = []): void
    {
        $this->messages[] = (string) $message;
    }
}

/**
 * 构造统一路由信息。
 *
 * @param string[]             $methods
 * @param string[]             $middleware
 * @param array<string,mixed>  $parameterDefaults
 * @param array<string,mixed>  $extra
 */
function info(?string $name, string $uri, array $extra = []): RouteForge\Common\Dto\RouteInfo
{
    return new RouteForge\Common\Dto\RouteInfo(
        name: $name,
        uri: $uri,
        methods: $extra['methods'] ?? ['GET', 'HEAD'],
        parameters: $extra['parameters'] ?? [],
        parameterDefaults: $extra['parameterDefaults'] ?? [],
        middleware: $extra['middleware'] ?? [],
        tier: $extra['tier'] ?? null,
        forgeAliases: $extra['forgeAliases'] ?? [],
        source: null,
    );
}

/**
 * classifier 描述符 → 闭包。描述符形态与 Java 侧一一对应：
 * {"return": "admin"} / {"returnRaw": 123} / {"throw": "boom"} / null
 */
function makeClassifier(?array $descriptor): ?callable
{
    if ($descriptor === null) {
        return null;
    }
    if (array_key_exists('return', $descriptor)) {
        return fn ($route) => $descriptor['return'];
    }
    if (array_key_exists('returnRaw', $descriptor)) {
        return fn ($route) => $descriptor['returnRaw'];
    }
    if (array_key_exists('throw', $descriptor)) {
        return function ($route) use ($descriptor) {
            throw new RuntimeException($descriptor['throw']);
        };
    }
    throw new InvalidArgumentException('未知 classifier 描述符');
}

/** 路由数组 → 可 JSON 化的输入形态（Java 侧按同结构构造 RouteInfo）。 */
function infoAsArray(RouteForge\Common\Dto\RouteInfo $info): array
{
    return [
        'name' => $info->name,
        'uri' => $info->uri,
        'methods' => $info->methods,
        'parameters' => $info->parameters,
        'parameterDefaults' => (object) $info->parameterDefaults,
        'middleware' => $info->middleware,
        'tier' => $info->tier,
        'forgeAliases' => $info->forgeAliases,
    ];
}

function provenance(string $generator): array
{
    $src = getenv('ROUTE_FORGE_COMMON_SRC') ?: 'G:/Web/php-common/src';
    $commit = trim((string) @shell_exec('git -C ' . escapeshellarg(dirname($src)) . ' rev-parse --short HEAD 2>NUL'));

    return [
        'generator' => $generator,
        'php' => PHP_VERSION,
        'phpCommonCommit' => $commit !== '' ? $commit : 'unknown',
        'note' => 'expected 由真实 php-common 代码产出，Java 侧不得按文档自编断言',
    ];
}

function dump(array $payload): void
{
    fwrite(STDOUT, json_encode($payload, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE) . PHP_EOL);
}
