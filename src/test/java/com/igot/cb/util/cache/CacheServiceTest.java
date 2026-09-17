package com.igot.cb.util.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    private CacheService cacheService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private RedisTemplate<String, String> redisTemplateDb1;

    @Mock
    private RedisConfig redisConfig;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ValueOperations<String, String> valueOperationsDb1;

    @Mock
    private RedisConnectionFactory connectionFactory;

    private ConcurrentHashMap<Integer, RedisTemplate<String, String>> templateCache;

    @BeforeEach
    void setUp() {
        cacheService = new CacheService(redisTemplate, redisConfig, objectMapper);
        ReflectionTestUtils.setField(cacheService, "cacheTtl", 3600L);
        ReflectionTestUtils.setField(cacheService, "defaultDatabase", 0);

        templateCache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(cacheService, "templateCache", templateCache);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplateDb1.opsForValue()).thenReturn(valueOperationsDb1);
    }

    @Test
    void putCache_SuccessDefaultDatabase() throws Exception {
        String key = "testKey";
        Map<String, String> object = new HashMap<>();
        object.put("field", "value");
        String jsonString = "{\"field\":\"value\"}";

        when(objectMapper.writeValueAsString(object)).thenReturn(jsonString);

        cacheService.putCache(key, 0, object);

        verify(valueOperations).set(key, jsonString, 3600L, TimeUnit.SECONDS);
    }

    @Test
    void putCache_SuccessCustomDatabase() throws Exception {
        String key = "testKey";
        Map<String, String> object = new HashMap<>();
        object.put("field", "value");
        String jsonString = "{\"field\":\"value\"}";

        when(objectMapper.writeValueAsString(object)).thenReturn(jsonString);
        when(redisConfig.createConnectionFactory(1)).thenReturn(connectionFactory);
        when(redisConfig.createRedisTemplate(connectionFactory)).thenReturn(redisTemplateDb1);

        cacheService.putCache(key, 1, object);

        verify(valueOperationsDb1).set(key, jsonString, 3600L, TimeUnit.SECONDS);
        assertEquals(1, templateCache.size());
        assertTrue(templateCache.containsKey(1));
    }

    @Test
    void putCache_ReusesCachedTemplate() throws Exception {
        String key = "testKey";
        Map<String, String> object = new HashMap<>();
        object.put("field", "value");
        String jsonString = "{\"field\":\"value\"}";

        when(objectMapper.writeValueAsString(object)).thenReturn(jsonString);
        when(redisConfig.createConnectionFactory(1)).thenReturn(connectionFactory);
        when(redisConfig.createRedisTemplate(connectionFactory)).thenReturn(redisTemplateDb1);

        // First call - creates template
        cacheService.putCache(key, 1, object);

        // Second call - reuses template
        cacheService.putCache(key, 1, object);

        verify(redisConfig, times(1)).createConnectionFactory(1);
        verify(redisConfig, times(1)).createRedisTemplate(connectionFactory);
        verify(valueOperationsDb1, times(2)).set(key, jsonString, 3600L, TimeUnit.SECONDS);
    }

    @Test
    void putCache_Exception() throws Exception {
        String key = "testKey";
        Object object = new Object();

        when(objectMapper.writeValueAsString(object)).thenThrow(new RuntimeException("Test exception"));

        assertDoesNotThrow(() -> cacheService.putCache(key, 0, object));
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void getCache_SuccessDefaultDatabase() {
        String key = "testKey";
        String cachedValue = "{\"field\":\"value\"}";

        when(valueOperations.get(key)).thenReturn(cachedValue);

        String result = cacheService.getCache(key, 0);

        assertEquals(cachedValue, result);
        verify(valueOperations).get(key);
    }

    @Test
    void getCache_SuccessCustomDatabase() {
        String key = "testKey";
        String cachedValue = "{\"field\":\"value\"}";

        when(redisConfig.createConnectionFactory(1)).thenReturn(connectionFactory);
        when(redisConfig.createRedisTemplate(connectionFactory)).thenReturn(redisTemplateDb1);
        when(valueOperationsDb1.get(key)).thenReturn(cachedValue);

        String result = cacheService.getCache(key, 1);

        assertEquals(cachedValue, result);
        verify(valueOperationsDb1).get(key);
    }

    @Test
    void getCache_ReturnsNull() {
        String key = "nonExistentKey";

        when(valueOperations.get(key)).thenReturn(null);

        String result = cacheService.getCache(key, 0);

        assertNull(result);
        verify(valueOperations).get(key);
    }

    @Test
    void getCache_Exception() {
        String key = "testKey";

        when(valueOperations.get(key)).thenThrow(new RuntimeException("Test exception"));

        String result = cacheService.getCache(key, 0);

        assertNull(result);
    }

    @Test
    void deleteCache_SuccessDefaultDatabase() {
        String key = "testKey";

        when(redisTemplate.delete(key)).thenReturn(true);

        Boolean result = cacheService.deleteCache(key, 0);

        assertTrue(result);
        verify(redisTemplate).delete(key);
    }

    @Test
    void deleteCache_SuccessCustomDatabase() {
        String key = "testKey";

        when(redisConfig.createConnectionFactory(1)).thenReturn(connectionFactory);
        when(redisConfig.createRedisTemplate(connectionFactory)).thenReturn(redisTemplateDb1);
        when(redisTemplateDb1.delete(key)).thenReturn(true);

        Boolean result = cacheService.deleteCache(key, 1);

        assertTrue(result);
        verify(redisTemplateDb1).delete(key);
    }

    @Test
    void deleteCache_KeyNotFound() {
        String key = "nonExistentKey";

        when(redisTemplate.delete(key)).thenReturn(false);

        Boolean result = cacheService.deleteCache(key, 0);

        assertFalse(result);
        verify(redisTemplate).delete(key);
    }

    @Test
    void deleteCache_Exception() {
        String key = "testKey";

        when(redisTemplate.delete(key)).thenThrow(new RuntimeException("Test exception"));

        Boolean result = cacheService.deleteCache(key, 0);

        assertFalse(result);
    }

    @Test
    void incrementIfExists_SuccessDefaultDatabase() {
        String key = "counter";
        long delta = 1L;

        when(redisTemplate.hasKey(key)).thenReturn(true);
        when(valueOperations.increment(key, delta)).thenReturn(6L);

        Long result = cacheService.incrementIfExists(key, delta, 0);

        assertEquals(6L, result);
        verify(redisTemplate).hasKey(key);
        verify(valueOperations).increment(key, delta);
    }

    @Test
    void incrementIfExists_SuccessCustomDatabase() {
        String key = "counter";
        long delta = 1L;

        when(redisConfig.createConnectionFactory(1)).thenReturn(connectionFactory);
        when(redisConfig.createRedisTemplate(connectionFactory)).thenReturn(redisTemplateDb1);
        when(redisTemplateDb1.hasKey(key)).thenReturn(true);
        when(valueOperationsDb1.increment(key, delta)).thenReturn(10L);

        Long result = cacheService.incrementIfExists(key, delta, 1);

        assertEquals(10L, result);
        verify(redisTemplateDb1).hasKey(key);
        verify(valueOperationsDb1).increment(key, delta);
    }

    @Test
    void incrementIfExists_KeyNotFound() {
        String key = "counter";
        long delta = 1L;

        when(redisTemplate.hasKey(key)).thenReturn(false);

        Long result = cacheService.incrementIfExists(key, delta, 0);

        assertNull(result);
        verify(redisTemplate).hasKey(key);
        verify(valueOperations, never()).increment(anyString(), anyLong());
    }

    @Test
    void incrementIfExists_KeyExistsReturnNull() {
        String key = "counter";
        long delta = 1L;

        when(redisTemplate.hasKey(key)).thenReturn(null);

        Long result = cacheService.incrementIfExists(key, delta, 0);

        assertNull(result);
        verify(valueOperations, never()).increment(anyString(), anyLong());
    }

    @Test
    void incrementIfExists_NegativeDelta() {
        String key = "counter";
        long delta = -1L;

        when(redisTemplate.hasKey(key)).thenReturn(true);
        when(valueOperations.increment(key, delta)).thenReturn(4L);

        Long result = cacheService.incrementIfExists(key, delta, 0);

        assertEquals(4L, result);
        verify(valueOperations).increment(key, delta);
    }

    @Test
    void incrementIfExists_Exception() {
        String key = "counter";
        long delta = 1L;

        when(redisTemplate.hasKey(key)).thenThrow(new RuntimeException("Test exception"));

        Long result = cacheService.incrementIfExists(key, delta, 0);

        assertNull(result);
    }

    @Test
    void getTemplate_DefaultDatabaseReturnsDefaultTemplate() {
        // Use reflection to call private method for testing
        RedisTemplate<String, String> result = ReflectionTestUtils.invokeMethod(
                cacheService, "getTemplate", 0
        );

        assertEquals(redisTemplate, result);
        verify(redisConfig, never()).createConnectionFactory(anyInt());
    }

    @Test
    void getTemplate_CreatesAndCachesNewTemplate() {
        when(redisConfig.createConnectionFactory(2)).thenReturn(connectionFactory);
        when(redisConfig.createRedisTemplate(connectionFactory)).thenReturn(redisTemplateDb1);

        // First call
        RedisTemplate<String, String> result1 = ReflectionTestUtils.invokeMethod(
                cacheService, "getTemplate", 2
        );

        // Second call - should use cached template
        RedisTemplate<String, String> result2 = ReflectionTestUtils.invokeMethod(
                cacheService, "getTemplate", 2
        );

        assertSame(result1, result2);
        assertEquals(redisTemplateDb1, result1);
        verify(redisConfig, times(1)).createConnectionFactory(2);
        verify(redisConfig, times(1)).createRedisTemplate(connectionFactory);
    }

    @Test
    void multipleDatabases_WorksIndependently() throws Exception {
        String key = "testKey";
        Map<String, String> object = new HashMap<>();
        object.put("field", "value");
        String jsonString = "{\"field\":\"value\"}";

        when(objectMapper.writeValueAsString(object)).thenReturn(jsonString);
        when(redisConfig.createConnectionFactory(1)).thenReturn(connectionFactory);
        when(redisConfig.createRedisTemplate(connectionFactory)).thenReturn(redisTemplateDb1);

        // Save to database 0
        cacheService.putCache(key, 0, object);

        // Save to database 1
        cacheService.putCache(key, 1, object);

        verify(valueOperations).set(key, jsonString, 3600L, TimeUnit.SECONDS);
        verify(valueOperationsDb1).set(key, jsonString, 3600L, TimeUnit.SECONDS);
        assertEquals(1, templateCache.size());
    }
}