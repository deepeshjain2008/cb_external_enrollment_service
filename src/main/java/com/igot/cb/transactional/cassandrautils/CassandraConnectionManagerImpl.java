package com.igot.cb.transactional.cassandrautils;


import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.internal.core.retry.DefaultRetryPolicy;
import com.datastax.oss.driver.internal.core.time.AtomicTimestampGenerator;
import com.igot.cb.util.Constants;
import com.igot.cb.util.PropertiesCache;
import com.igot.cb.util.exceptions.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


/**
 * @author Mahesh RV
 * @author Ruksana
 * <p>
 * Manages Cassandra connections and sessions.
 */
@Component
@Slf4j
public class CassandraConnectionManagerImpl implements CassandraConnectionManager {

    private static final Map<String, CqlSession> cassandraSessionMap = new ConcurrentHashMap<>(2);
    public static final String DATACENTER_1 = "datacenter1";
    private static CqlSession session;

    @Override
    public CqlSession getSession(String keyspaceName) {
        // Check if session for keyspace already exists
        CqlSession currentSession = cassandraSessionMap.get(keyspaceName);
        if (currentSession != null&& !currentSession.isClosed()) {
            return currentSession;
        } else {
            // Create new session scoped to keyspace using the USE command
            CqlSession newSession = createCassandraConnectionWithKeySpaces(keyspaceName);
            cassandraSessionMap.put(keyspaceName, newSession);
            return newSession;
        }
    }

    public CassandraConnectionManagerImpl() {
        // Initialize the connection and register shutdown hook
        registerShutDownHook();
        createCassandraConnection();
    }

