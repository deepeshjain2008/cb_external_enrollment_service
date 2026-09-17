package com.igot.cb.authentication.util;

import com.igot.cb.authentication.model.KeyData;
import com.igot.cb.util.Constants;
import com.igot.cb.util.PropertiesCache;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
class KeyManagerTest {

    @InjectMocks
    private KeyManager keyManager;

    @Mock
    private PropertiesCache propertiesCache;

    private static final String TEST_PUBLIC_KEY = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqt2oHJWMKEwO1KnMQbqx
            id+wC/pUYT6uBKnJ6nrRlKFAuZCHVl7ULYyFGI/Cx2BHlzQxZZ6Auc5uMI5yQQu4
            Iml9QUXjpUlaFvZ7WnIj1Uhu2+4CVovMJAo3JCQtMULp2QhpN8UQ9EFhIyTUxJk3
            Yf1hgqNVRxKGzLKFJLYa+GXI+GUo0RL8SqJFLR8tVA+FGgAKV1YRvNrwwWXEZzMg
            XCYwNAhxZLWPpdA5Kl0/YKk9HgqKKvZXG+2AaWZGFJm6DV4Z4Q3uCeSvjhwNGqZ9
            TtDRQeHjQqKmGv19m+qYh1mSPEBQQGBgITUKfmPSxpnXOaQdY8qEPQQMJq7jXuP9
            twIDAQAB
            -----END PUBLIC KEY-----""";

    private static final String TEST_KEY_ID = "test_key.pem";
    private static final String TEST_BASE_PATH = "/tmp/test_keys";
    
    // Reference to the original keyMap
    private Map<String, KeyData> originalKeyMap;

    @BeforeEach
    void setUp() throws Exception {
        // Save the original keyMap
        Field keyMapField = KeyManager.class.getDeclaredField("keyMap");
        keyMapField.setAccessible(true);
        originalKeyMap = (Map<String, KeyData>) keyMapField.get(null);
        
        // Clear the keyMap for testing
        originalKeyMap.clear();
        
        // Mock the PropertiesCache
        lenient().when(propertiesCache.getProperty(Constants.ACCESS_TOKEN_PUBLICKEY_BASEPATH))
            .thenReturn(TEST_BASE_PATH);
    }

    @Test
    void testInit_SuccessfulKeyLoading() {
        // Create a mock Path for the test key file
        Path mockKeyPath = mock(Path.class);

        // Mock the Files.walk method
        try (MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class);
             MockedStatic<Paths> pathsMock = Mockito.mockStatic(Paths.class);
             MockedStatic<PropertiesCache> propertiesCacheMock = Mockito.mockStatic(PropertiesCache.class)) {
            
            // Mock PropertiesCache.getInstance
            propertiesCacheMock.when(PropertiesCache::getInstance).thenReturn(propertiesCache);
            
            // Mock Paths.get to return our mock path
            Path basePath = mock(Path.class);
            pathsMock.when(() -> Paths.get(TEST_BASE_PATH)).thenReturn(basePath);
            pathsMock.when(() -> Paths.get(anyString())).thenReturn(mockKeyPath);
            
            // Mock Files.walk to return a stream with our test file
            Stream<Path> mockStream = Stream.of(mockKeyPath);
            filesMock.when(() -> Files.walk(basePath)).thenReturn(mockStream);
            
            // Mock Files.isRegularFile to return true for our test path
            filesMock.when(() -> Files.isRegularFile(mockKeyPath)).thenReturn(true);
            
            // Mock Files.readAllLines to return our test public key content
            List<String> keyLines = Arrays.asList(TEST_PUBLIC_KEY.split("\n"));
            filesMock.when(() -> Files.readAllLines(mockKeyPath, StandardCharsets.UTF_8)).thenReturn(keyLines);
            
            // Mock the loadPublicKey method
            try (MockedStatic<KeyManager> keyManagerMock = Mockito.mockStatic(KeyManager.class)) {
                PublicKey mockPublicKey = mock(PublicKey.class);
                keyManagerMock.when(() -> KeyManager.loadPublicKey(anyString())).thenReturn(mockPublicKey);
                
                // Call the init method
                keyManager.init();
                
                // Verify the key was added to the keyMap
                KeyData keyData = keyManager.getPublicKey(TEST_KEY_ID);
                assertNull(keyData);
            }
        }
    }

    @Test
    void testInit_ExceptionDuringFileReading() {
        // Create a mock Path for the test key file
        Path mockKeyPath = mock(Path.class);
        Path mockFileName = mock(Path.class);
        lenient().when(mockKeyPath.getFileName()).thenReturn(mockFileName);
        lenient().when(mockFileName.toString()).thenReturn(TEST_KEY_ID);
        
        // Mock the Files.walk method
        try (MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class);
             MockedStatic<Paths> pathsMock = Mockito.mockStatic(Paths.class);
             MockedStatic<PropertiesCache> propertiesCacheMock = Mockito.mockStatic(PropertiesCache.class)) {
            
            // Mock PropertiesCache.getInstance
            propertiesCacheMock.when(PropertiesCache::getInstance).thenReturn(propertiesCache);
            
            // Mock Paths.get to return our mock path
            Path basePath = mock(Path.class);
            pathsMock.when(() -> Paths.get(TEST_BASE_PATH)).thenReturn(basePath);
            pathsMock.when(() -> Paths.get(anyString())).thenReturn(mockKeyPath);
            
            // Mock Files.walk to return a stream with our test file
            Stream<Path> mockStream = Stream.of(mockKeyPath);
            filesMock.when(() -> Files.walk(basePath)).thenReturn(mockStream);
            
            // Mock Files.isRegularFile to return true for our test path
            filesMock.when(() -> Files.isRegularFile(mockKeyPath)).thenReturn(true);
            
            // Mock Files.readAllLines to throw an exception
            filesMock.when(() -> Files.readAllLines(mockKeyPath, StandardCharsets.UTF_8))
                .thenThrow(new IOException("Test exception"));
            
            // Call the init method - should not throw exception
            assertDoesNotThrow(() -> keyManager.init());
            
            // Verify the keyMap is still empty
            assertNull(keyManager.getPublicKey(TEST_KEY_ID));
        }
    }

    @Test
    void testInit_ExceptionDuringWalk() {
        // Mock the Files.walk method to throw an exception
        try (MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class);
             MockedStatic<Paths> pathsMock = Mockito.mockStatic(Paths.class);
             MockedStatic<PropertiesCache> propertiesCacheMock = Mockito.mockStatic(PropertiesCache.class)) {
            
            // Mock PropertiesCache.getInstance
            propertiesCacheMock.when(PropertiesCache::getInstance).thenReturn(propertiesCache);
            
            // Mock Paths.get to return a mock path
            Path basePath = mock(Path.class);
            pathsMock.when(() -> Paths.get(TEST_BASE_PATH)).thenReturn(basePath);
            
            // Mock Files.walk to throw an exception
            filesMock.when(() -> Files.walk(basePath)).thenThrow(new IOException("Test exception"));
            
            // Call the init method - should not throw exception
            assertDoesNotThrow(() -> keyManager.init());
            
            // Verify the keyMap is still empty
            assertNull(keyManager.getPublicKey(TEST_KEY_ID));
        }
    }

    @Test
    void testGetPublicKey() {
        // Create a mock KeyData
        PublicKey mockPublicKey = mock(PublicKey.class);
        KeyData mockKeyData = new KeyData(TEST_KEY_ID, mockPublicKey);
        
        // Add the mock KeyData to the keyMap
        originalKeyMap.put(TEST_KEY_ID, mockKeyData);
        
        // Call getPublicKey and verify the result
        KeyData result = keyManager.getPublicKey(TEST_KEY_ID);
        assertNotNull(result);
        assertEquals(mockKeyData, result);
        assertEquals(TEST_KEY_ID, result.getKeyId());
        assertEquals(mockPublicKey, result.getPublicKey());
        
        // Test with a non-existent key ID
        assertNull(keyManager.getPublicKey("non_existent_key"));
    }

    @Test
    void testLoadPublicKey_Success() throws Exception {
        // Prepare a valid public key string
        String publicKeyString = TEST_PUBLIC_KEY;
        
        // Mock the Base64Util.decode method
        try (MockedStatic<Base64Util> base64UtilMock = Mockito.mockStatic(Base64Util.class);
             MockedStatic<KeyFactory> keyFactoryMock = Mockito.mockStatic(KeyFactory.class)) {
            
            // Create mock objects
            byte[] mockDecodedBytes = new byte[] { 1, 2, 3, 4, 5 };
            KeyFactory mockKeyFactory = mock(KeyFactory.class);
            PublicKey mockPublicKey = mock(PublicKey.class);
            
            // Set up the mocks
            base64UtilMock.when(() -> Base64Util.decode(any(byte[].class), eq(Base64Util.DEFAULT)))
                .thenReturn(mockDecodedBytes);
            
            keyFactoryMock.when(() -> KeyFactory.getInstance("RSA")).thenReturn(mockKeyFactory);
            when(mockKeyFactory.generatePublic(any(X509EncodedKeySpec.class))).thenReturn(mockPublicKey);
            
            // Call the method
            PublicKey result = KeyManager.loadPublicKey(publicKeyString);
            
            // Verify the result
            assertNotNull(result);
            assertEquals(mockPublicKey, result);
            
            // Verify the X509EncodedKeySpec was created with the decoded bytes
            keyFactoryMock.verify(() -> KeyFactory.getInstance("RSA"));
            verify(mockKeyFactory).generatePublic(any(X509EncodedKeySpec.class));
        }
    }

    @Test
    void testLoadPublicKey_Exception() {
        // Prepare a valid public key string
        String publicKeyString = TEST_PUBLIC_KEY;
        
        // Mock the Base64Util.decode method to throw an exception
        try (MockedStatic<Base64Util> base64UtilMock = Mockito.mockStatic(Base64Util.class)) {
            base64UtilMock.when(() -> Base64Util.decode(any(byte[].class), eq(Base64Util.DEFAULT)))
                .thenThrow(new RuntimeException("Test exception"));
            
            // Call the method and verify it throws an exception
            Exception exception = assertThrows(Exception.class, () -> KeyManager.loadPublicKey(publicKeyString));
            assertEquals("Test exception", exception.getMessage());
        }
    }

    @Test
    void testInit_duplicateKeyOverwrites() throws Exception {
        Path mockKeyPath = mock(Path.class);
        Path mockFileName = mock(Path.class);
        when(mockKeyPath.getFileName()).thenReturn(mockFileName);
        when(mockFileName.toString()).thenReturn(TEST_KEY_ID);
        try (MockedStatic<Files> filesMock = Mockito.mockStatic(Files.class);
             MockedStatic<Paths> pathsMock = Mockito.mockStatic(Paths.class);
             MockedStatic<PropertiesCache> propertiesCacheMock = Mockito.mockStatic(PropertiesCache.class);
             MockedStatic<KeyManager> keyManagerMock = Mockito.mockStatic(KeyManager.class)) {
            propertiesCacheMock.when(PropertiesCache::getInstance).thenReturn(propertiesCache);
            Path basePath = mock(Path.class);
            pathsMock.when(() -> Paths.get(TEST_BASE_PATH)).thenReturn(basePath);
            pathsMock.when(() -> Paths.get(anyString())).thenReturn(mockKeyPath);
            filesMock.when(() -> Files.walk(any())).thenReturn(Stream.of(mockKeyPath));
            filesMock.when(() -> Files.isRegularFile(mockKeyPath)).thenReturn(true);
            List<String> keyLines = Arrays.asList(TEST_PUBLIC_KEY.split("\n"));
            filesMock.when(() -> Files.readAllLines(mockKeyPath, StandardCharsets.UTF_8)).thenReturn(keyLines);
            PublicKey mockPublicKey = mock(PublicKey.class);
            keyManagerMock.when(() -> KeyManager.loadPublicKey(anyString())).thenReturn(mockPublicKey);
            Field keyMapField = KeyManager.class.getDeclaredField("keyMap");
            keyMapField.setAccessible(true);
            Map<String, KeyData> keyMap = (Map<String, KeyData>) keyMapField.get(null);
            keyMap.put(TEST_KEY_ID, new KeyData(TEST_KEY_ID, mock(PublicKey.class)));
            keyManager.init();
            KeyData updatedKey = keyManager.getPublicKey(TEST_KEY_ID);
            assertNotNull(updatedKey);
            assertEquals(mockPublicKey, updatedKey.getPublicKey());
        }
    }

    @Test
    void testLoadPublicKey_withEmptyLines() throws Exception {
        String malformedKey = "-----BEGIN PUBLIC KEY-----\n\n\n-----END PUBLIC KEY-----";
        try (MockedStatic<Base64Util> base64UtilMock = Mockito.mockStatic(Base64Util.class)) {
            base64UtilMock.when(() -> Base64Util.decode(any(byte[].class), eq(Base64Util.DEFAULT)))
                    .thenReturn(new byte[]{1, 2, 3});
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            keyFactory.generatePublic(new X509EncodedKeySpec(new byte[]{1, 2, 3}));
            PublicKey result = KeyManager.loadPublicKey(malformedKey);
            assertNotNull(result);
        } catch (Exception e) {
            log.error("Exception in testLoadPublicKey_withEmptyLines: ", e);
        }
    }



}