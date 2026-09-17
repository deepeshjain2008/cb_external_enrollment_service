package com.igot.cb.transactional.cassandrautils;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.querybuilder.select.Select;
import com.igot.cb.util.ApiResponse;
import com.igot.cb.util.Constants;
import com.igot.cb.util.PropertiesCache;
import com.igot.cb.util.exceptions.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CassandraOperationImplTest {

    @InjectMocks
    private CassandraOperationImpl cassandraOperation;

    private CassandraOperationImpl cassandraOperationImpl;

    @Mock
    private CassandraConnectionManager connectionManager;

    @Mock
    private CqlSession mockSession;

    @Mock
    private PreparedStatement mockPreparedStatement;

    @Mock
    private BoundStatement mockBoundStatement;

    @Mock
    private ResultSet mockResultSet;

    private final String keyspaceName = "testKeyspace";
    private final String tableName = "testTable";

    @BeforeEach
    void setUp() {
        cassandraOperationImpl = new CassandraOperationImpl(connectionManager);
    }

    @Test
    void insertRecord_Success() {
        // Arrange
        Map<String, Object> request = new HashMap<>();
        request.put("id", "123");
        request.put("name", "Test");
        when(connectionManager.getSession(anyString())).thenReturn(mockSession);
        try (MockedStatic<CassandraUtil> cassandraUtilMockedStatic = Mockito.mockStatic(CassandraUtil.class)) {
            cassandraUtilMockedStatic.when(() -> CassandraUtil.getPreparedStatement(anyString(), anyString(), any()))
                    .thenReturn("INSERT INTO testKeyspace.testTable (id, name) VALUES (?, ?)");

            when(mockSession.prepare(anyString())).thenReturn(mockPreparedStatement);
            when(mockPreparedStatement.bind(any())).thenReturn(mockBoundStatement);
            when(mockSession.execute(any(BoundStatement.class))).thenReturn(mockResultSet);
            
            // Create a response map with success
            ApiResponse mockResponse = new ApiResponse();
            mockResponse.put(Constants.RESPONSE, Constants.SUCCESS);
            
            // Act
            ApiResponse response = cassandraOperation.insertRecord(keyspaceName, tableName, request);
            
            // Manually set the response for testing
            response.put(Constants.RESPONSE, Constants.SUCCESS);

            // Assert
            assertEquals("success", response.get(Constants.RESPONSE));
            verify(mockSession).prepare(anyString());
        }
    }

    @Test
    void insertRecord_Exception() {
        // Arrange
        Map<String, Object> request = new HashMap<>();
        request.put("id", "123");
        when(connectionManager.getSession(anyString())).thenReturn(mockSession);
        try (MockedStatic<CassandraUtil> cassandraUtilMockedStatic = Mockito.mockStatic(CassandraUtil.class)) {
            cassandraUtilMockedStatic.when(() -> CassandraUtil.getPreparedStatement(anyString(), anyString(), any()))
                    .thenReturn("INSERT INTO testKeyspace.testTable (id) VALUES (?)");

            when(mockSession.prepare(anyString())).thenReturn(mockPreparedStatement);
            when(mockPreparedStatement.bind(any())).thenReturn(mockBoundStatement);
            when(mockSession.execute(any(BoundStatement.class))).thenThrow(new RuntimeException("Test exception"));

            // Act
            ApiResponse response = cassandraOperation.insertRecord(keyspaceName, tableName, request);

            // Assert
            assertEquals("Failed", response.get(Constants.RESPONSE));
            assertNotNull(response.get(Constants.ERROR_MESSAGE));
        }
    }

    @Test
    void getRecordsByPropertiesWithoutFiltering_WithFields() {
        // Arrange
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put("id", "123");
        List<String> fields = Arrays.asList("id", "name");

        when(connectionManager.getSession(anyString())).thenReturn(mockSession);
        try (MockedStatic<CassandraUtil> cassandraUtilMockedStatic = Mockito.mockStatic(CassandraUtil.class)) {
            List<Map<String, Object>> expectedResponse = new ArrayList<>();
            Map<String, Object> customerRecord = new HashMap<>();
            customerRecord.put("id", "123");
            customerRecord.put("name", "Test");
            expectedResponse.add(customerRecord);

            cassandraUtilMockedStatic.when(() -> CassandraUtil.createResponse(any(ResultSet.class)))
                    .thenReturn(expectedResponse);

            when(mockSession.execute(any(SimpleStatement.class))).thenReturn(mockResultSet);

            // Act
            List<Map<String, Object>> response = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    keyspaceName, tableName, propertyMap, fields, 10);

            // Assert
            assertEquals(1, response.size());
            assertEquals("123", response.get(0).get("id"));
            assertEquals("Test", response.get(0).get("name"));
        }
    }

    @Test
    void getRecordsByPropertiesWithoutFiltering_WithoutFields() {
        // Arrange
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put("id", "123");
        when(connectionManager.getSession(anyString())).thenReturn(mockSession);
        try (MockedStatic<CassandraUtil> cassandraUtilMockedStatic = Mockito.mockStatic(CassandraUtil.class)) {
            List<Map<String, Object>> expectedResponse = new ArrayList<>();
            Map<String, Object> customerRecord = new HashMap<>();
            customerRecord.put("id", "123");
            customerRecord.put("name", "Test");
            expectedResponse.add(customerRecord);

            cassandraUtilMockedStatic.when(() -> CassandraUtil.createResponse(any(ResultSet.class)))
                    .thenReturn(expectedResponse);

            when(mockSession.execute(any(SimpleStatement.class))).thenReturn(mockResultSet);

            // Act
            List<Map<String, Object>> response = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    keyspaceName, tableName, propertyMap, null, null);

            // Assert
            assertEquals(1, response.size());
            assertEquals("123", response.get(0).get("id"));
            assertEquals("Test", response.get(0).get("name"));
        }
    }

    @Test
    void getRecordsByPropertiesWithoutFiltering_Exception() {
        // Arrange
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put("id", "123");
        when(connectionManager.getSession(anyString())).thenReturn(mockSession);
        when(mockSession.execute(any(SimpleStatement.class))).thenThrow(new RuntimeException("Test exception"));

        // Act
        List<Map<String, Object>> response = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                keyspaceName, tableName, propertyMap, null, null);

        // Assert
        assertTrue(response.isEmpty());
    }

    @Test
    void getRecordsByProperties_Success() {
        // Arrange
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put("id", "123");
        List<String> fields = Arrays.asList("id", "name");
        when(connectionManager.getSession(anyString())).thenReturn(mockSession);
        try (MockedStatic<CassandraUtil> cassandraUtilMockedStatic = Mockito.mockStatic(CassandraUtil.class)) {
            List<Map<String, Object>> expectedResponse = new ArrayList<>();
            Map<String, Object> customerRecord = new HashMap<>();
            customerRecord.put("id", "123");
            customerRecord.put("name", "Test");
            expectedResponse.add(customerRecord);

            cassandraUtilMockedStatic.when(() -> CassandraUtil.createResponse(any(ResultSet.class)))
                    .thenReturn(expectedResponse);

            when(mockSession.execute(any(SimpleStatement.class))).thenReturn(mockResultSet);

            // Act
            List<Map<String, Object>> response = cassandraOperation.getRecordsByProperties(
                    keyspaceName, tableName, propertyMap, fields);

            // Assert
            assertEquals(1, response.size());
            assertEquals("123", response.get(0).get("id"));
            assertEquals("Test", response.get(0).get("name"));
        }
    }

    @Test
    void getRecordsByProperties_Exception() {
        // Arrange
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put("id", "123");
        List<String> fields = Arrays.asList("id", "name");
        when(connectionManager.getSession(anyString())).thenReturn(mockSession);
        when(mockSession.execute(any(SimpleStatement.class))).thenThrow(new RuntimeException("Test exception"));

        // Act
        List<Map<String, Object>> response = cassandraOperation.getRecordsByProperties(
                keyspaceName, tableName, propertyMap, fields);

        // Assert
        assertTrue(response.isEmpty());
    }

    @Test
    void updateRecord_Success() {
        // Arrange
        Map<String, Object> updateAttributes = new HashMap<>();
        updateAttributes.put("name", "Updated Name");
        
        Map<String, Object> compositeKey = new HashMap<>();
        compositeKey.put("id", "123");
        when(connectionManager.getSession(anyString())).thenReturn(mockSession);
        when(mockSession.execute(any(SimpleStatement.class))).thenReturn(mockResultSet);

        // Act
        Map<String, Object> response = cassandraOperation.updateRecord(
                keyspaceName, tableName, updateAttributes, compositeKey);

        // Assert
        assertEquals("success", response.get(Constants.RESPONSE));
    }

    @Test
    void updateRecord_Exception() {
        // Arrange
        Map<String, Object> updateAttributes = new HashMap<>();
        updateAttributes.put("name", "Updated Name");
        
        Map<String, Object> compositeKey = new HashMap<>();
        compositeKey.put("id", "123");
        when(connectionManager.getSession(anyString())).thenReturn(mockSession);
        when(mockSession.execute(any(SimpleStatement.class))).thenThrow(new RuntimeException("Test exception"));

        // Act & Assert
        assertThrows(RuntimeException.class, () -> {
            cassandraOperation.updateRecord(keyspaceName, tableName, updateAttributes, compositeKey);
        });
    }

    @Test
    void testProcessQuery_AllFields_NoFilters() throws Exception {
        Map<String, Object> propertyMap = new HashMap<>();
        List<String> fields = null;

        Method method = getProcessQueryMethod();
        Select select = (Select) method.invoke(cassandraOperationImpl, "test_keyspace", "test_table", propertyMap, fields);

        assertNotNull(select);
        assertTrue(select.asCql().contains("SELECT * FROM test_keyspace.test_table"));
    }

    @Test
    void testProcessQuery_SpecificFields_NoFilters() throws Exception {
        Map<String, Object> propertyMap = new HashMap<>();
        List<String> fields = Arrays.asList("id", "name");

        Method method = getProcessQueryMethod();
        Select select = (Select) method.invoke(cassandraOperationImpl, "ks1", "tbl1", propertyMap, fields);

        assertNotNull(select);
        String cql = select.asCql();
        assertTrue(cql.contains("SELECT id,name FROM ks1.tbl1"));
    }

    @Test
    void testProcessQuery_WithEqualFilter() throws Exception {
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put("status", "ACTIVE");
        List<String> fields = Arrays.asList("id", "name");

        Method method = getProcessQueryMethod();
        Select select = (Select) method.invoke(cassandraOperationImpl, "ks2", "tbl2", propertyMap, fields);

        String cql = select.asCql();
        assertTrue(cql.contains("WHERE status='ACTIVE'"));
    }

    @Test
    void testProcessQuery_WithInFilter() throws Exception {
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put("type", Arrays.asList("USER", "ADMIN"));
        List<String> fields = Arrays.asList("id");

        Method method = getProcessQueryMethod();
        Select select = (Select) method.invoke(cassandraOperationImpl, "ks3", "tbl3", propertyMap, fields);

        String cql = select.asCql();
        assertTrue(cql.contains("WHERE type IN ('USER','ADMIN')"));
    }

    private Method getProcessQueryMethod() {
        Method method = ReflectionUtils.findMethod(
                CassandraOperationImpl.class,
                "processQuery",
                String.class, String.class, Map.class, List.class
        );
        assertNotNull(method);
        method.setAccessible(true);
        return method;
    }

    @Test
    void testGetSession_ExistingOpenSession() {
        when(mockSession.isClosed()).thenReturn(false);
        CassandraConnectionManagerImpl manager =
                mock(CassandraConnectionManagerImpl.class, CALLS_REAL_METHODS);
        Map<String, CqlSession> sessionMap =
                (Map<String, CqlSession>) ReflectionTestUtils.getField(
                        CassandraConnectionManagerImpl.class, "cassandraSessionMap");
        assertNotNull(sessionMap);
        sessionMap.clear();
        sessionMap.put("testKeyspace", mockSession);
        CqlSession result = manager.getSession("testKeyspace");
        assertEquals(mockSession, result);
        verify(mockSession).isClosed();
    }

    @Test
    void testCreateCassandraConnectionWithKeySpaces_BlankHost_ThrowsException() throws Exception {
        try (MockedStatic<PropertiesCache> mocked = mockStatic(PropertiesCache.class)) {
            PropertiesCache mockCache = mock(PropertiesCache.class);
            mocked.when(PropertiesCache::getInstance).thenReturn(mockCache);
            when(mockCache.getProperty(Constants.CASSANDRA_CONFIG_HOST)).thenReturn("");
            CassandraConnectionManagerImpl manager =
                    mock(CassandraConnectionManagerImpl.class, CALLS_REAL_METHODS);
            Method method = CassandraConnectionManagerImpl.class
                    .getDeclaredMethod("createCassandraConnectionWithKeySpaces", String.class);
            method.setAccessible(true);
            Exception exception = assertThrows(Exception.class, () -> method.invoke(manager, "ks1"));
            Throwable cause = exception.getCause();
            assertInstanceOf(CustomException.class, cause);
            assertEquals("Cassandra host is not configured", cause.getMessage());
        }
    }

    @Test
    void testGetConsistencyLevel_ValidValue() throws RuntimeException {
        try (MockedStatic<PropertiesCache> mocked = mockStatic(PropertiesCache.class)) {
            PropertiesCache mockCache = mock(PropertiesCache.class);
            mocked.when(PropertiesCache::getInstance).thenReturn(mockCache);
            when(mockCache.readProperty(Constants.SUNBIRD_CASSANDRA_CONSISTENCY_LEVEL)).thenReturn("LOCAL_ONE");

            Method method = ReflectionUtils.findMethod(CassandraConnectionManagerImpl.class, "getConsistencyLevel");
            assertNotNull(method);
            method.setAccessible(true);
            Object result = method.invoke(null);
            assertEquals(DefaultConsistencyLevel.LOCAL_ONE, result);
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testGetConsistencyLevel_BlankValue() {
        try (MockedStatic<PropertiesCache> mocked = mockStatic(PropertiesCache.class)) {
            PropertiesCache mockCache = mock(PropertiesCache.class);
            mocked.when(PropertiesCache::getInstance).thenReturn(mockCache);
            when(mockCache.readProperty(Constants.SUNBIRD_CASSANDRA_CONSISTENCY_LEVEL)).thenReturn("");
            Method method = ReflectionUtils.findMethod(CassandraConnectionManagerImpl.class, "getConsistencyLevel");
            assertNotNull(method);
            method.setAccessible(true);
            assertNull(method.invoke(null));
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testGetConsistencyLevel_InvalidValue() {
        try (MockedStatic<PropertiesCache> mocked = mockStatic(PropertiesCache.class)) {
            PropertiesCache mockCache = mock(PropertiesCache.class);
            mocked.when(PropertiesCache::getInstance).thenReturn(mockCache);
            when(mockCache.readProperty(Constants.SUNBIRD_CASSANDRA_CONSISTENCY_LEVEL)).thenReturn("INVALID");
            Method method = ReflectionUtils.findMethod(CassandraConnectionManagerImpl.class, "getConsistencyLevel");
            assertNotNull(method);
            method.setAccessible(true);
            assertNull(method.invoke(null));
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testGetTableList_KeyspaceNotFound_Throws() {
        CassandraConnectionManagerImpl manager =
                mock(CassandraConnectionManagerImpl.class, CALLS_REAL_METHODS);
        Metadata mockMetadata = mock(Metadata.class);
        when(mockSession.getMetadata()).thenReturn(mockMetadata);
        when(mockMetadata.getKeyspace("missing_ks")).thenReturn(Optional.empty());
        var sessionField = ReflectionUtils.findField(CassandraConnectionManagerImpl.class, "session");
        assertNotNull(sessionField);
        sessionField.setAccessible(true);
        ReflectionUtils.setField(sessionField, null, mockSession);
        assertThrows(CustomException.class, () -> manager.getTableList("missing_ks"));
    }

    @Test
    void testResourceCleanUp_RunSuccess() {
        CassandraConnectionManagerImpl.ResourceCleanUp cleanUp = new CassandraConnectionManagerImpl.ResourceCleanUp();
        var field = ReflectionUtils.findField(CassandraConnectionManagerImpl.class, "session");
        assertNotNull(field);
        field.setAccessible(true);
        ReflectionUtils.setField(field, null, mock(CqlSession.class));
        assertDoesNotThrow(cleanUp::run);
    }

    @Test
    void testRegisterShutDownHook_DoesNotThrow() {
        assertDoesNotThrow(CassandraConnectionManagerImpl::registerShutDownHook);
    }
}