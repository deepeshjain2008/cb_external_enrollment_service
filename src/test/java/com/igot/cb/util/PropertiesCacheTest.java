package com.igot.cb.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.StringUtils;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PropertiesCacheTest {

    private PropertiesCache propertiesCache;

    @BeforeEach
    void setUp() {
        propertiesCache = PropertiesCache.getInstance();
    }

    @Test
    void testSingletonInstance() {
        PropertiesCache instance1 = PropertiesCache.getInstance();
        PropertiesCache instance2 = PropertiesCache.getInstance();
        assertSame(instance1, instance2, "Instances should be the same (singleton)");
    }

    @Test
    void testGetProperty_FromEnvironmentVariable_Simulated() throws Exception {
        var field = PropertiesCache.class.getDeclaredField("configProp");
        field.setAccessible(true);
        Properties props = (Properties) field.get(propertiesCache);
        props.remove("TEST_KEY");
        String result = propertiesCache.getProperty("TEST_KEY");
        assertTrue(result.equals("TEST_KEY") || StringUtils.isNotBlank(System.getenv("TEST_KEY")));
    }

    @Test
    void testGetProperty_FromPropertiesFile() throws Exception {
        var field = PropertiesCache.class.getDeclaredField("configProp");
        field.setAccessible(true);
        Properties props = (Properties) field.get(propertiesCache);
        props.setProperty("PROP_KEY", "file_value");
        String result = propertiesCache.getProperty("PROP_KEY");
        assertEquals("file_value", result);
    }

    @Test
    void testGetProperty_WhenKeyNotFound() {
        String result = propertiesCache.getProperty("UNKNOWN_KEY_DOES_NOT_EXIST");
        assertEquals("UNKNOWN_KEY_DOES_NOT_EXIST", result,
                "Should return the key itself if not found");
    }

    @Test
    void testReadProperty_FromEnvironmentVariable_Safe() throws Exception {
        var field = PropertiesCache.class.getDeclaredField("configProp");
        field.setAccessible(true);
        Properties props = (Properties) field.get(propertiesCache);
        props.remove("READ_KEY");
        String result = propertiesCache.readProperty("READ_KEY");
        String envValue = System.getenv("READ_KEY");
        if (envValue != null) {
            assertEquals(envValue, result);
        } else {
            assertNull(result);
        }
    }

    @Test
    void testReadProperty_FromConfigProperty() throws Exception {
        var field = PropertiesCache.class.getDeclaredField("configProp");
        field.setAccessible(true);
        Properties props = (Properties) field.get(propertiesCache);
        props.setProperty("READ_KEY_FILE", "FILE_VALUE");
        String result = propertiesCache.readProperty("READ_KEY_FILE");
        assertEquals("FILE_VALUE", result);
    }

    @Test
    void testReadProperty_WhenKeyNotFound() {
        String result = propertiesCache.readProperty("MISSING_KEY_DOES_NOT_EXIST");
        assertNull(result, "Should return null when neither env nor file has the key");
    }

    @Test
    void testConstructorHandlesMissingFilesGracefully() throws Exception {
        ClassLoader mockLoader = mock(ClassLoader.class);
        when(mockLoader.getResourceAsStream(anyString())).thenReturn(null);
        var constructor = PropertiesCache.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertDoesNotThrow(() -> {
            PropertiesCache newInstance = constructor.newInstance();
            assertNotNull(newInstance);
        });
    }

}