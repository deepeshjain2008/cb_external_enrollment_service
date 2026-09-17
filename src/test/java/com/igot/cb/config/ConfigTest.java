package com.igot.cb.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConfigTest {

    @Test
    void testConsumerConfiguration() {
        ConsumerConfiguration consumerConfiguration = new ConsumerConfiguration();
        ReflectionTestUtils.setField(consumerConfiguration, "kafkabootstrapAddress", "localhost:9092");
        ReflectionTestUtils.setField(consumerConfiguration, "kafkaOffsetResetValue", "earliest");
        ReflectionTestUtils.setField(consumerConfiguration, "kafkaMaxPollInterval", 300000);
        ReflectionTestUtils.setField(consumerConfiguration, "kafkaMaxPollRecords", 500);
        ReflectionTestUtils.setField(consumerConfiguration, "kafkaAutoCommitInterval", 1000);

        Map<String, Object> props = consumerConfiguration.consumerConfigs();

        assertNotNull(props);
        assertEquals("localhost:9092", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        assertEquals(300000, props.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));
        assertEquals(500, props.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
        assertEquals(1000, props.get(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG));
    }

    @Test
    void testProducerConfiguration() {
        ProducerConfiguration producerConfiguration = new ProducerConfiguration();
        ReflectionTestUtils.setField(producerConfiguration, "kafkabootstrapAddress", "localhost:9092");

        ProducerFactory<String, String> factory = producerConfiguration.producerFactory();
        assertNotNull(factory);
        // It's hard to inspect the config of the DefaultKafkaProducerFactory directly
        // without more reflection or casting,
        // but checking it is not null is a basic validation that the method runs.

        // We can also potentially check the properties if we cast or use reflection on
        // the factory if needed,
        // but for now, ensuring the bean creation methods don't throw exceptions is a
        // good step.
    }

    @Test
    void testRedisConfig() {
        RedisConfig redisConfig = new RedisConfig();
        ReflectionTestUtils.setField(redisConfig, "redisHost", "localhost");
        ReflectionTestUtils.setField(redisConfig, "redisPort", 6379);
        ReflectionTestUtils.setField(redisConfig, "defaultIndex", 0);

        LettuceConnectionFactory factory = (LettuceConnectionFactory) redisConfig.redisConnectionFactory();
        assertNotNull(factory);

        RedisStandaloneConfiguration config = factory.getStandaloneConfiguration();
        assertNotNull(config);
        assertEquals("localhost", config.getHostName());
        assertEquals(6379, config.getPort());
        assertEquals(0, config.getDatabase());
    }
}