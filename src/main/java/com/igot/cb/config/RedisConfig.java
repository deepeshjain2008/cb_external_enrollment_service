package com.igot.cb.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

  @Value("${spring.redis.host}")
  private String redisHost;

  @Value("${spring.redis.port}")
  private int redisPort;

  @Value("${spring.redis.default.index}")
  private int defaultIndex;

  private static final long redisTimeout = 60000;

  @Bean
  @Primary
  public RedisConnectionFactory redisConnectionFactory() {
    return createConnectionFactory(defaultIndex);
  }

  @Bean
  @Primary
  public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory redisConnectionFactory) {
    return createRedisTemplate(redisConnectionFactory);
  }

  public RedisConnectionFactory createConnectionFactory(int database) {
    RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration();
    configuration.setHostName(redisHost);
    configuration.setPort(redisPort);
    configuration.setDatabase(database);

    LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(redisTimeout))
            .poolConfig(buildPoolConfig())
            .build();

    LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration, clientConfig);
    factory.setShareNativeConnection(false); // CRITICAL: Disable shared connections
    factory.afterPropertiesSet();
    return factory;
  }

  public RedisTemplate<String, String> createRedisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setHashValueSerializer(new StringRedisSerializer());
    template.afterPropertiesSet();
    return template;
  }

  private GenericObjectPoolConfig<?> buildPoolConfig() {
    GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
    poolConfig.setMaxTotal(3000);
    poolConfig.setMaxIdle(128);
    poolConfig.setMinIdle(100);
    poolConfig.setMaxWait(Duration.ofMillis(5000));
    return poolConfig;
  }
}