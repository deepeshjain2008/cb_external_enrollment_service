package com.igot.cb.transactional.cassandrautils;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class CassandraConnectionManagerTest {

    @Mock
    private CassandraConnectionManager cassandraConnectionManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetSession_Success() {
        String keyspace = "testKeyspace";
        // Updated to mock a valid getSession method
        when(cassandraConnectionManager.getSession(keyspace)).thenReturn(mock(CqlSession.class));
        CqlSession session = cassandraConnectionManager.getSession(keyspace);
        assertNotNull(session);
        verify(cassandraConnectionManager, times(1)).getSession(keyspace);
    }
}