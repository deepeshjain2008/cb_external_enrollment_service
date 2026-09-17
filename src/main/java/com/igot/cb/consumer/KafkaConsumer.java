package com.igot.cb.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.producer.Producer;
import com.igot.cb.util.CbServerProperties;
import com.igot.cb.util.TransformUtility;
import com.igot.cb.util.Constants;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;

import java.io.InputStream;


import com.igot.cb.util.cache.CacheService;
import com.igot.cb.util.exceptions.CustomException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.http.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

import org.springframework.core.io.*;


@Component
@Slf4j
public class KafkaConsumer {
    private ObjectMapper mapper = new ObjectMapper();

    private final CassandraOperation cassandraOperation;
    private final Producer producer;
    private final CbServerProperties cbServerProperties;
    private final TransformUtility transformUtility;
    private final ResourceLoader resourceLoader;
    private final CacheService cacheService;

    public KafkaConsumer(
            CassandraOperation cassandraOperation,
            Producer producer,
            CbServerProperties cbServerProperties,
            TransformUtility transformUtility,
            ResourceLoader resourceLoader,
            CacheService cacheService) {
        this.cassandraOperation = cassandraOperation;
        this.producer = producer;
        this.cbServerProperties = cbServerProperties;
        this.transformUtility = transformUtility;
        this.resourceLoader = resourceLoader;
        this.cacheService = cacheService;
    }

