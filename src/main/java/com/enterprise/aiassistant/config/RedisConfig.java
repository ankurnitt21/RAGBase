package com.enterprise.aiassistant.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/**
 * Creates the shared {@link UnifiedJedis} (connection-pooled) bean used by
 * {@link com.enterprise.aiassistant.service.SemanticCacheService} for vector
 * similarity search via Redis Stack's RediSearch module.
 *
 * Connection pool sizing:
 *   maxTotal=20 — concurrent Redis connections; scale with app thread count
 *   maxIdle=10  — connections kept alive between bursts
 *   minIdle=2   — pre-warmed connections to avoid cold-start latency spikes
 *
 * If Redis is unreachable at startup the bean creation fails loudly so the
 * misconfiguration is caught immediately. Disable the semantic cache
 * (app.semantic-cache.enabled=false) if Redis is not available in an environment.
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Value("${app.redis.host}")
    private String host;

    @Value("${app.redis.port}")
    private int port;

    @Value("${app.redis.user:default}")
    private String user;

    @Value("${app.redis.password}")
    private String password;

    @Value("${app.redis.ssl:false}")
    private boolean ssl;

    @Bean
    public UnifiedJedis jedis() {
        JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .user(user)
                .password(password)
                .ssl(ssl)
                .build();

        GenericObjectPoolConfig<Connection> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);

        JedisPooled pooled = new JedisPooled(
                new HostAndPort(host, port),
                clientConfig,
                poolConfig
        );

        log.info("Redis connection pool created → {}:{} (ssl={})", host, port, ssl);
        return pooled;
    }
}
