package com.igot.cb.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RedisConfigTest {

    @InjectMocks
    private RedisConfig redisConfig;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(redisConfig, "redisHost", "localhost");
        ReflectionTestUtils.setField(redisConfig, "redisPort", 6379);
    }

    @Test
    void redisConnectionFactory() {
        // Act
        RedisConnectionFactory factory = redisConfig.redisConnectionFactory();
        
        // Assert
        assertNotNull(factory);
        assertTrue(factory instanceof LettuceConnectionFactory);
        
        LettuceConnectionFactory lettuceFactory = (LettuceConnectionFactory) factory;
        RedisStandaloneConfiguration configuration = lettuceFactory.getStandaloneConfiguration();
        
        assertEquals("localhost", configuration.getHostName());
        assertEquals(6379, configuration.getPort());
        assertEquals(0, configuration.getDatabase());
    }

    @Test
    void buildPoolConfig() {
        // Act
        GenericObjectPoolConfig<?> poolConfig = ReflectionTestUtils.invokeMethod(redisConfig, "buildPoolConfig");
        
        // Assert
        assertNotNull(poolConfig);
        assertEquals(3000, poolConfig.getMaxTotal());
        assertEquals(128, poolConfig.getMaxIdle());
        assertEquals(100, poolConfig.getMinIdle());
        // Use getMaxWaitDuration() instead of getMaxWait()
        assertEquals(Duration.ofMillis(5000), poolConfig.getMaxWaitDuration());
    }

    @Test
    void redisTemplate() {
        // Arrange
        RedisConnectionFactory mockFactory = new LettuceConnectionFactory();
        
        // Act
        RedisTemplate<String, String> template = redisConfig.redisTemplate(mockFactory);
        
        // Assert
        assertNotNull(template);
        assertEquals(mockFactory, template.getConnectionFactory());
    }
}