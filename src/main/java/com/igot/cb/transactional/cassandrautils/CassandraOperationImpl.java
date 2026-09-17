package com.igot.cb.transactional.cassandrautils;


import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.querybuilder.QueryBuilder;
import com.datastax.oss.driver.api.querybuilder.relation.Relation;
import com.datastax.oss.driver.api.querybuilder.select.Select;
import com.datastax.oss.driver.api.querybuilder.term.Term;
import com.datastax.oss.driver.api.querybuilder.update.Assignment;
import com.datastax.oss.driver.api.querybuilder.update.Update;
import com.datastax.oss.driver.api.querybuilder.update.UpdateStart;
import com.datastax.oss.driver.api.querybuilder.update.UpdateWithAssignments;
import com.igot.cb.util.ApiResponse;
import com.igot.cb.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * @author Mahesh RV
 * @author Ruksana
 */
@Component
@Slf4j
public class CassandraOperationImpl implements CassandraOperation {

    private final CassandraConnectionManager connectionManager;

    public CassandraOperationImpl(CassandraConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public ApiResponse insertRecord(String keyspaceName, String tableName, Map<String, Object> request) {
        ApiResponse response = new ApiResponse();
        CqlSession session = null;
        try {
            session = connectionManager.getSession(keyspaceName);
            String query = CassandraUtil.getPreparedStatement(keyspaceName, tableName, request);
            PreparedStatement statement = session.prepare(query);
            BoundStatement boundStatement = statement.bind(request.values().toArray());
            session.execute(boundStatement);
            response.put(Constants.RESPONSE, Constants.SUCCESS);
        } catch (Exception e) {
            log.error("Error inserting record into {}: {}", tableName, e.getMessage());
            response.put(Constants.RESPONSE, Constants.FAILED);
            response.put(Constants.ERROR_MESSAGE, e.getMessage());
        }
        return response;
    }

    @Override
    public List<Map<String, Object>> getRecordsByPropertiesWithoutFiltering(String keyspaceName, String tableName, Map<String, Object> propertyMap, List<String> fields, Integer limit) {
        List<Map<String, Object>> response = new ArrayList<>();
        CqlSession session = null;
        try {
            session = connectionManager.getSession(keyspaceName);
            Select selectQuery = null;
            selectQuery = processQuery(keyspaceName, tableName, propertyMap, fields);

            if (limit != null) selectQuery = selectQuery.limit(limit);
            String queryString = selectQuery.toString();
            SimpleStatement statement = SimpleStatement.newInstance(queryString);
            ResultSet results = session.execute(statement);
            response = CassandraUtil.createResponse(results);
        } catch (Exception e) {
            log.error("Error fetching records from {}: {}", tableName, e.getMessage());
        }
        return response;
    }

    @Override
    public List<Map<String, Object>> getRecordsByProperties(String keyspaceName, String tableName, Map<String, Object> propertyMap, List<String> fields) {
        List<Map<String, Object>> response = new ArrayList<>();
        CqlSession session = null;
        try {
            session = connectionManager.getSession(keyspaceName);
            Select selectQuery = null;
            selectQuery = processQuery(keyspaceName, tableName, propertyMap, fields);
            String queryString = selectQuery.toString();
            SimpleStatement statement = SimpleStatement.newInstance(queryString);
            ResultSet results = session.execute(statement);
            response = CassandraUtil.createResponse(results);
        } catch (Exception e) {
            log.error("Error fetching records from {}: {}", tableName, e.getMessage());
        }
        return response;
    }

    @Override
    public Map<String, Object> updateRecord(String keyspaceName, String tableName, Map<String, Object> updateAttributes,
                                            Map<String, Object> compositeKey) {
        Map<String, Object> response = new HashMap<>();
        CqlSession session = null;
        try {
            session = connectionManager.getSession(keyspaceName);
            UpdateStart updateStart = QueryBuilder.update(keyspaceName, tableName);
            UpdateWithAssignments updateWithAssignments = updateStart.set(
                    updateAttributes.entrySet().stream()
                            .map(entry -> Assignment.setColumn(entry.getKey(), QueryBuilder.literal(entry.getValue())))
                            .toArray(Assignment[]::new)
            );
            Update update = updateWithAssignments.where(
                    compositeKey.entrySet().stream()
                            .map(entry -> Relation.column(entry.getKey()).isEqualTo(QueryBuilder.literal(entry.getValue())))
                            .toArray(Relation[]::new)
            );
            session.execute(update.build());
            response.put(Constants.RESPONSE, Constants.SUCCESS);
        } catch (Exception e) {
            String errMsg = String.format("Exception occurred while updating record to %s %s", tableName, e.getMessage());
            log.error(errMsg);
            response.put(Constants.RESPONSE, Constants.FAILED);
            response.put(Constants.ERROR_MESSAGE, errMsg);
            throw e;
        }
        return response;
    }

    private Select processQuery(String keyspaceName, String tableName, Map<String, Object> propertyMap,
                               List<String> fields) {
        Select select;
        if (CollectionUtils.isNotEmpty(fields)) {
            select = QueryBuilder.selectFrom(keyspaceName, tableName).columns(fields);
        } else {

            select = QueryBuilder.selectFrom(keyspaceName, tableName).all();
        }
        if (MapUtils.isEmpty(propertyMap)) {
            return select; // Build and return the query
        }
        for (Map.Entry<String, Object> entry : propertyMap.entrySet()) {
            String columnName = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof List) {
                List<?> valueList = (List<?>) value;
                if (CollectionUtils.isNotEmpty(valueList)) {
                    List<Term> terms = valueList.stream()
                            .map(QueryBuilder::literal)
                            .collect(Collectors.toList());
                    select = select.whereColumn(columnName).in(terms);
                }
            } else {
                select = select.whereColumn(columnName).isEqualTo(QueryBuilder.literal(value));
            }
        }
        return select;
    }
}
