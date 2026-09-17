package com.igot.cb.authentication.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.authentication.model.KeyData;
import com.igot.cb.util.Constants;
import com.igot.cb.util.PropertiesCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.common.util.Time;
import org.keycloak.crypto.KeyWrapper;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.PublicKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessTokenValidatorTest {

    @Mock
    private KeyManager keyManager;

    @Mock
    private KeyWrapper mockKeyWrapper;

    @Mock
    private PublicKey mockPublicKey;

    private AccessTokenValidator accessTokenValidator;

    private AccessTokenValidator spyAccessTokenValidator;

    private static final ObjectMapper mapper = new ObjectMapper();

    private String expiredToken;
    private String invalidSignatureToken;
    private String invalidIssuerToken;

    @BeforeEach
    void setUp() throws Exception {
        accessTokenValidator = new AccessTokenValidator(keyManager);
        spyAccessTokenValidator = spy(new AccessTokenValidator(keyManager));
        expiredToken = generateToken("expiredUserId", Time.currentTime() - 1000, "expectedIssuer");
        invalidSignatureToken = generateToken("invalidSignatureUserId", Time.currentTime() + 1000, "expectedIssuer");
        invalidIssuerToken = generateToken("invalidIssuerUserId", Time.currentTime() + 1000, "invalidIssuer");

    }

    @Test
    void testVerifyUserToken_ExpiredToken() {
        String userId = accessTokenValidator.verifyUserToken(expiredToken);
        assertEquals(Constants.UNAUTHORIZED, userId);
    }

    @Test
    void testVerifyUserToken_InvalidSignature() {
        String userId = accessTokenValidator.verifyUserToken(invalidSignatureToken);
        assertEquals(Constants.UNAUTHORIZED, userId);
    }

    @Test
    void testVerifyUserToken_InvalidIssuer() {
        String userId = accessTokenValidator.verifyUserToken(invalidIssuerToken);
        assertEquals(Constants.UNAUTHORIZED, userId);
    }

    @Test
    void testFetchUserIdFromAccessToken_NullToken() {
        String userId = accessTokenValidator.fetchUserIdFromAccessToken(null);
        assertNull(userId);
    }

    private String generateToken(String userId, int exp, String issuer) throws Exception {
        Map<String, Object> header = new HashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", "testKeyId");
        Map<String, Object> body = new HashMap<>();
        body.put("sub", "user:" + userId);
        body.put("exp", exp);
        body.put("iss", issuer);
        String headerJson = mapper.writeValueAsString(header);
        String bodyJson = mapper.writeValueAsString(body);
        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes());
        String encodedBody = Base64.getUrlEncoder().withoutPadding().encodeToString(bodyJson.getBytes());
        String unsignedToken = encodedHeader + "." + encodedBody;
        String signature = "testSignature";
        return unsignedToken + "." + signature;
    }

    @Test
    void fetchUserIdFromAccessToken_validToken_returnsUserId() {
        String accessToken = "validToken";
        String expectedUserId = "user123";
        doReturn(expectedUserId).when(spyAccessTokenValidator).verifyUserToken(accessToken);
        String actualUserId = spyAccessTokenValidator.fetchUserIdFromAccessToken(accessToken);
        assertEquals(expectedUserId, actualUserId);
    }

    @Test
    void fetchUserIdFromAccessToken_unauthorizedToken_returnsNull() {
        String accessToken = "unauthorizedToken";
        doReturn("UNAUTHORIZED").when(spyAccessTokenValidator).verifyUserToken(accessToken);
        String actualUserId = spyAccessTokenValidator.fetchUserIdFromAccessToken(accessToken);
        assertNull(actualUserId);
    }

    @Test
    void fetchUserIdFromAccessToken_nullToken_returnsNull() {
        String actualUserId = spyAccessTokenValidator.fetchUserIdFromAccessToken(null);
        assertNull(actualUserId);
    }

    @Test
    void fetchUserIdFromAccessToken_exceptionThrown_returnsNull() {
        String accessToken = "token";
        doThrow(new RuntimeException("some error")).when(spyAccessTokenValidator).verifyUserToken(accessToken);
        String actualUserId = spyAccessTokenValidator.fetchUserIdFromAccessToken(accessToken);
        assertNull(actualUserId);
    }

    @Test
    void validateToken_invalidTokenFormat_returnsNullUserId() {
        String userId = accessTokenValidator.fetchUserIdFromAccessToken("invalid.token");
        assertNull(userId);
    }

    @Test
    void validateToken_invalidBase64Header_returnsEmptyMap() {
        String token = "invalidHeader.payload.signature";
        try (MockedStatic<Base64Util> mockedStatic = mockStatic(Base64Util.class)) {
            mockedStatic.when(() -> Base64Util.decode(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("decode error"));
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(token);
            assertNull(userId);
        }
    }

    @Test
    void verifyUserToken_invalidSignature_returnsUnauthorized() {
        try (MockedStatic<Base64Util> base64Mock = mockStatic(Base64Util.class);
             MockedStatic<CryptoUtil> cryptoMock = mockStatic(CryptoUtil.class)) {
            base64Mock.when(() -> Base64Util.decode(anyString(), anyInt()))
                    .thenReturn("{\"kid\":\"testKey\"}".getBytes()); // fake header
            cryptoMock.when(() -> CryptoUtil.verifyRSASign(
                            anyString(), any(), any(), anyString()))
                    .thenReturn(false);
            String result = accessTokenValidator.verifyUserToken("invalid.signature.token");
            assertEquals(Constants.UNAUTHORIZED, result);
        }
    }

    @Test
    void validateToken_validToken_returnsUserId() throws Exception {
        String userId = "testUserId";
        int exp = Time.currentTime() + 1000;
        String issuer = PropertiesCache.getInstance().getProperty(Constants.SSO_URL) + "realms/" + PropertiesCache.getInstance().getProperty(Constants.SSO_REALM);
        String token = generateToken(userId, exp, issuer);
        try (MockedStatic<Base64Util> base64Mock = mockStatic(Base64Util.class);
             MockedStatic<CryptoUtil> cryptoMock = mockStatic(CryptoUtil.class)) {
            String headerJson = "{\"kid\":\"testKeyId\"}";
            String bodyJson = String.format("{\"sub\":\"user:%s\",\"exp\":%d,\"iss\":\"%s\"}", userId, exp, issuer);
            base64Mock.when(() -> Base64Util.decode(anyString(), eq(11)))
                    .thenReturn(headerJson.getBytes())
                    .thenReturn(bodyJson.getBytes());
            KeyData mockKeyData = mock(KeyData.class);
            when(keyManager.getPublicKey("testKeyId")).thenReturn(mockKeyData);
            when(mockKeyData.getPublicKey()).thenReturn(mockPublicKey);
            cryptoMock.when(() -> CryptoUtil.verifyRSASign(anyString(), any(), any(), eq(Constants.SHA_256_WITH_RSA)))
                    .thenReturn(true);
            String actualUserId = accessTokenValidator.fetchUserIdFromAccessToken(token);
            assertEquals(userId, actualUserId);
        }
    }

    @Test
    void validateToken_validSignatureButExpired_returnsUnauthorized() throws Exception {
        String userId = "expiredUser";
        int exp = Time.currentTime() - 1000;
        String issuer = PropertiesCache.getInstance().getProperty(Constants.SSO_URL) + "realms/" + PropertiesCache.getInstance().getProperty(Constants.SSO_REALM);
        String token = generateToken(userId, exp, issuer);
        try (MockedStatic<Base64Util> base64Mock = mockStatic(Base64Util.class);
             MockedStatic<CryptoUtil> cryptoMock = mockStatic(CryptoUtil.class)) {
            String headerJson = "{\"kid\":\"testKeyId\"}";
            String bodyJson = String.format("{\"sub\":\"user:%s\",\"exp\":%d,\"iss\":\"%s\"}", userId, exp, issuer);
            base64Mock.when(() -> Base64Util.decode(anyString(), eq(11)))
                    .thenReturn(headerJson.getBytes())
                    .thenReturn(bodyJson.getBytes());
            KeyData mockKeyData = mock(KeyData.class);
            when(keyManager.getPublicKey("testKeyId")).thenReturn(mockKeyData);
            when(mockKeyData.getPublicKey()).thenReturn(mockPublicKey);
            cryptoMock.when(() -> CryptoUtil.verifyRSASign(anyString(), any(), any(), eq(Constants.SHA_256_WITH_RSA)))
                    .thenReturn(true);
            String result = accessTokenValidator.fetchUserIdFromAccessToken(token);
            assertNull(result);
        }
    }

    @Test
    void validateToken_invalidJsonHeader_returnsNull() {
        String token = "invalidHeader.payload.signature";
        try (MockedStatic<Base64Util> base64Mock = mockStatic(Base64Util.class)) {
            base64Mock.when(() -> Base64Util.decode(anyString(), eq(11)))
                    .thenReturn("invalid-json".getBytes());
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(token);
            assertNull(userId);
        }
    }

    @Test
    void validateToken_validSignatureButInvalidIssuer_returnsUnauthorized() throws Exception {
        String userId = "userWithBadIssuer";
        int exp = Time.currentTime() + 1000;
        String badIssuer = "https://invalid.issuer.com";
        String token = generateToken(userId, exp, badIssuer);
        try (MockedStatic<Base64Util> base64Mock = mockStatic(Base64Util.class);
             MockedStatic<CryptoUtil> cryptoMock = mockStatic(CryptoUtil.class)) {
            String headerJson = "{\"kid\":\"testKeyId\"}";
            String bodyJson = String.format("{\"sub\":\"user:%s\",\"exp\":%d,\"iss\":\"%s\"}", userId, exp, badIssuer);
            base64Mock.when(() -> Base64Util.decode(anyString(), eq(11)))
                    .thenReturn(headerJson.getBytes())
                    .thenReturn(bodyJson.getBytes());
            KeyData mockKeyData = mock(KeyData.class);
            when(keyManager.getPublicKey("testKeyId")).thenReturn(mockKeyData);
            when(mockKeyData.getPublicKey()).thenReturn(mockPublicKey);
            cryptoMock.when(() -> CryptoUtil.verifyRSASign(anyString(), any(), any(), eq(Constants.SHA_256_WITH_RSA)))
                    .thenReturn(true);
            String result = accessTokenValidator.fetchUserIdFromAccessToken(token);
            assertNull(result);
        }
    }
}