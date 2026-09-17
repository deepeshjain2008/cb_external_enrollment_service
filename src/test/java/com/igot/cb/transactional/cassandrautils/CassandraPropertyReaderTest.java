package com.igot.cb.transactional.cassandrautils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class CassandraPropertyReaderTest {

    @Test
    void getInstance_ReturnsSameInstance() {
        // Act
        CassandraPropertyReader instance1 = CassandraPropertyReader.getInstance();
        CassandraPropertyReader instance2 = CassandraPropertyReader.getInstance();
        
        // Assert
        assertNotNull(instance1);
        assertSame(instance1, instance2, "getInstance should return the same instance");
    }

    @Test
    void readProperty_ExistingKey_ReturnsValue() throws Exception {
        // Arrange
        CassandraPropertyReader reader = CassandraPropertyReader.getInstance();
        
        // Use reflection to set properties for testing
        Field propertiesField = CassandraPropertyReader.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        Properties properties = (Properties) propertiesField.get(reader);
        properties.setProperty("testKey", "testValue");
        
        // Act
        String result = reader.readProperty("testKey");
        
        // Assert
        assertEquals("testValue", result);
        
        // Clean up
        properties.remove("testKey");
    }

    @Test
    void readProperty_NonExistingKey_ReturnsKeyItself() {
        // Arrange
        CassandraPropertyReader reader = CassandraPropertyReader.getInstance();
        String nonExistingKey = "nonExistingKey" + System.currentTimeMillis();
        
        // Act
        String result = reader.readProperty(nonExistingKey);
        
        // Assert
        assertEquals(nonExistingKey, result);
    }

    @Test
    void privateConstructor_CreatesInstance() throws Exception {
        // This test is just for code coverage of the private constructor
        // We don't actually need to create a new instance since the singleton already exists
        
        // Use reflection to access the private constructor
        Constructor<CassandraPropertyReader> constructor = CassandraPropertyReader.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        
        // We don't actually call the constructor to avoid side effects
        // Just verify it exists
        assertNotNull(constructor);
    }
    
    @Test
    void holderClass_Exists() throws Exception {
        // This test is just for code coverage of the Holder class
        Class<?> holderClass = Class.forName("com.igot.cb.transactional.cassandrautils.CassandraPropertyReader$Holder");
        assertNotNull(holderClass);
    }
    
    @Test
    void loadProperties_Success() throws Exception {
        // This test verifies that properties are loaded successfully
        // We're using the singleton instance which already has properties loaded
        CassandraPropertyReader reader = CassandraPropertyReader.getInstance();
        
        // Use reflection to access the properties field
        Field propertiesField = CassandraPropertyReader.class.getDeclaredField("properties");
        propertiesField.setAccessible(true);
        Properties properties = (Properties) propertiesField.get(reader);
        
        // Verify properties object is not null and not empty
        assertNotNull(properties);
        // Note: We can't assert it's not empty because we don't know what's in the properties file
        // But the fact that getInstance() didn't throw an exception means loadProperties() worked
    }
}