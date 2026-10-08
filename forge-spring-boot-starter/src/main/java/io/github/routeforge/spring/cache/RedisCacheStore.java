package io.github.routeforge.spring.cache;

import io.github.routeforge.core.contract.CacheStore;
import io.github.routeforge.core.exception.CacheDriverException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 版 {@link CacheStore}（{@code forge.cache-driver=redis}）：多实例共享一份路由元信息缓存。
 *
 * <p><b>值用 JDK 序列化，绝不用 Jackson</b>：缓存值是 {@code RouteRepository} 产出的 {@code Map}/{@code List}，
 * 需原样类型回还（{@code RouteCache.get} 靠 {@code type.isInstance} 判定命中）。Jackson 一旦进 {@code src/main}
 * 会重新踩开 Boot 3（Jackson 2）/ Boot 4（Jackson 3 {@code tools.jackson}）的断层——那正是本包刻意规避的。
 * 而这里序列化的永远是<b>自己写的</b> Map/List（非外部不可信输入），JDK 序列化的安全顾虑不适用。
 *
 * <p>键用 {@link StringRedisSerializer}（{@code route-forge:{level}} / {@code route-forge:_keys} 人类可读、可 redis-cli 查）。
 * TTL：{@code seconds==null}（含 ≤0）＝永久（{@code RouteCache} 已把「0=永久」归一为 null 下发）；正数＝{@code EXPIRE}。
 *
 * <p>类只在 {@code cache-driver=redis} 时被装配加载（见 {@code ForgeAutoConfiguration} 的 {@code ClassUtils.isPresent}
 * 前置守卫 + 惰性构造），故 Redis 不在 classpath 的宿主根本不会触发本类的任何 Redis 类型解析。测试经
 * {@link #RedisCacheStore(RedisOperations)} 注入 mock 的 {@code RedisTemplate} 即可覆盖读写/过期/失效逻辑，无需真 Redis。
 */
public final class RedisCacheStore implements CacheStore {

    private final RedisOperations<String, Object> redis;

    /** 直接注入已配置好的模板（测试用 mock；生产走 {@link #from}）。 */
    public RedisCacheStore(RedisOperations<String, Object> redis) {
        this.redis = redis;
    }

    /**
     * 用容器里的 {@link RedisConnectionFactory} 构建（键 String、值 JDK 序列化）。
     *
     * <p>无 {@code RedisConnectionFactory} bean（没引 {@code spring-boot-starter-data-redis} 或未配置连接）
     * → 抛 {@link CacheDriverException}（{@code RF_BE_003}），绝不静默退回内存。
     */
    public static RedisCacheStore from(ApplicationContext context) {
        RedisConnectionFactory factory;
        try {
            factory = context.getBean(RedisConnectionFactory.class);
        } catch (NoSuchBeanDefinitionException e) {
            throw new CacheDriverException(
                    "forge.cache-driver=redis 但容器里没有 RedisConnectionFactory；请引入 spring-boot-starter-data-redis "
                            + "并配置连接（Redis 不静默退回内存）", e);
        }
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new JdkSerializationRedisSerializer());
        template.setHashValueSerializer(new JdkSerializationRedisSerializer());
        template.afterPropertiesSet();
        return new RedisCacheStore(template);
    }

    @Override
    public Object get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public void put(String key, Object value, Long seconds) {
        if (seconds == null || seconds <= 0) {
            redis.opsForValue().set(key, value);
        } else {
            redis.opsForValue().set(key, value, seconds, TimeUnit.SECONDS);
        }
    }

    @Override
    public void forget(String key) {
        redis.delete(key);
    }
}