    private void createCassandraConnection() {
        try {
            session = createCassandraConnectionWithKeySpaces(null);
        } catch (Exception e) {
            log.error("Error while creating Cassandra connection", e);
            throw new CustomException(
                    Constants.ERROR,
                    e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private CqlSession createCassandraConnectionWithKeySpaces(String keySpaceName) {
        try {
            // Load the properties required for connection
            PropertiesCache cache = PropertiesCache.getInstance();
            String cassandraHost = cache.getProperty(Constants.CASSANDRA_CONFIG_HOST);
            if (StringUtils.isBlank(cassandraHost)) {
                throw new CustomException(
                        Constants.ERROR,
                        "Cassandra host is not configured",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            List<String> hosts = Arrays.asList(cassandraHost.split(","));
            List<InetSocketAddress> contactPoints = hosts.stream()
                    .map(host -> new InetSocketAddress(host.trim(), 9042)) // Assuming default port 9042
                    .toList();
            List<String> contactPointsString = hosts.stream()
                    .map(host -> host.trim() + ":9042") // Ensure proper host:port format
                    .toList();
            ConsistencyLevel consistencyLevel = getConsistencyLevel();
            String consistencyLevelName = consistencyLevel != null ? consistencyLevel.name() : ConsistencyLevel.LOCAL_ONE.name(); // or default fallback
            DriverConfigLoader loader = DriverConfigLoader.programmaticBuilder()
                    .withStringList(DefaultDriverOption.CONTACT_POINTS, contactPointsString)
                    .withString(DefaultDriverOption.REQUEST_CONSISTENCY, consistencyLevelName)
                    .withString(DefaultDriverOption.LOAD_BALANCING_LOCAL_DATACENTER, DATACENTER_1)
                    .withInt(DefaultDriverOption.CONNECTION_POOL_LOCAL_SIZE,
                            Integer.parseInt(cache.getProperty(Constants.CORE_CONNECTIONS_PER_HOST_FOR_LOCAL)))
                    .withInt(DefaultDriverOption.CONNECTION_POOL_REMOTE_SIZE,
                            Integer.parseInt(cache.getProperty(Constants.CORE_CONNECTIONS_PER_HOST_FOR_REMOTE)))
                    .withInt(DefaultDriverOption.HEARTBEAT_INTERVAL,
                            Integer.parseInt(cache.getProperty(Constants.HEARTBEAT_INTERVAL)))
                    .withInt(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, 10000)
                    .withInt(DefaultDriverOption.REQUEST_TIMEOUT, 10000)
                    .withString(DefaultDriverOption.PROTOCOL_VERSION, ProtocolVersion.V4.toString())
                    .withClass(DefaultDriverOption.RETRY_POLICY_CLASS, DefaultRetryPolicy.class)
                    .withClass(DefaultDriverOption.TIMESTAMP_GENERATOR_CLASS, AtomicTimestampGenerator.class)
                    .build();
            CqlSession sessionWithKeyspaces;
            if (StringUtils.isNotBlank(keySpaceName)) {
                sessionWithKeyspaces = CqlSession.builder()
                        .addContactPoints(contactPoints)
                        .withLocalDatacenter(DATACENTER_1)
                        .withKeyspace(keySpaceName)
                        .withConfigLoader(loader)
                        .build();
            } else {
                sessionWithKeyspaces = CqlSession.builder()
                        .addContactPoints(contactPoints)
                        .withLocalDatacenter(DATACENTER_1)
                        .withConfigLoader(loader)
                        .build();
            }
            log.info("Connected to the keyspaces: " + keySpaceName);
            // Get metadata and log cluster information
            final Metadata metadata = sessionWithKeyspaces.getMetadata();
            log.info(String.format("Connected to cluster: %s", metadata.getClusterName()));
            // Log nodes in the cluster
            for (Node host : metadata.getNodes().values()) {
                log.info(String.format("Datacenter: %s; Host: %s; Rack: %s", host.getDatacenter(), host.getEndPoint(), host.getRack()));
            }
            return sessionWithKeyspaces;
        } catch (Exception e) {
            log.error("Error while creating Cassandra connection", e);
            throw new CustomException(
                    Constants.ERROR,
                    e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static ConsistencyLevel getConsistencyLevel() {
        String consistency = PropertiesCache.getInstance().readProperty(Constants.SUNBIRD_CASSANDRA_CONSISTENCY_LEVEL);

        log.info("CassandraConnectionManagerImpl:getConsistencyLevel: level = " + consistency);

        if (StringUtils.isBlank(consistency)) return null;

        try {
            return DefaultConsistencyLevel.valueOf(consistency.toUpperCase());
        } catch (IllegalArgumentException exception) {
            log.info("CassandraConnectionManagerImpl:getConsistencyLevel: Exception occurred with error message = "
                    + exception.getMessage());
        }
        return null;
    }

    @Override
    public List<String> getTableList(String keyspaceName) {
        try {
            // Fetch the metadata for the keyspace and list tables
            Metadata metadata = session.getMetadata();
            Optional<KeyspaceMetadata> optionalKeyspace = metadata.getKeyspace(keyspaceName);

            if (optionalKeyspace.isPresent()) {
                // Convert the Map<CqlIdentifier, TableMetadata> to a List<String> with table names
                Map<CqlIdentifier, TableMetadata> tables = optionalKeyspace.get().getTables();
                return tables.keySet().stream()
                        .map(CqlIdentifier::toString)
                        .toList();
            } else {
                throw new CustomException(
                        Constants.ERROR,
                        "Keyspace not found: " + keyspaceName,
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } catch (Exception e) {
            log.error("Error fetching tables for keyspace: " + keyspaceName, e);
            throw new CustomException(
                    Constants.ERROR,
                    e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public static void registerShutDownHook() {
        Runtime runtime = Runtime.getRuntime();
        runtime.addShutdownHook(new ResourceCleanUp());
        log.info("Cassandra ShutDownHook registered.");
    }

    // Clean up resources when JVM terminates
    static class ResourceCleanUp extends Thread {
        @Override
        public void run() {
            try {
                log.info("Started resource cleanup for Cassandra.");
                for (Map.Entry<String, CqlSession> entry : cassandraSessionMap.entrySet()) {
                    entry.getValue().close();
                }
                if (session != null) {
                    session.close();
                }
                log.info("Completed resource cleanup for Cassandra.");
            } catch (Exception ex) {
                log.error("Error during resource cleanup", ex);
            }
        }
    }
}