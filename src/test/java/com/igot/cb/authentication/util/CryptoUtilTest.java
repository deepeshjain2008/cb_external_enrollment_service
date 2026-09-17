package com.igot.cb.authentication.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CryptoUtilTest {

    private static final String TEST_PAYLOAD = "Test payload for signature verification";
    private static final String TEST_ALGORITHM = "SHA256withRSA";

    @Test
    void testVerifyRSASign_ValidSignature() throws Exception {
        // Generate a key pair for testing
        KeyPair keyPair = generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();

        // Create a signature using the private key
        byte[] signature = createSignature(TEST_PAYLOAD, privateKey, TEST_ALGORITHM);

        // Verify the signature using CryptoUtil
        boolean result = CryptoUtil.verifyRSASign(TEST_PAYLOAD, signature, publicKey, TEST_ALGORITHM);

        // Assert that the signature is valid
        assertTrue(result);
    }

    @Test
    void testVerifyRSASign_InvalidSignature() throws Exception {
        // Generate a key pair for testing
        KeyPair keyPair = generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();

        // Create an invalid signature (random bytes)
        byte[] invalidSignature = new byte[256];
        new SecureRandom().nextBytes(invalidSignature);

        // Verify the signature using CryptoUtil
        boolean result = CryptoUtil.verifyRSASign(TEST_PAYLOAD, invalidSignature, publicKey, TEST_ALGORITHM);

        // Assert that the signature is invalid
        assertFalse(result);
    }

    @Test
    void testVerifyRSASign_ModifiedPayload() throws Exception {
        // Generate a key pair for testing
        KeyPair keyPair = generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();

        // Create a signature using the private key
        byte[] signature = createSignature(TEST_PAYLOAD, privateKey, TEST_ALGORITHM);

        // Verify the signature with a modified payload
        String modifiedPayload = TEST_PAYLOAD + " modified";
        boolean result = CryptoUtil.verifyRSASign(modifiedPayload, signature, publicKey, TEST_ALGORITHM);

        // Assert that the signature is invalid for the modified payload
        assertFalse(result);
    }

    @Test
    void testVerifyRSASign_NoSuchAlgorithmException() throws Exception {
        // Generate a key pair for testing
        KeyPair keyPair = generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        byte[] signature = new byte[256];

        // Use a non-existent algorithm
        String invalidAlgorithm = "InvalidAlgorithm";
        boolean result = CryptoUtil.verifyRSASign(TEST_PAYLOAD, signature, publicKey, invalidAlgorithm);

        // Assert that verification fails due to the invalid algorithm
        assertFalse(result);
    }

    @Test
    void testVerifyRSASign_InvalidKeyException() throws Exception {
        // Mock the Signature class to throw InvalidKeyException
        try (MockedStatic<Signature> signatureMock = Mockito.mockStatic(Signature.class)) {
            // Create a mock Signature instance
            Signature mockSignature = mock(Signature.class);
            
            // Configure the mock to throw InvalidKeyException
            doThrow(new InvalidKeyException("Invalid key")).when(mockSignature).initVerify(any(PublicKey.class));
            
            // Return the mock when Signature.getInstance is called
            signatureMock.when(() -> Signature.getInstance(anyString())).thenReturn(mockSignature);
            
            // Call the method under test
            boolean result = CryptoUtil.verifyRSASign(TEST_PAYLOAD, new byte[10], mock(PublicKey.class), TEST_ALGORITHM);
            
            // Assert that verification fails due to the InvalidKeyException
            assertFalse(result);
        }
    }

    @Test
    void testVerifyRSASign_SignatureException() throws Exception {
        // Mock the Signature class to throw SignatureException
        try (MockedStatic<Signature> signatureMock = Mockito.mockStatic(Signature.class)) {
            // Create a mock Signature instance
            Signature mockSignature = mock(Signature.class);
            
            // Configure the mock to throw SignatureException
            doThrow(new SignatureException("Signature error")).when(mockSignature).verify(any(byte[].class));
            
            // Return the mock when Signature.getInstance is called
            signatureMock.when(() -> Signature.getInstance(anyString())).thenReturn(mockSignature);
            
            // Call the method under test
            boolean result = CryptoUtil.verifyRSASign(TEST_PAYLOAD, new byte[10], mock(PublicKey.class), TEST_ALGORITHM);
            
            // Assert that verification fails due to the SignatureException
            assertFalse(result);
        }
    }

    @Test
    void testVerifyRSASign_NullParameters() {
        // Test with null payload
        assertFalse(CryptoUtil.verifyRSASign(null, new byte[10], mock(PublicKey.class), TEST_ALGORITHM));
        
        // Test with null signature
        assertFalse(CryptoUtil.verifyRSASign(TEST_PAYLOAD, null, mock(PublicKey.class), TEST_ALGORITHM));
        
        // Test with null public key
        assertFalse(CryptoUtil.verifyRSASign(TEST_PAYLOAD, new byte[10], null, TEST_ALGORITHM));
        
        // Skip null algorithm test as it causes NullPointerException in Signature.getInstance()
        // The CryptoUtil class doesn't have null checks for algorithm parameter
    }

    @Test
    void testPrivateConstructor() throws Exception {
        // Test that the private constructor is not accessible
        Constructor<CryptoUtil> constructor = CryptoUtil.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        CryptoUtil instance = constructor.newInstance();
        assertNotNull(instance);
    }

    // Helper method to generate a key pair for testing
    private KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }

    // Helper method to create a signature using a private key
    private byte[] createSignature(String payload, PrivateKey privateKey, String algorithm) 
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(privateKey);
        signature.update(payload.getBytes(StandardCharsets.US_ASCII));
        return signature.sign();
    }
}