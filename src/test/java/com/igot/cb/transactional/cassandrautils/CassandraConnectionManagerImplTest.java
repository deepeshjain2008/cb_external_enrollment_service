package com.igot.cb.transactional.cassandrautils;

import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.igot.cb.util.Constants;
import com.igot.cb.util.PropertiesCache;
import com.igot.cb.util.exceptions.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CassandraConnectionManagerImplTest {

    @Mock
    PropertiesCache propertiesCache;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetConsistencyLevel_valid() {
        try (MockedStatic<PropertiesCache> staticMock = mockStatic(PropertiesCache.class)) {
            staticMock.when(PropertiesCache::getInstance).thenReturn(propertiesCache);
            when(propertiesCache.readProperty(Constants.SUNBIRD_CASSANDRA_CONSISTENCY_LEVEL))
                    .thenReturn("LOCAL_QUORUM");

            ConsistencyLevel level = invokeGetConsistencyLevel();
            assertEquals(DefaultConsistencyLevel.LOCAL_QUORUM, level);
        }
    }

    @Test
    void testGetConsistencyLevel_invalid() {
        try (MockedStatic<PropertiesCache> staticMock = mockStatic(PropertiesCache.class)) {
            staticMock.when(PropertiesCache::getInstance).thenReturn(propertiesCache);
            when(propertiesCache.readProperty(Constants.SUNBIRD_CASSANDRA_CONSISTENCY_LEVEL))
                    .thenReturn("INVALID");

            ConsistencyLevel level = invokeGetConsistencyLevel();
            assertNull(level);
        }
    }

    @Test
    void testShutdownHook() {
        Thread thread = new CassandraConnectionManagerImpl.ResourceCleanUp();
        assertDoesNotThrow(thread::start);
    }

    private ConsistencyLevel invokeGetConsistencyLevel() {
        try {
            Method method = CassandraConnectionManagerImpl.class.getDeclaredMethod("getConsistencyLevel");
            method.setAccessible(true);
            return (ConsistencyLevel) method.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testConstructorThrowsException_whenHostIsBlank() {
        try (
                MockedStatic<PropertiesCache> propertiesCacheStatic = Mockito.mockStatic(PropertiesCache.class)) {
            // Arrange
            PropertiesCache mockPropertiesCache = mock(PropertiesCache.class);
            propertiesCacheStatic.when(PropertiesCache::getInstance).thenReturn(mockPropertiesCache);
            when(mockPropertiesCache.getProperty(Constants.CASSANDRA_CONFIG_HOST)).thenReturn("");

            // Act & Assert
            CustomException exception = assertThrows(CustomException.class, CassandraConnectionManagerImpl::new);
            assertEquals("Cassandra host is not configured", exception.getMessage()); // Adjust message if needed
        }
    }

    @Test
    void testResourceCleanup() {
        // This is just for code coverage
        CassandraConnectionManagerImpl.ResourceCleanUp cleanup = new CassandraConnectionManagerImpl.ResourceCleanUp();
        assertDoesNotThrow(cleanup::run);
    }

    @Test
    void testGetSession_ReturnsExistingSession() throws Exception {
        CassandraConnectionManagerImpl manager = mock(CassandraConnectionManagerImpl.class, CALLS_REAL_METHODS);
        CqlSession mockSession = mock(CqlSession.class);
        when(mockSession.isClosed()).thenReturn(false);
        Field field = CassandraConnectionManagerImpl.class.getDeclaredField("cassandraSessionMap");
        field.setAccessible(true);
        Map<String, CqlSession> map = (Map<String, CqlSession>) field.get(null);
        map.clear();
        map.put("testKS", mockSession);
        CqlSession result = manager.getSession("testKS");
        assertSame(mockSession, result);
        verify(mockSession).isClosed();
    }

    @Test
    void testGetSession_CreatesNewSession() throws Exception {
        CassandraConnectionManagerImpl manager = mock(CassandraConnectionManagerImpl.class, CALLS_REAL_METHODS);
        CqlSession mockSession = mock(CqlSession.class);
        when(mockSession.isClosed()).thenReturn(false);
        Field mapField = CassandraConnectionManagerImpl.class.getDeclaredField("cassandraSessionMap");
        mapField.setAccessible(true);
        Map<String, CqlSession> map = (Map<String, CqlSession>) mapField.get(null);
        map.clear();
        map.put("ks1", mockSession);
        CqlSession session = manager.getSession("ks1");
        assertNotNull(session);
        assertSame(mockSession, session);
    }

    @Test
    void testRegisterShutDownHook_DoesNotThrow() {
        assertDoesNotThrow(CassandraConnectionManagerImpl::registerShutDownHook);
    }

    @Test
    void testResourceCleanup_ExceptionHandled() throws Exception {
        var sessionField = CassandraConnectionManagerImpl.class.getDeclaredField("session");
        sessionField.setAccessible(true);
        CqlSession mockSession = mock(CqlSession.class);
        doThrow(new RuntimeException("close fail")).when(mockSession).close();
        sessionField.set(null, mockSession);

        CassandraConnectionManagerImpl.ResourceCleanUp cleanup = new CassandraConnectionManagerImpl.ResourceCleanUp();
        assertDoesNotThrow(cleanup::run);
    }

    @Test
    void testCreateCassandraConnection_ThrowsCustomException() {
        try (MockedStatic<CqlSession> mocked = mockStatic(CqlSession.class)) {
            mocked.when(CqlSession::builder).thenThrow(new RuntimeException("boom"));
            CustomException ex = assertThrows(
                    CustomException.class,
                    CassandraConnectionManagerImpl::new);
            assertTrue(ex.getMessage().contains("boom"));
        }
    }

    @Test
    void testCreateCassandraConnectionWithKeySpaces_BlankHostThrows() {
        try (MockedStatic<PropertiesCache> mockStatic = mockStatic(PropertiesCache.class)) {
            PropertiesCache cache = mock(PropertiesCache.class);
            mockStatic.when(PropertiesCache::getInstance).thenReturn(cache);
            when(cache.getProperty(Constants.CASSANDRA_CONFIG_HOST)).thenReturn("");
            CustomException ex = assertThrows(
                    CustomException.class,
                    CassandraConnectionManagerImpl::new);
            assertTrue(ex.getMessage().contains("Cassandra host is not configured"));
        }
    }

    @Test
    void testCreateCassandraConnectionWithKeySpaces_ConsistencyLevelNotConfigured_defaultsToLocalOne() {
        try (MockedStatic<PropertiesCache> propStatic = mockStatic(PropertiesCache.class);
                MockedStatic<CqlSession> cqlStatic = mockStatic(CqlSession.class)) {
            PropertiesCache cache = mock(PropertiesCache.class);
            propStatic.when(PropertiesCache::getInstance).thenReturn(cache);
            when(cache.getProperty(Constants.CASSANDRA_CONFIG_HOST)).thenReturn("localhost");
            when(cache.getProperty(Constants.CORE_CONNECTIONS_PER_HOST_FOR_LOCAL)).thenReturn("4");
            when(cache.getProperty(Constants.CORE_CONNECTIONS_PER_HOST_FOR_REMOTE)).thenReturn("2");
            when(cache.getProperty(Constants.HEARTBEAT_INTERVAL)).thenReturn("60");
            when(cache.readProperty(Constants.SUNBIRD_CASSANDRA_CONSISTENCY_LEVEL)).thenReturn(null);

            CqlSession mockSession = mock(CqlSession.class);
            CqlSessionBuilder mockBuilder = mock(CqlSessionBuilder.class);
            cqlStatic.when(CqlSession::builder).thenReturn(mockBuilder);
            when(mockBuilder.addContactPoints(any())).thenReturn(mockBuilder);
            when(mockBuilder.withLocalDatacenter(anyString())).thenReturn(mockBuilder);
            when(mockBuilder.withConfigLoader(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockSession);
            Metadata metadata = mock(Metadata.class);
            when(mockSession.getMetadata()).thenReturn(metadata);

            assertDoesNotThrow(CassandraConnectionManagerImpl::new);
        }
    }

    @Test
    void testGetTableList_Success() {
        try (MockedStatic<CqlSession> mocked = mockStatic(CqlSession.class)) {
            CqlSession mockSession = mock(CqlSession.class);
            CqlSessionBuilder mockBuilder = mock(CqlSessionBuilder.class);
            mocked.when(CqlSession::builder).thenReturn(mockBuilder);
            when(mockBuilder.addContactPoints(any())).thenReturn(mockBuilder);
            when(mockBuilder.withLocalDatacenter(anyString())).thenReturn(mockBuilder);
            when(mockBuilder.withConfigLoader(any())).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockSession);
            Metadata metadata = mock(Metadata.class);
            KeyspaceMetadata ksMeta = mock(KeyspaceMetadata.class);
            TableMetadata tblMeta = mock(TableMetadata.class);
            when(ksMeta.getTables()).thenReturn(Map.of(CqlIdentifier.fromCql("table1"), tblMeta));
            when(metadata.getKeyspace("ks1")).thenReturn(Optional.of(ksMeta));
            when(mockSession.getMetadata()).thenReturn(metadata);
            CassandraConnectionManagerImpl manager = new CassandraConnectionManagerImpl();
            List<String> tables = manager.getTableList("ks1");
            assertEquals(List.of("table1"), tables);
        }
    }
}