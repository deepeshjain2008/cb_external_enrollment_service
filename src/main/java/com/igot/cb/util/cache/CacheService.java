package com.igot.cb.util.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class CacheService {

  private final RedisTemplate<String, String> redisTemplate;

  private final RedisConfig redisConfig;

  private final ObjectMapper objectMapper;

  @Value("${spring.redis.cacheTtl}")
  private long cacheTtl;

  @Value("${spring.redis.database:0}")
  private int defaultDatabase;

  private final ConcurrentHashMap<Integer, RedisTemplate<String, String>> templateCache = new ConcurrentHashMap<>();

  public CacheService(RedisTemplate<String, String> redisTemplate, RedisConfig redisConfig, ObjectMapper objectMapper) {
    this.redisTemplate = redisTemplate;
    this.redisConfig = redisConfig;
    this.objectMapper = objectMapper;
  }

  private RedisTemplate<String, String> getTemplate(int dbIndex) {
    if (dbIndex == defaultDatabase) {
      return redisTemplate;
    }

    return templateCache.computeIfAbsent(dbIndex, db -> {
      log.info("Creating new RedisTemplate for database: {}", db);
      return redisConfig.createRedisTemplate(redisConfig.createConnectionFactory(db));
    });
  }

  public void putCache(String key, int dbIndex, Object object) {
    try {
      RedisTemplate<String, String> template = getTemplate(dbIndex);
      String data = objectMapper.writeValueAsString(object);
      template.opsForValue().set(key, data, cacheTtl, TimeUnit.SECONDS);
      log.debug("Data saved to database {} with key: {}", dbIndex, key);
    } catch (Exception e) {
      log.error("Error while putting data in Redis cache: {} ", e.getMessage());
    }
  }

  public String getCache(String key, int dbIndex) {
    try {
      RedisTemplate<String, String> template = getTemplate(dbIndex);
      return template.opsForValue().get(key);
    } catch (Exception e) {
      log.error("Error while getting data from Redis cache: {} ", e.getMessage());
      return null;
    }
  }

  public Boolean deleteCache(String key, int dbIndex) {
    try {
      RedisTemplate<String, String> template = getTemplate(dbIndex);
      boolean result = template.delete(key);
      if(result) {
        log.info("Key {} deleted successfully from database {}.", key, dbIndex);
      } else {
        log.warn("Key {} not found in database {}.", key, dbIndex);
      }
      return result;
    } catch (Exception e) {
      log.error("Error while deleting key from Redis cache: {} ", e.getMessage());
      return false;
    }
  }

  public Long incrementIfExists(String key, long delta, int dbIndex) {
    try {
      RedisTemplate<String, String> template = getTemplate(dbIndex);
      Boolean exists = template.hasKey(key);
      if (Boolean.TRUE.equals(exists)) {
        return template.opsForValue().increment(key, delta);
      } else {
        log.warn("Key {} does not exist in database {}, increment skipped.", key, dbIndex);
        return null;
      }
    } catch (Exception e) {
      log.error("Error while incrementing key in Redis cache: {} ", e.getMessage());
      return null;
    }
  }
}