    @KafkaListener(topics = "${spring.kafka.cornell.topic.name}", groupId = "${spring.kafka.consumer.group.id}")
    public void enrollUpdateConsumer(ConsumerRecord<String, String> data) {
        log.info("KafkaConsumer::enrollUpdateConsumer:topic name: {} and recievedData: {}", data.topic(), data.value());
        try {
            ZoneId zoneId = ZoneId.of("UTC");
            Instant instant = LocalDateTime.now().atZone(zoneId).toInstant();
            Map<String, Object> userCourseEnrollMap = mapper.readValue(data.value(), HashMap.class);
            if (userCourseEnrollMap.containsKey(Constants.USER_ID) && userCourseEnrollMap.get(Constants.USER_ID) instanceof String userIdValue && userCourseEnrollMap.containsKey(Constants.COURSE_ID) && userCourseEnrollMap.get(Constants.COURSE_ID) instanceof String) {
                String courseId = "";
                String partnerId = userCourseEnrollMap.get("partnerId").toString();
                String extCourseId = userCourseEnrollMap.get("courseid").toString();
                JsonNode result = transformUtility.callCiosReadAPi(extCourseId, partnerId);
                log.debug("got result from cios read api");
                JsonNode contentNode = result.path("content");
                if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                    courseId = contentNode.get("contentId").asText();
                }
                log.debug("KafkaConsumer :: enrollUpdateConsumer ::courseId from cios api {} userid {}", courseId, userCourseEnrollMap.get(Constants.USER_ID));
                String[] parts = userIdValue.split("@");
                userCourseEnrollMap.put(Constants.USER_ID, parts[0]);
                Map<String, Object> propertyMap = new HashMap<>();
                propertyMap.put(Constants.USER_ID, userCourseEnrollMap.get(Constants.USER_ID));
                propertyMap.put(Constants.COURSE_ID, courseId);
                List<Map<String, Object>> listOfMasterData = cassandraOperation.getRecordsByPropertiesWithoutFiltering(Constants.KEYSPACE_SUNBIRD_COURSES, Constants.TABLE_USER_EXTERNAL_ENROLMENTS, propertyMap, null, 1);
                if (!CollectionUtils.isEmpty(listOfMasterData)) {
                    Map<String, Object> updatedMap = new HashMap<>();
                    updatedMap.put(Constants.PROGRESS, 100);
                    updatedMap.put(Constants.STATUS, 2);
                    updatedMap.put(Constants.COMPLETED_ON, convertToTimestamp((String) userCourseEnrollMap.get("completedon")));
                    updatedMap.put(Constants.COMPLETION_PERCENTAGE, 100);
                    updatedMap.put(Constants.UPDATED_ON, instant);
                    if(userCourseEnrollMap.get(Constants.ADDITIONAL_PROPERTIES)!=null) {
                        updatedMap.put(Constants.ADDITIONAL_PROPERTIES, mapper.writeValueAsString(userCourseEnrollMap.get("additional_properties")));
                    } else {
                        updatedMap.put(Constants.ADDITIONAL_PROPERTIES, mapper.writeValueAsString(new HashMap<>()));
                    }
                    cassandraOperation.updateRecord(Constants.KEYSPACE_SUNBIRD_COURSES, Constants.TABLE_USER_EXTERNAL_ENROLMENTS, updatedMap, propertyMap);
                    String userId = userCourseEnrollMap.get(Constants.USER_ID).toString();
                    if (StringUtils.isNotBlank(userId)) {
                        cacheService.deleteCache(Constants.PARTNER + partnerId + Constants.USER_KEY + userCourseEnrollMap.get(Constants.USER_ID) + Constants.ACTIVE_COUNT, cbServerProperties.getRedisIndex());
                    }
                    sendUpdatedRecordDataToKafkaToGenerateCertificate(userCourseEnrollMap, result);
                } else {
                    log.error("Data not present in DB for userid {} and courseid {}", userCourseEnrollMap.get(Constants.USER_ID), courseId);
                    //add not enrolled data to file
                }
            } else {
                log.error("Unable to get userid and courseid from kafka consumer");
            }

        } catch (Exception e) {
            log.error("Failed to read enroll Request. Message received : " + data.value(), e);
        }
    }

    @KafkaListener(topics = "${user.progress.send.from.partner.topic.name}", groupId = "${user.progress.send.from.partner.consumer.group.id}")
    public void receiveProgressUpdateFromPartner(ConsumerRecord<String, String> data) {
        log.info("KafkaConsumer::receiveProgressUpdateFromPartner:topic name: {} and recievedData: {}", data.topic(), data.value());
        try {
            JsonNode jsonNode = mapper.readTree(data.value());
            JsonNode partnerReadApiResponse = transformUtility.callContentPartnerReadByPartnerCodeApi(jsonNode.get("partnerCode").asText());
            if (!partnerReadApiResponse.path(Constants.TRANSFORM_PROGRESS_JSON).isMissingNode()) {
                String partnerid = partnerReadApiResponse.get("id").asText();
                List<Object> contentJson = mapper.convertValue(partnerReadApiResponse.path(Constants.TRANSFORM_PROGRESS_JSON), new TypeReference<List<Object>>() {
                });
                JsonNode transformData = transformUtility.transformData(jsonNode, contentJson);
                ((ObjectNode) transformData).put(Constants.PARTNER_ID, partnerid);
                JsonNode additionalProps = jsonNode.path("additionalProperties");
                if (!additionalProps.isMissingNode() && !additionalProps.isNull()) {
                    ((ObjectNode) transformData).set("additional_properties", additionalProps);
                }
                producer.push(cbServerProperties.getUserProgressUpdateTopic(), transformData);
            } else {
                log.error("Partner Transform progress json is missing in content partner db, please update");
            }
        } catch (Exception e) {
            log.error("Failed to read enroll Request. Message received : " + data.value(), e);
        }
    }

    private void sendUpdatedRecordDataToKafkaToGenerateCertificate(Map<String, Object> userCourseEnrollMap, JsonNode result) {
        log.info("KafkaConsumer::sendUpdatedRecordDataToKafkaToGenerateCertificate:inside method");
        try {
            String courseId = "";
            String courseName = "";
            String contentPartnerName = "";
            String coursePosterImage = "";
            String partnerId = "";
            JsonNode contentNode = result.path("content");
            if (!contentNode.isMissingNode() && !contentNode.isNull()) {
                courseId = contentNode.get("contentId").asText();
                courseName = contentNode.path("name").asText(null);
                coursePosterImage = contentNode.path("appIcon").asText(null);
                JsonNode contentPartnerNode = contentNode.path("contentPartner");
                if (!contentPartnerNode.isMissingNode() && !contentPartnerNode.isNull()) {
                    contentPartnerName = contentPartnerNode.path("contentPartnerName").asText(null);
                    partnerId = contentPartnerNode.path("id").asText(null);
                }
            }
            JsonNode partnerApiResponse = transformUtility.callContentPartnerReadApi(partnerId);
            if (!partnerApiResponse.path(Constants.CERTIFICATE_TEMPLATE_URL).isMissingNode() && !partnerApiResponse.path(Constants.CERTIFICATE_TEMPLATE_URL).isNull()) {
                String svgTemplate = partnerApiResponse.get(Constants.CERTIFICATE_TEMPLATE_URL).asText();
                Resource resource = resourceLoader.getResource("classpath:certificateTemplate.json");
                InputStream inputStream = resource.getInputStream();
                JsonNode jsonNode = mapper.readTree(inputStream);
                Map<String, Object> certificateRequest = new HashMap<>();
                certificateRequest.put(Constants.USER_ID, userCourseEnrollMap.get(Constants.USER_ID));
                certificateRequest.put(Constants.COURSE_ID, courseId);
                certificateRequest.put(Constants.COMPLETION_DATE, userCourseEnrollMap.get("completedon"));
                certificateRequest.put(Constants.PROVIDER_NAME, contentPartnerName);
                certificateRequest.put(Constants.COURSE_NAME, courseName);
                certificateRequest.put(Constants.COURSE_POSTER_IMAGE, coursePosterImage);
                certificateRequest.put(Constants.RECIPIENT_NAME, readUserName(userCourseEnrollMap.get(Constants.USER_ID).toString()));
                certificateRequest.put(Constants.SVG_TEMPLATE, svgTemplate);
                replacePlaceholders(jsonNode, certificateRequest);
                producer.push(cbServerProperties.getCertificateTopic(), jsonNode);
                log.info("KafkaConsumer::enrollUpdateConsumer:updated");
            } else {
                throw new CustomException(Constants.ERROR, "Certificate JsonData not found in Content Partner Response", HttpStatus.INTERNAL_SERVER_ERROR);
            }

        } catch (Exception e) {
            throw new CustomException(Constants.ERROR, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String readUserName(String userid) {
        List<String> fields = Arrays.asList("firstname", "lastname"); // Assuming user_id is the column name in your table
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put("id", userid);
        List<Map<String, Object>> userEnrollmentList = cassandraOperation.getRecordsByProperties(
                Constants.KEYSPACE_SUNBIRD,
                Constants.TABLE_USER,
                propertyMap,
                fields
        );
        Optional<Map<String, Object>> optionalUser = userEnrollmentList.stream().findFirst();
        if (optionalUser.isPresent()) {
            Map<String, Object> user = optionalUser.get();
            String firstname = (String) user.get("firstname");
            String lastname = (String) user.get("lastname");
            String fullname = firstname;
            if (lastname != null) {
                fullname = fullname + " " + lastname;
            }
            return fullname;
        }
        return null;
    }


    private static Instant convertToTimestamp(String dateString) {
        if (dateString == null || dateString.trim().isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(dateString);
        } catch (DateTimeParseException e1) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                LocalDateTime dateTime = LocalDateTime.parse(dateString, formatter);
                return dateTime.toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                e2.printStackTrace();
                return null;
            }
        }
    }

    private void replacePlaceholders(JsonNode jsonNode, Map<String, Object> certificateRequest) {
        log.debug("KafkaConsumer :: replacePlaceholders");
        if (jsonNode.isObject()) {
            ObjectNode objectNode = (ObjectNode) jsonNode;
            objectNode.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    String textValue = value.asText();
                    if (textValue.startsWith("${") && textValue.endsWith("}")) {
                        String placeholder = textValue.substring(2, textValue.length() - 1);
                        String replacement = getReplacementValue(placeholder, certificateRequest);
                        objectNode.put(entry.getKey(), replacement);
                    }
                } else if (value.isArray()) {
                    value.elements().forEachRemaining(element -> {
                        if (element.isObject()) {
                            replacePlaceholders(element, certificateRequest);
                        }
                    });
                } else {
                    replacePlaceholders(value, certificateRequest);
                }
            });
        }
    }

    private String getReplacementValue(String placeholder, Map<String, Object> certificateRequest) {
        log.debug("KafkaConsumer :: getReplacementValue");
        String value = WordUtils.wrap((String) certificateRequest.get(Constants.COURSE_NAME), cbServerProperties.getCertificateCharLength(), "\n", false);
        switch (placeholder) {
            case "user.id":
                return (String) certificateRequest.get(Constants.USER_ID);
            case "course.id":
                return (String) certificateRequest.get(Constants.COURSE_ID);
            case "today.date":
                return convertDateFormat((String) certificateRequest.get(Constants.COMPLETION_DATE));
            case "time.ms":
                return String.valueOf(System.currentTimeMillis());
            case "unique.id":
                return UUID.randomUUID().toString();
            case "course.name":
                int firstNewLineIndex = value.indexOf("\n");
                if (firstNewLineIndex != -1) {
                    return value.substring(0, firstNewLineIndex).trim();
                } else {
                    return value;
                }
            case "course.name.extended":
                int firstNewLineIndexExtended = value.indexOf("\n");
                if (firstNewLineIndexExtended != -1) {
                    String textAfterFirstNewLine = value.substring(firstNewLineIndexExtended + 1).trim();
                    String secondValue = WordUtils.wrap(textAfterFirstNewLine, cbServerProperties.getCertificateCharLength(), "\n", false);
                    int secondNewLineIndex = secondValue.indexOf("\n");
                    if (secondNewLineIndex != -1) {
                        return secondValue.substring(0, secondNewLineIndex).trim();
                    } else {
                        return secondValue;
                    }
                } else {
                    return "";
                }
            case "provider.name":
                return (String) certificateRequest.get(Constants.PROVIDER_NAME);
            case "user.name":
                return (String) certificateRequest.get(Constants.RECIPIENT_NAME);
            case "course.poster.image":
                return (String) certificateRequest.get(Constants.COURSE_POSTER_IMAGE);
            case "svgTemplate":
                return (String) certificateRequest.get(Constants.SVG_TEMPLATE);
            default:
                return "";
        }
    }

    private static String convertDateFormat(String originalDate) {
        Instant instant = Instant.parse(originalDate); // Parse ISO 8601 format
        ZonedDateTime zonedDateTime = instant.atZone(ZoneId.of("UTC"));
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        return outputFormatter.format(zonedDateTime);
    }

}
