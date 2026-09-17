package com.igot.cb.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.producer.Producer;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import com.igot.cb.util.CbServerProperties;
import com.igot.cb.util.Constants;
import com.igot.cb.util.TransformUtility;
import com.igot.cb.util.cache.CacheService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerTest {

    @InjectMocks
    private KafkaConsumer kafkaConsumer;

    @Spy
    private ObjectMapper mapper = new ObjectMapper();

    @Mock
    private CassandraOperation cassandraOperation;

    @Mock
    private Producer producer;

    @Mock
    private CbServerProperties cbServerProperties;

    @Mock
    private TransformUtility transformUtility;

    @Mock
    private ResourceLoader resourceLoader;

    @Mock
    private Resource mockResource;

    @Mock
    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(kafkaConsumer, "mapper", mapper);
        lenient().when(cbServerProperties.getCertificateCharLength()).thenReturn(30);
        lenient().when(cbServerProperties.getCertificateTopic()).thenReturn("certTopic");
    }

    @Test
    void enrollUpdateConsumer_Success() throws Exception {
        // Arrange
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put(Constants.USER_ID, "user123@domain.com");
        payloadMap.put("courseid", "course123");
        payloadMap.put("partnerId", "partner123");
        payloadMap.put("completedon", "01/01/2023");
        String payload = mapper.writeValueAsString(payloadMap);
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("topic", 0, 0L, "key", payload);

        JsonNode contentNode = mapper.createObjectNode();
        ((ObjectNode) contentNode).put("contentId", "course123");
        ((ObjectNode) contentNode).put("name", "Course Name");
        ((ObjectNode) contentNode).put("appIcon", "http://example.com/icon.png");

        JsonNode contentPartnerNode = mapper.createObjectNode();
        ((ObjectNode) contentPartnerNode).put("contentPartnerName", "Partner Name");
        ((ObjectNode) contentPartnerNode).put("id", "partner123");
        ((ObjectNode) contentNode).set("contentPartner", contentPartnerNode);

        JsonNode result = mapper.createObjectNode();
        ((ObjectNode) result).set("content", contentNode);

        when(transformUtility.callCiosReadAPi(anyString(), anyString())).thenReturn(result);

        List<Map<String, Object>> existingRecords = new ArrayList<>();
        Map<String, Object> existingRecord = new HashMap<>();
        existingRecords.add(existingRecord);

        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_USER_EXTERNAL_ENROLMENTS),
                any(),
                isNull(),
                eq(1))).thenReturn(existingRecords);

        when(cassandraOperation.updateRecord(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_USER_EXTERNAL_ENROLMENTS),
                any(),
                any())).thenReturn(new HashMap<>());

        JsonNode partnerApiResponse = mapper.createObjectNode();
        ((ObjectNode) partnerApiResponse).put("certificateTemplateUrl", "http://example.com/template.svg");
        when(transformUtility.callContentPartnerReadApi(anyString())).thenReturn(partnerApiResponse);

        String certificateTemplateJson = "{\"template\":\"data\"}";
        when(resourceLoader.getResource(anyString())).thenReturn(mockResource);
        when(mockResource.getInputStream()).thenReturn(new ByteArrayInputStream(certificateTemplateJson.getBytes()));

        List<Map<String, Object>> userList = new ArrayList<>();
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("firstname", "John");
        userMap.put("lastname", "Doe");
        userList.add(userMap);

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER),
                any(),
                any())).thenReturn(userList);

        // Act
        kafkaConsumer.enrollUpdateConsumer(consumerRecord);

        // Assert
        verify(producer).push(eq("certTopic"), any(JsonNode.class));
        verify(cassandraOperation).getRecordsByPropertiesWithoutFiltering(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_USER_EXTERNAL_ENROLMENTS),
                any(),
                isNull(),
                eq(1));
    }

    @Test
    void enrollUpdateConsumer_NoUserIdOrCourseId() {
        // Arrange
        String payload = "{\"someField\":\"value\"}";
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("topic", 0, 0, "key", payload);

        // Act
        kafkaConsumer.enrollUpdateConsumer(consumerRecord);

        // Assert - should not throw exception and log error
        verify(cassandraOperation, never()).getRecordsByPropertiesWithoutFiltering(any(), any(), any(), any(), any());
        // Mock producer.push to ensure no interaction
        verify(producer, never()).push(anyString(), any(JsonNode.class));
    }

    @Test
    void enrollUpdateConsumer_NoExistingRecord() {
        // Arrange
        String payload = "{\"userId\":\"user123@domain.com\",\"courseid\":\"course123\",\"partnerId\":\"partner123\"}";
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("topic", 0, 0, "key", payload);

        JsonNode contentNode = mapper.createObjectNode();
        ((ObjectNode) contentNode).put("contentId", "course123");
        JsonNode result = mapper.createObjectNode();
        ((ObjectNode) result).set("content", contentNode);

        lenient().when(transformUtility.callCiosReadAPi(anyString(), anyString())).thenReturn(result);

        // Return empty list to simulate no existing record
        lenient().when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_USER_EXTERNAL_ENROLMENTS),
                any(),
                isNull(),
                eq(1))).thenReturn(Collections.emptyList());

        // Act
        kafkaConsumer.enrollUpdateConsumer(consumerRecord);

        // Assert
        verify(cassandraOperation, never()).updateRecord(any(), any(), any(), any());
        // Mock producer.push to ensure no interaction
        verify(producer, never()).push(anyString(), any(JsonNode.class));
    }

    @Test
    void enrollUpdateConsumer_Exception() {
        // Arrange
        String payload = "{\"userId\":\"user123@domain.com\",\"courseid\":\"course123\",\"partnerId\":\"partner123\"}";
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("topic", 0, 0, "key", payload);

        lenient().when(transformUtility.callCiosReadAPi(anyString(), anyString()))
                .thenThrow(new RuntimeException("Test exception"));

        // Act
        kafkaConsumer.enrollUpdateConsumer(consumerRecord);

        // Assert - should not throw exception and log error
        verify(cassandraOperation, never()).updateRecord(any(), any(), any(), any());
        // Mock producer.push to ensure no interaction
        verify(producer, never()).push(anyString(), any(JsonNode.class));
    }

    @Test
    void receiveProgressUpdateFromPartner_Success() {
        // Arrange
        String payload = "{\"partnerCode\":\"partner123\",\"completion_date\":\"01/01/2023\"}";
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("topic", 0, 0, "key", payload);

        JsonNode partnerResponse = mapper.createObjectNode();
        ((ObjectNode) partnerResponse).put("id", "partner123");
        ((ObjectNode) partnerResponse).set(Constants.TRANSFORM_PROGRESS_JSON, mapper.createArrayNode());

        when(transformUtility.callContentPartnerReadByPartnerCodeApi(anyString())).thenReturn(partnerResponse);

        // Fix: Complete the stubbing before using the mock
        JsonNode transformedData = mapper.createObjectNode();
        when(transformUtility.transformData(any(JsonNode.class), any())).thenReturn(transformedData);

        String updateTopic = "updateTopic";
        when(cbServerProperties.getUserProgressUpdateTopic()).thenReturn(updateTopic);

        // Explicitly mock the producer.push method with the exact topic name
        doNothing().when(producer).push(eq(updateTopic), any(JsonNode.class));

        // Act
        kafkaConsumer.receiveProgressUpdateFromPartner(consumerRecord);

        // Assert
        verify(producer).push(eq(updateTopic), any(JsonNode.class));
    }

    @Test
    void receiveProgressUpdateFromPartner_MissingTransformJson() {
        // Arrange
        String payload = "{\"partnerCode\":\"partner123\"}";
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("topic", 0, 0, "key", payload);

        JsonNode partnerResponse = mapper.createObjectNode();
        ((ObjectNode) partnerResponse).put("id", "partner123");
        // No TRANSFORM_PROGRESS_JSON field

        when(transformUtility.callContentPartnerReadByPartnerCodeApi(anyString())).thenReturn(partnerResponse);

        // Act
        kafkaConsumer.receiveProgressUpdateFromPartner(consumerRecord);

        // Assert
        verify(producer, never()).push(any(), any(JsonNode.class));
    }

    @Test
    void receiveProgressUpdateFromPartner_Exception() {
        // Arrange
        String payload = "{\"partnerCode\":\"partner123\"}";
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("topic", 0, 0, "key", payload);

        when(transformUtility.callContentPartnerReadByPartnerCodeApi(anyString()))
                .thenThrow(new RuntimeException("Test exception"));

        // Act
        kafkaConsumer.receiveProgressUpdateFromPartner(consumerRecord);

        // Assert
        verify(producer, never()).push(any(), any(JsonNode.class));
    }

    @Test
    void testSendUpdatedRecordDataToKafkaToGenerateCertificate() throws Exception {
        // Arrange
        Map<String, Object> userCourseEnrollMap = new HashMap<>();
        userCourseEnrollMap.put(Constants.USER_ID, "user123");
        userCourseEnrollMap.put("completedon", "01/01/2023");

        JsonNode contentPartnerNode = mapper.createObjectNode();
        ((ObjectNode) contentPartnerNode).put("contentPartnerName", "Partner Name");
        ((ObjectNode) contentPartnerNode).put("id", "partner123");

        JsonNode contentNode = mapper.createObjectNode();
        ((ObjectNode) contentNode).put("contentId", "course123");
        ((ObjectNode) contentNode).put("name", "Course Name");
        ((ObjectNode) contentNode).put("appIcon", "http://example.com/icon.png");
        ((ObjectNode) contentNode).set("contentPartner", contentPartnerNode);

        JsonNode result = mapper.createObjectNode();
        ((ObjectNode) result).set("content", contentNode);

        // Use proper mocking without lenient()
        JsonNode partnerApiResponse = mapper.createObjectNode();
        ((ObjectNode) partnerApiResponse).put("certificateTemplateUrl", "http://example.com/template.svg");
        when(transformUtility.callContentPartnerReadApi(anyString())).thenReturn(partnerApiResponse);

        // Mock resource loading
        String certificateTemplateJson = "{\"template\":\"data\"}";
        when(resourceLoader.getResource(anyString())).thenReturn(mockResource);
        when(mockResource.getInputStream()).thenReturn(new ByteArrayInputStream(certificateTemplateJson.getBytes()));

        // Mock user name retrieval
        List<Map<String, Object>> userList = new ArrayList<>();
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("firstname", "John");
        userMap.put("lastname", "Doe");
        userList.add(userMap);
        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER),
                any(),
                any())).thenReturn(userList);

        when(cbServerProperties.getCertificateTopic()).thenReturn("certTopic");
        // Explicitly mock the producer.push method with the exact topic name
        doNothing().when(producer).push(eq("certTopic"), any(JsonNode.class));

        // Act
        ReflectionTestUtils.invokeMethod(kafkaConsumer, "sendUpdatedRecordDataToKafkaToGenerateCertificate",
                userCourseEnrollMap, result);

        // Assert
        verify(producer).push(eq("certTopic"), any(JsonNode.class));
    }

    @Test
    void testSendUpdatedRecordDataToKafkaToGenerateCertificate_NoCertificateTemplate() {
        // Arrange
        Map<String, Object> userCourseEnrollMap = new HashMap<>();
        userCourseEnrollMap.put(Constants.USER_ID, "user123");

        JsonNode contentNode = mapper.createObjectNode();
        ((ObjectNode) contentNode).put("contentId", "course123");
        ((ObjectNode) contentNode).put("name", "Course Name");
        JsonNode contentPartnerNode = mapper.createObjectNode();
        ((ObjectNode) contentPartnerNode).put("id", "partner123");
        ((ObjectNode) contentNode).set("contentPartner", contentPartnerNode);

        JsonNode result = mapper.createObjectNode();
        ((ObjectNode) result).set("content", contentNode);

        JsonNode partnerApiResponse = mapper.createObjectNode();
        // No certificateTemplateUrl
        when(transformUtility.callContentPartnerReadApi(anyString())).thenReturn(partnerApiResponse);

        // Act & Assert
        // The method throws RuntimeException that wraps CustomException
        assertThrows(RuntimeException.class, () -> {
            ReflectionTestUtils.invokeMethod(kafkaConsumer, "sendUpdatedRecordDataToKafkaToGenerateCertificate",
                    userCourseEnrollMap, result);
        });
    }

    @Test
    void testReadUserName_WithLastName() {
        // Arrange
        List<Map<String, Object>> userList = new ArrayList<>();
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("firstname", "John");
        userMap.put("lastname", "Doe");
        userList.add(userMap);

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER),
                any(),
                any())).thenReturn(userList);

        // Act
        String result = ReflectionTestUtils.invokeMethod(kafkaConsumer, "readUserName", "user123");

        // Assert
        assertEquals("John Doe", result);
    }

    @Test
    void testReadUserName_WithoutLastName() {
        // Arrange
        List<Map<String, Object>> userList = new ArrayList<>();
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("firstname", "John");
        userMap.put("lastname", null);
        userList.add(userMap);

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER),
                any(),
                any())).thenReturn(userList);

        // Act
        String result = ReflectionTestUtils.invokeMethod(kafkaConsumer, "readUserName", "user123");

        // Assert
        assertEquals("John", result);
    }

    @Test
    void testConvertToTimestamp() {
        // Act
        Object result = ReflectionTestUtils.invokeMethod(kafkaConsumer, "convertToTimestamp",
                "2023-12-01T06:42:12.000Z");

        // Assert
        assertNotNull(result);

    }

    @Test
    void testConvertToTimestamp_InvalidFormat() {
        // Act
        Object result = ReflectionTestUtils.invokeMethod(kafkaConsumer, "convertToTimestamp", "01-01-2023");

        // Assert
        assertNull(result);
    }

    @Test
    void testConvertDateFormat() {
        // Act
        String result = ReflectionTestUtils.invokeMethod(kafkaConsumer, "convertDateFormat", "2023-01-01T00:00:00Z");

        // Assert
        assertEquals("2023-01-01", result);
    }

    @Test
    void testGetReplacementValue() {
        // Arrange
        Map<String, Object> certificateRequest = new HashMap<>();
        certificateRequest.put(Constants.USER_ID, "user123");
        certificateRequest.put(Constants.COURSE_ID, "course123");
        certificateRequest.put(Constants.COMPLETION_DATE, "2023-01-01T00:00:00Z");
        certificateRequest.put(Constants.PROVIDER_NAME, "Provider");
        certificateRequest.put(Constants.COURSE_NAME, "Course Name that has extended text");
        certificateRequest.put(Constants.RECIPIENT_NAME, "John Doe");
        certificateRequest.put(Constants.COURSE_POSTER_IMAGE, "image.png");
        certificateRequest.put(Constants.SVG_TEMPLATE, "template");

        // Mock the certificate char length to force line breaks
        when(cbServerProperties.getCertificateCharLength()).thenReturn(12);

        // Test all placeholder cases
        assertEquals("user123",
                ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "user.id", certificateRequest));
        assertEquals("course123", ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "course.id",
                certificateRequest));
        assertEquals("2023-01-01", ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "today.date",
                certificateRequest));
        assertNotNull(
                ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "time.ms", certificateRequest));
        assertNotNull(ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "unique.id",
                certificateRequest));
        assertEquals("Course Name", ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue",
                "course.name", certificateRequest));
        assertEquals("that", ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue",
                "course.name.extended", certificateRequest));
        assertEquals("Provider", ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "provider.name",
                certificateRequest));
        assertEquals("John Doe", ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "user.name",
                certificateRequest));
        assertEquals("image.png", ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue",
                "course.poster.image", certificateRequest));
        assertEquals("template", ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "svgTemplate",
                certificateRequest));
        assertEquals("",
                ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "unknown", certificateRequest));
    }

    @Test
    void testGetReplacementValue_WithNewlines() {
        // Arrange
        Map<String, Object> certificateRequest = new HashMap<>();
        certificateRequest.put(Constants.COURSE_NAME, "This is a very long course name that will be wrapped");

        // Use a small character length to force line breaks
        when(cbServerProperties.getCertificateCharLength()).thenReturn(15);

        // Test course.name with newlines - should return text before first newline
        String courseName = (String) ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue",
                "course.name", certificateRequest);
        assertEquals("This is a very", courseName);

        // Test course.name.extended with newlines - should return text after first
        // newline and before second newline
        String courseNameExtended = (String) ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue",
                "course.name.extended", certificateRequest);
        assertEquals("long", courseNameExtended);
    }

    @Test
    void testReplacePlaceholders() throws IOException {
        // Arrange
        String jsonString = "{\"field1\":\"${user.id}\",\"field2\":\"${course.name}\",\"nested\":{\"field3\":\"${provider.name}\"},\"array\":[{\"field4\":\"${user.name}\"}]}";
        JsonNode jsonNode = mapper.readTree(jsonString);

        Map<String, Object> certificateRequest = new HashMap<>();
        certificateRequest.put(Constants.USER_ID, "user123");
        certificateRequest.put(Constants.COURSE_NAME, "Course Name");
        certificateRequest.put(Constants.PROVIDER_NAME, "Provider");
        certificateRequest.put(Constants.RECIPIENT_NAME, "John Doe");

        when(cbServerProperties.getCertificateCharLength()).thenReturn(30);

        // Act
        ReflectionTestUtils.invokeMethod(kafkaConsumer, "replacePlaceholders", jsonNode, certificateRequest);

        // Assert
        assertEquals("user123", jsonNode.get("field1").asText());
        assertEquals("Course Name", jsonNode.get("field2").asText());
        assertEquals("Provider", jsonNode.get("nested").get("field3").asText());
        assertEquals("John Doe", jsonNode.get("array").get(0).get("field4").asText());
    }

    @Test
    void testEnrollUpdateConsumer_whenJsonParseFails_shouldLogError() {
        // Given: an invalid JSON message that will cause ObjectMapper to throw
        // JsonProcessingException
        String invalidJson = "{invalid json}";
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("test-topic", 0, 0L, "key", invalidJson);

        // When & Then: exception should be caught and logged; no exception should be
        // thrown from the method
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> kafkaConsumer.enrollUpdateConsumer(consumerRecord));
    }

    @Test
    void enrollUpdateConsumer_withNullAdditionalProperties_doesNotThrow() throws Exception {
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put(Constants.USER_ID, "user@domain.com");
        payloadMap.put("courseid", "courseid");
        payloadMap.put("partnerId", "partnerId");
        payloadMap.put("completedon", "2023-01-01T00:00:00Z");
        payloadMap.put(Constants.ADDITIONAL_PROPERTIES, null);
        String payload = mapper.writeValueAsString(payloadMap);

        ConsumerRecord<String, String> updatRecord = new ConsumerRecord<>("topic", 0, 0L, "key", payload);
        ObjectNode contentNode = mapper.createObjectNode();
        contentNode.put("contentId", "courseInternalId");
        contentNode.put("name", "Course Name");
        contentNode.put("appIcon", "http://image.png");
        ObjectNode contentPartnerNode = mapper.createObjectNode();
        contentPartnerNode.put("contentPartnerName", "Partner");
        contentPartnerNode.put("id", "partnerId");
        contentNode.set("contentPartner", contentPartnerNode);
        ObjectNode result = mapper.createObjectNode();
        result.set("content", contentNode);

        when(transformUtility.callCiosReadAPi(anyString(), anyString())).thenReturn(result);

        List<Map<String, Object>> dbRecords = new ArrayList<>();
        dbRecords.add(new HashMap<>());
        when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                anyString(), anyString(), any(), isNull(), eq(1))).thenReturn(dbRecords);

        when(cassandraOperation.updateRecord(anyString(), anyString(), any(), any()))
                .thenReturn(Collections.emptyMap());

        ObjectNode partnerApiResponse = mapper.createObjectNode();
        partnerApiResponse.put("certificateTemplateUrl", "http://template.svg");
        when(transformUtility.callContentPartnerReadApi(any())).thenReturn(partnerApiResponse);

        when(resourceLoader.getResource(anyString())).thenReturn(mockResource);
        when(mockResource.getInputStream()).thenReturn(new ByteArrayInputStream("{\"template\":\"data\"}".getBytes()));

        Map<String, Object> userMap = new HashMap<>();
        userMap.put("firstname", "John");
        userMap.put("lastname", "Doe");
        when(cassandraOperation.getRecordsByProperties(anyString(), anyString(), any(), any()))
                .thenReturn(List.of(userMap));

        // Mock the certificate topic and stub producer.push
        when(cbServerProperties.getCertificateTopic()).thenReturn("certTopic");
        doNothing().when(producer).push(eq("certTopic"), any(JsonNode.class));

        // Act
        kafkaConsumer.enrollUpdateConsumer(updatRecord);

        // Assert
        verify(cassandraOperation).updateRecord(anyString(), anyString(), any(), any());
        verify(producer).push(eq("certTopic"), any(JsonNode.class));
    }

    @Test
    void enrollUpdateConsumer_withInvalidCompletedOnFormat_returnsNullTimestamp() throws Exception {
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put(Constants.USER_ID, "user@domain.com");
        payloadMap.put("courseid", "courseid");
        payloadMap.put("partnerId", "partnerId");
        payloadMap.put("completedon", "invalid-format");
        String payload = mapper.writeValueAsString(payloadMap);

        ConsumerRecord<String, String> updateRecord = new ConsumerRecord<>("topic", 0, 0L, "key", payload);
        ObjectNode contentNode = mapper.createObjectNode();
        contentNode.put("contentId", "internalCourseId");
        contentNode.put("name", "Course");
        contentNode.put("appIcon", "http://image.com");
        ObjectNode contentPartnerNode = mapper.createObjectNode();
        contentPartnerNode.put("id", "partnerId");
        contentNode.set("contentPartner", contentPartnerNode);
        ObjectNode result = mapper.createObjectNode();
        result.set("content", contentNode);
        lenient().when(transformUtility.callCiosReadAPi(anyString(), anyString())).thenReturn(result);
        List<Map<String, Object>> records = new ArrayList<>();
        records.add(new HashMap<>());
        lenient().when(cassandraOperation.getRecordsByPropertiesWithoutFiltering(any(), any(), any(), any(), any()))
                .thenReturn(records);
        lenient().when(cassandraOperation.updateRecord(any(), any(), any(), any())).thenReturn(Collections.emptyMap());
        ObjectNode partnerApiResponse = mapper.createObjectNode();
        partnerApiResponse.put("certificateTemplateUrl", "http://template.svg");
        lenient().when(transformUtility.callContentPartnerReadApi(any())).thenReturn(partnerApiResponse);
        lenient().when(resourceLoader.getResource(any())).thenReturn(mockResource);
        lenient().when(mockResource.getInputStream())
                .thenReturn(new ByteArrayInputStream("{\"template\":\"data\"}".getBytes()));
        lenient().when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any()))
                .thenReturn(List.of(Map.of("firstname", "John")));
        ArgumentCaptor<Map<String, Object>> updateCaptor = ArgumentCaptor.forClass(Map.class);
        kafkaConsumer.enrollUpdateConsumer(updateRecord);
        verify(cassandraOperation).updateRecord(any(), any(), updateCaptor.capture(), any());
        Map<String, Object> updateMap = updateCaptor.getValue();
        assertNull(updateMap.get(Constants.COMPLETED_ON),
                "Expected 'completed_on' to be null due to invalid date format");
    }

    @Test
    void sendUpdatedRecordDataToKafkaToGenerateCertificate_missingOptionalFields_doesNotFail() throws Exception {
        Map<String, Object> map = Map.of(Constants.USER_ID, "user123", "completedon", "2023-01-01T00:00:00Z");
        ObjectNode contentNode = mapper.createObjectNode();
        contentNode.put("contentId", "course123"); // No optional fields like name or appIcon
        ObjectNode result = mapper.createObjectNode().set("content", contentNode);
        ObjectNode partnerApiResponse = mapper.createObjectNode()
                .put("certificateTemplateUrl", "http://template.svg");
        when(transformUtility.callContentPartnerReadApi(any())).thenReturn(partnerApiResponse);
        when(resourceLoader.getResource(any())).thenReturn(mockResource);
        when(mockResource.getInputStream()).thenReturn(new ByteArrayInputStream("{\"template\":\"data\"}".getBytes()));
        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any()))
                .thenReturn(List.of(Map.of("firstname", "John")));
        when(cbServerProperties.getCertificateTopic()).thenReturn("certTopic");
        ReflectionTestUtils.invokeMethod(kafkaConsumer, "sendUpdatedRecordDataToKafkaToGenerateCertificate", map,
                result);
        verify(producer).push(eq("certTopic"), any(JsonNode.class));
    }

    @Test
    void replacePlaceholders_withMissingKeys_setsEmptyString() throws IOException {
        JsonNode json = mapper.readTree("{\"field1\":\"${missing.key}\"}");
        Map<String, Object> certRequest = Map.of();
        ReflectionTestUtils.invokeMethod(kafkaConsumer, "replacePlaceholders", json, certRequest);
        assertEquals("", json.get("field1").asText());
    }

    @Test
    void getReplacementValue_withOneNewline_returnsExpectedParts() {
        Map<String, Object> certRequest = new HashMap<>();
        certRequest.put(Constants.COURSE_NAME, "First Line\nSecond Line");
        when(cbServerProperties.getCertificateCharLength()).thenReturn(6);
        String part1 = ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "course.name",
                certRequest);
        String part2 = ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "course.name.extended",
                certRequest);
        assertEquals("First", part1);
        assertEquals("Line", part2);
    }

    @Test
    void testReadUserName_userNotFound_returnsNull() {
        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any())).thenReturn(Collections.emptyList());
        String result = ReflectionTestUtils.invokeMethod(kafkaConsumer, "readUserName", "user123");
        assertNull(result);
    }

    @Test
    void testGetReplacementValue_courseNameWithoutNewline() {
        Map<String, Object> certRequest = Map.of(Constants.COURSE_NAME, "SimpleCourseName");
        when(cbServerProperties.getCertificateCharLength()).thenReturn(50);
        String result = ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "course.name",
                certRequest);
        assertEquals("SimpleCourseName", result);
    }

    @Test
    void testReplacePlaceholders_withNonTextField() throws Exception {
        JsonNode json = mapper.readTree("{\"number\":123,\"nested\":{\"inner\":456}}");
        ReflectionTestUtils.invokeMethod(kafkaConsumer, "replacePlaceholders", json, Map.of());
        assertEquals(123, json.get("number").asInt());
    }

    @Test
    void enrollUpdateConsumer_withMissingContentNode_doesNotFail() {
        String payload = "{\"userid\":\"user@domain.com\",\"courseid\":\"courseid\",\"partnerId\":\"partnerId\"}";
        JsonNode result = mapper.createObjectNode(); // "content" is missing
        when(transformUtility.callCiosReadAPi(anyString(), anyString())).thenReturn(result);
        ConsumerRecord<String, String> updateRecord = new ConsumerRecord<>("topic", 0, 0L, "key", payload);
        kafkaConsumer.enrollUpdateConsumer(updateRecord);
        verify(cassandraOperation, never()).updateRecord(any(), any(), any(), any());
        verify(producer, never()).push(anyString(), any(JsonNode.class));
    }

    @Test
    void sendUpdatedRecordDataToKafkaToGenerateCertificate_withNullTemplate_throwsCustomException() {
        Map<String, Object> map = Map.of(Constants.USER_ID, "user123", "completedon", "2023-01-01T00:00:00Z");
        JsonNode contentNode = mapper.createObjectNode().put("contentId", "course123")
                .set("contentPartner", mapper.createObjectNode().put("id", "partner123"));
        JsonNode result = mapper.createObjectNode().set("content", contentNode);
        JsonNode response = mapper.createObjectNode().putNull("certificateTemplateUrl");
        when(transformUtility.callContentPartnerReadApi(any())).thenReturn(response);
        assertThrows(RuntimeException.class, () -> {
            ReflectionTestUtils.invokeMethod(kafkaConsumer, "sendUpdatedRecordDataToKafkaToGenerateCertificate", map,
                    result);
        });
    }

    @Test
    void receiveProgressUpdateFromPartner_whenTransformJsonMissing_doesNotPush() {
        String payload = "{\"partnerCode\":\"p1\"}";
        ConsumerRecord<String, String> updateRecord = new ConsumerRecord<>("t", 0, 0L, "k", payload);
        JsonNode resp = mapper.createObjectNode().put("id", "p1");
        when(transformUtility.callContentPartnerReadByPartnerCodeApi(anyString())).thenReturn(resp);
        kafkaConsumer.receiveProgressUpdateFromPartner(updateRecord);
        verify(producer, never()).push(anyString(), any(JsonNode.class));
    }

    @Test
    void sendUpdatedRecordDataToKafkaToGenerateCertificate_resourceReadThrows_throwsException() throws Exception {
        Map<String, Object> map = Map.of(Constants.USER_ID, "u1", "completedon", "2023-01-01T00:00:00Z");
        JsonNode contentNode = mapper.createObjectNode().put("contentId", "c1");
        JsonNode result = mapper.createObjectNode().set("content", contentNode);
        JsonNode partnerResp = mapper.createObjectNode().put("certificateTemplateUrl", "http://template.svg");
        when(transformUtility.callContentPartnerReadApi(anyString())).thenReturn(partnerResp);
        when(resourceLoader.getResource(anyString())).thenReturn(mockResource);
        when(mockResource.getInputStream()).thenThrow(new IOException("fail read"));
        assertThrows(RuntimeException.class, () -> {
            ReflectionTestUtils.invokeMethod(kafkaConsumer, "sendUpdatedRecordDataToKafkaToGenerateCertificate", map,
                    result);
        });
    }

    @Test
    void readUserName_whenUserNotFound_returnsNull() {
        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        String name = ReflectionTestUtils.invokeMethod(kafkaConsumer, "readUserName", "someUser");
        assertNull(name);
    }

    @Test
    void getReplacementValue_unknownPlaceholder_returnsEmptyString() {
        Map<String, Object> certReq = new HashMap<>();
        certReq.put(Constants.USER_ID, "u1");
        when(cbServerProperties.getCertificateCharLength()).thenReturn(50);
        String val = ReflectionTestUtils.invokeMethod(kafkaConsumer, "getReplacementValue", "non.existent", certReq);
        assertEquals("", val);
    }

    @Test
    void replacePlaceholders_withArrayAndNonTextNodes() throws IOException {
        String jsonStr = "{\"arr\":[123, {\"key\":\"${user.id}\"}]}";
        JsonNode node = mapper.readTree(jsonStr);
        Map<String, Object> certReq = Map.of(Constants.USER_ID, "u1");
        when(cbServerProperties.getCertificateCharLength()).thenReturn(50);
        ReflectionTestUtils.invokeMethod(kafkaConsumer, "replacePlaceholders", node, certReq);
        assertEquals(123, node.get("arr").get(0).asInt());
        assertEquals("u1", node.get("arr").get(1).get("key").asText());
    }
}