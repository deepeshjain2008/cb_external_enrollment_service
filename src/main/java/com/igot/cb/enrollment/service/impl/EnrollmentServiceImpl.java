package com.igot.cb.enrollment.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.enrollment.entity.CiosContentEntity;
import com.igot.cb.enrollment.entity.CiosEnrolmentStatus;
import com.igot.cb.enrollment.model.AccessControl;
import com.igot.cb.enrollment.model.UserGroup;
import com.igot.cb.enrollment.model.UserGroupCriteria;
import com.igot.cb.enrollment.repository.CiosContentRepository;
import com.igot.cb.enrollment.service.EnrollmentService;
import com.igot.cb.producer.Producer;
import com.igot.cb.util.CbServerProperties;
import com.igot.cb.util.PayloadValidation;
import com.igot.cb.util.TransformUtility;
import com.igot.cb.util.cache.CacheService;
import com.igot.cb.util.dto.*;
import com.igot.cb.util.Constants;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;


import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;

import com.igot.cb.util.exceptions.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;


@Service
@Slf4j
@RequiredArgsConstructor
public class EnrollmentServiceImpl implements EnrollmentService {

    private final AccessTokenValidator accessTokenValidator;

    private final CassandraOperation cassandraOperation;

    private final ObjectMapper objectMapper;

    private final CacheService cacheService;

    private final CbServerProperties cbServerProperties;

    private final CiosContentRepository contentRepository;

    private final TransformUtility transformUtility;

    private final Producer producer;

    private final PayloadValidation payloadValidation;

    private final Map<String, Integer> statusMap = CiosEnrolmentStatus.toMap();

    @Override
    public SBApiResponse enrollUser(JsonNode userCourseEnroll, String token) {
        log.info("EnrollmentService::enrollUser:inside the method");
        SBApiResponse response = transformUtility.createDefaultResponse(Constants.CIOS_ENROLLMENT_CREATE);
        if (!validateRequest(userCourseEnroll, response)) {
            return response;
        }
        String partnerId = userCourseEnroll.get(Constants.PARTNER_ID).asText();
        String courseId = userCourseEnroll.get(Constants.COURSE_ID_RQST).asText();
        try {
            String userId = transformUtility.validateAndGetUserId(token,response);
            if(StringUtils.isBlank(userId)){
                return response;
            }

            if(isUserEnrolled(response, userId, courseId)){
                return response;
            }
            processEnrolment(response, userId, courseId, partnerId, token);

        } catch (Exception e) {
            String errMsg = Constants.ENROLLMENT_ERROR + e.getMessage();
            log.error(errMsg, e);
            return transformUtility.buildFailedResponse(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    @Override
    public SBApiResponse readByUserId(Map<String, Object> searchRequest, String token) {
        log.info("EnrollmentService::readByUserId:inside the method");
        SBApiResponse response = transformUtility.createDefaultResponse(Constants.CIOS_ENROLLMENT_READ_COURSELIST);
        try {
            Map<String, Object> request = (Map<String, Object>)searchRequest.get(Constants.REQUEST);
            if (MapUtils.isEmpty(request)) {
                response.getParams().setMsg("Request is not proper, please include request body.");
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            String status = (String) request.get(Constants.STATUS);
            if (StringUtils.isEmpty(status)) {
                response.getParams().setMsg("Request is not proper, please provide status in request body.");
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            if (statusMap.get(status) == null) {
                response.getParams().setMsg("Request is not proper, please provide proper value of status.");
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }

            String userId = accessTokenValidator.verifyUserToken(token);
            log.info("UserId from auth token {}", userId);
            if (StringUtils.isBlank(userId) || userId.equalsIgnoreCase(Constants.UNAUTHORIZED)) {
                response.getParams().setMsg(Constants.USER_ID_DOESNT_EXIST);
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }

            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.USER_ID, userId);
            List<Map<String, Object>> userEnrollmentList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    Constants.KEYSPACE_SUNBIRD_COURSES,
                    Constants.TABLE_USER_EXTERNAL_ENROLMENTS,
                    propertyMap,
                    null,
                    null
            );
            if (request.get(Constants.LIMIT) != null) {
                int limit = (int)request.get(Constants.LIMIT);
                if (cbServerProperties.getMaximumAllowedLimit() < limit) {
                    limit = cbServerProperties.getMaximumAllowedLimit();
                }
                userEnrollmentList = userEnrollmentList.stream()
                        .filter(enrollment -> {
                            Instant updatedOn = (Instant) enrollment.get(Constants.UPDATED_ON); // Cast to Date
                            if (updatedOn == null) {
                                log.error("Invalid or missing updatedOn value: " + updatedOn);
                                return false;
                            }
                            return true;
                        })
                        .sorted(Comparator.comparing(enrollment -> ((Instant) (((Map<String, Object>) enrollment).get(Constants.UPDATED_ON)))).reversed())
                        .toList();
                if (CollectionUtils.isNotEmpty(userEnrollmentList) && userEnrollmentList.size() > limit) {
                    userEnrollmentList = userEnrollmentList.subList(0, limit);
                }
            }

            Integer statusValue = statusMap.get(status);
            if (statusValue != -1) {
                userEnrollmentList = userEnrollmentList.stream().filter(enrolment -> (int) enrolment.get(Constants.STATUS) == statusValue).toList();
            }

            List<Map<String, Object>> courses = new ArrayList<>();
            if (!userEnrollmentList.isEmpty()) {
                for (Map<String, Object> enrollment : userEnrollmentList) {
                    String courseId = (String) enrollment.get(Constants.COURSE_ID);
                    Map<String, Object> data = fetchDataByContentId(courseId);
                    enrollment.put(Constants.CONTENT, data.get(Constants.CONTENT));
                    courses.add(enrollment);
                    response.put(Constants.COURSES, courses);
                }
                response.setResponseCode(HttpStatus.OK);
                response.setResult(response.getResult());
            } else {
                response.getParams().setMsg("User is not enrolled into any courses");
                response.getParams().setStatus(Constants.SUCCESS);
                response.setResponseCode(HttpStatus.OK);
                return response;
            }
            return response;
        } catch (Exception e) {
            String errMsg = "Error while performing operation." + e.getMessage();
            log.error(errMsg, e);
            response.getParams().setMsg(errMsg);
            response.getParams().setStatus(Constants.FAILED);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    @Override
    public SBApiResponse readByUserIdAndCourseId(String courseid, String token) {
        log.info("EnrollmentService::readByUserIdAndCourseId:inside the method");
        SBApiResponse response = transformUtility.createDefaultResponse(Constants.CIOS_ENROLLMENT_READ_COURSEID);
        try {
            String userId = accessTokenValidator.verifyUserToken(token);
            if (StringUtils.isBlank(userId) || userId.equalsIgnoreCase(Constants.UNAUTHORIZED)) {
                response.getParams().setMsg(Constants.USER_ID_DOESNT_EXIST);
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            //List<String> fields = Arrays.asList("userid", "courseid", "completedon", "updatedon", "completionpercentage", "enrolled_date", "issued_certificates", "progress", "status"); // Assuming user_id is the column name in your table
            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put("userid", userId);
            propertyMap.put("courseid", courseid);
            List<Map<String, Object>> userEnrollmentList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                    Constants.KEYSPACE_SUNBIRD_COURSES,
                    Constants.TABLE_USER_EXTERNAL_ENROLMENTS,
                    propertyMap,
                    null,
                    1
            );
            if (!userEnrollmentList.isEmpty()) {
                for (Map<String, Object> enrollment : userEnrollmentList) {
                    if (!enrollment.isEmpty()) {
                        response.setResponseCode(HttpStatus.OK);
                        response.setResult(enrollment);
                    } else {
                        response.getParams().setMsg("courseId is not matching");
                        response.getParams().setStatus(Constants.FAILED);
                        response.setResponseCode(HttpStatus.BAD_REQUEST);
                        return response;
                    }
                }
            } else {
                response.getParams().setMsg("User not enrolled into the course");
                response.getParams().setStatus(Constants.SUCCESS);
                response.setResponseCode(HttpStatus.OK);
                return response;
            }
            return response;
        } catch (Exception e) {
            log.error("error while processing", e);
            throw new CustomException(Constants.ERROR, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public SBApiResponse userProgressUpdate(JsonNode jsonNode, String partnercode) {
        try {
            SBApiResponse response = transformUtility.createDefaultResponse(Constants.CIOS_ENROLLMENT_PREGRESS_UPDATE);
            log.info("Payload received for userProgressUpdate: {} and partnerCode: {}", jsonNode.toString(),partnercode);
            String inputDate=jsonNode.get("completion_date").asText();
            String formatedDate=updateDateFormatFromInputDate(inputDate);
            ((ObjectNode)jsonNode).put("completion_date",formatedDate);
            ((ObjectNode)jsonNode).put("partnerCode",partnercode);
            JsonNode additionalProps = jsonNode.path("additionalProperties");
            if (!additionalProps.isMissingNode() && !additionalProps.isNull()) {
                payloadValidation.validatePayload(Constants.PAYLOAD_VALIDATION_FILE_CONTENT_PROVIDER, additionalProps);
            }
            producer.push(cbServerProperties.getUserProgressSendFromPartner(), jsonNode);
            Map<String, Object> result = new HashMap<>();
            result.put("response", "Progress report sent successfully");
            response.setResult(result);
            return response;
        }catch (Exception e) {
           throw new CustomException(Constants.ERROR,e.getMessage(),HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String updateDateFormatFromInputDate(String inputDate) {
        DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime localDateTime = LocalDateTime.parse(inputDate, inputFormatter);

        ZonedDateTime utcZonedDateTime = localDateTime
                .atZone(ZoneId.of("Asia/Kolkata"))
                .withZoneSameInstant(ZoneId.of("UTC"));

        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneId.of("UTC"));
        return outputFormatter.format(utcZonedDateTime);
    }
    public Map<String, Object> fetchDataByContentId(String contentId) {
        log.debug("getting content by id: " + contentId);
        if (StringUtils.isEmpty(contentId)) {
            log.error("CiosContentServiceImpl::read:Id not found");
            throw new CustomException(Constants.ERROR, "contentId is mandatory", HttpStatus.BAD_REQUEST);
        }
        String cachedJson = cacheService.getCache(contentId,cbServerProperties.getDefaultIndex());
        Map<String, Object> response = new HashMap<>();
        if (StringUtils.isNotEmpty(cachedJson)) {
            log.info("CiosContentServiceImpl::read:Record coming from redis cache");
            try {
               return objectMapper.readValue(cachedJson, new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                throw new CustomException(Constants.ERROR, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
            }
        } else {
            Optional<CiosContentEntity> optionalJsonNodeEntity = contentRepository.findByContentIdAndIsActive(contentId, true);
            if (optionalJsonNodeEntity.isPresent()) {
                CiosContentEntity ciosContentEntity = optionalJsonNodeEntity.get();
                cacheService.putCache(contentId, cbServerProperties.getDefaultIndex(), ciosContentEntity.getCiosData());
                log.info("CiosContentServiceImpl::read:Record coming from postgres db");
                return objectMapper.convertValue(ciosContentEntity.getCiosData(), new TypeReference<Map<String, Object>>() {});
            }
        }
    return response;
    }

    private Map<String, String> getUserAttributes(Map<String, Object> userProfileMap) {
        Map<String, String> userAttributes = new HashMap<>();
        userAttributes.put(Constants.USER, (String) userProfileMap.get(Constants.ID));
        userAttributes.put(Constants.ROOT_ORG_ID.toLowerCase(), (String) userProfileMap.get(Constants.ROOT_ORG_ID_REQ));

        String profileDetailsStr = (String) userProfileMap.get(Constants.PROFILE_DETAILS);
        if (StringUtils.isBlank(profileDetailsStr)) {
            return userAttributes;
        }

        try {
            Map<String, Object> profileDetails = objectMapper.readValue(profileDetailsStr, new TypeReference<Map<String, Object>>() {});
            if (MapUtils.isEmpty(profileDetails)) {
                return userAttributes;
            }

            userAttributes.put(Constants.PROFILE_STATUS.toLowerCase(),
                    (String) profileDetails.get(Constants.PROFILE_STATUS));

            populateProfessionalDetails(userAttributes, profileDetails);
            populateCadreDetails(userAttributes, profileDetails);

        } catch (Exception e) {
            throw new CustomException(Constants.USER_NOT_FOUND, e.getMessage(), HttpStatus.NOT_FOUND);
        }
        return userAttributes;
    }

    private void populateProfessionalDetails(Map<String, String> userAttributes, Map<String, Object> profileDetails) {
        Object professionalObj = profileDetails.get(Constants.PROFESSIONAL_DETAILS);
        if (!(professionalObj instanceof List)) {
            return;
        }
        List<?> profList = (List<?>) professionalObj;
        if (profList.isEmpty()) {
            return;
        }
        Object first = profList.get(0);
        if (!(first instanceof Map)) {
            return;
        }
        Map<String, Object> professionalDetails = (Map<String, Object>) first;
        if (MapUtils.isEmpty(professionalDetails)) {
            return;
        }
        if (professionalDetails.get(Constants.DESIGNATION) != null) {
            userAttributes.put(Constants.DESIGNATION, (String) professionalDetails.get(Constants.DESIGNATION));
        }
        if (professionalDetails.get(Constants.GROUP) != null) {
            userAttributes.put(Constants.GROUP, (String) professionalDetails.get(Constants.GROUP));
        }
    }

    @SuppressWarnings("unchecked")
    private void populateCadreDetails(Map<String, String> userAttributes, Map<String, Object> profileDetails) {
        Object cadreObj = profileDetails.get(Constants.CADRE_DETAILS);
        if (!(cadreObj instanceof Map)) {
            return;
        }
        Map<String, Object> cadreDetails = (Map<String, Object>) cadreObj;
        if (MapUtils.isEmpty(cadreDetails)) {
            return;
        }
        if (StringUtils.isNotBlank(MapUtils.getString(cadreDetails, Constants.CADRE_NAME))) {
            userAttributes.put(Constants.CADRE, (String) cadreDetails.get(Constants.CADRE_NAME));
        }
        if (StringUtils.isNotBlank(MapUtils.getString(cadreDetails,Constants.CIVIL_SERVICE_NAME))) {
            userAttributes.put(Constants.SERVICE, (String) cadreDetails.get(Constants.CIVIL_SERVICE_NAME));
        }
        if (StringUtils.isNotBlank(MapUtils.getString(cadreDetails, Constants.CADRE_BATCH))) {
            userAttributes.put(Constants.BATCH, String.valueOf(cadreDetails.get(Constants.CADRE_BATCH)));
        }
    }


    private boolean accessSettingsEnabled(Map<String, String> userAttributes, List<UserGroup> rules) {
        boolean isCourseAllowed = false;
        for (UserGroup rule : rules) {
            boolean isRuleSuccess = true;
            log.info("Validating rule: {}", rule.getUserGroupId());
            for (UserGroupCriteria criteria : rule.getUserGroupCriteriaList()) {
                log.info("Validating criteriaKey: {}, with Value: {}", criteria.getCriteriaKey(), criteria.getCriteriaValue());
                if (!criteria.evaluate(userAttributes)) {
                    isRuleSuccess = false;
                    break;
                }
            }
            if (isRuleSuccess) {
                isCourseAllowed = true;
                log.info("User {} successfully passed the rule using id: {}", userAttributes.get(Constants.USER), rule.getUserGroupId());
                break;
            }
            log.info("isRuleSuccess: {} is course allowed: {}", isRuleSuccess, isCourseAllowed);
        }
        return isCourseAllowed;

    }

    private boolean handleAccessControlledEnrollment(String courseId, Map<String, String> userAttributes) throws JsonProcessingException {
        AccessControl accessControl = transformUtility.readAccessSettings(courseId);
        if (accessControl == null) {
            log.error("Access control settings enabled but not found for courseId: {}", courseId);
            throw new CustomException(Constants.ERROR, Constants.ACCESS_RULES_ENABLED_BUT_NOT_FOUND_COURSE, HttpStatus.BAD_REQUEST);
        }
        return accessSettingsEnabled(userAttributes, accessControl.getUserGroups());
    }

    private void enrollUserInCourse(String userId, String courseId, String partnerId) throws JsonProcessingException {
        ZoneId zoneId = ZoneId.of(Constants.UTC);
        Instant instant = LocalDateTime.now().atZone(zoneId).toInstant();

        Map<String, Object> userCourseEnrollMap = new HashMap<>();
        userCourseEnrollMap.put(Constants.USER_ID, userId);
        userCourseEnrollMap.put(Constants.COURSE_ID, courseId);
        userCourseEnrollMap.put(Constants.PARTNER_ID_REQ, partnerId);
        userCourseEnrollMap.put(Constants.PROGRESS, 0);
        userCourseEnrollMap.put(Constants.STATUS, 0);
        userCourseEnrollMap.put(Constants.COMPLETED_ON, null);
        userCourseEnrollMap.put(Constants.COMPLETION_PERCENTAGE, 0);
        userCourseEnrollMap.put(Constants.ISSUED_CERTIFICATES, new ArrayList<>());
        userCourseEnrollMap.put(Constants.ENROLLED_DATE, instant);
        userCourseEnrollMap.put(Constants.UPDATED_ON, instant);
        userCourseEnrollMap.put(Constants.ADDITIONAL_PROPERTIES, objectMapper.writeValueAsString(new HashMap<>()));

        cassandraOperation.insertRecord(
                Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.TABLE_USER_EXTERNAL_ENROLMENTS,
                userCourseEnrollMap
        );

        cassandraOperation.insertRecord(
                Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.TABLE_USER_EXTERNAL_ENROLMENT_LOOKUP,
                Map.of(Constants.PARTNER_ID_REQ, partnerId,
                        Constants.USER_ID, userId,
                        Constants.COURSE_ID, courseId)
        );

        cacheService.incrementIfExists(Constants.PARTNER + partnerId + Constants.COUNT, 1, cbServerProperties.getRedisIndex());
        cacheService.incrementIfExists(Constants.PARTNER + partnerId + Constants.USER_KEY + userId + Constants.COUNT, 1, cbServerProperties.getRedisIndex());
        cacheService.incrementIfExists(Constants.PARTNER + partnerId + Constants.USER_KEY + userId + Constants.ACTIVE_COUNT, 1, cbServerProperties.getRedisIndex());
        log.info("User {} successfully enrolled to course {}", userId, courseId);
    }

    private boolean validatePartnerEnrollmentLimits(
            String userId,
            String partnerId,
            SBApiResponse response,
            JsonNode providerResponse,
            String token,
            Map<String, String> userAttributes) {

        if (isOverallLimitExceeded(partnerId, providerResponse, response)) return false;

        if (isUserWiseLimitExceeded(userId, partnerId, providerResponse, response)) return false;

        if (isConcurrentLimitExceeded(userId, partnerId, providerResponse, response)) return false;

        return !isKarmaInsufficient(userId, providerResponse, token, userAttributes, response);
    }

    private boolean isOverallLimitExceeded(
            String partnerId,
            JsonNode providerResponse,
            SBApiResponse response) {

        int overallLimit = providerResponse.path(Constants.OVER_ALL_PROVIDER_LIMIT).asInt(0);
        if (overallLimit <= 0) return false;

        String partnerKey = Constants.PARTNER + partnerId + Constants.COUNT;

        int partnerCount = getCountFromCacheOrDb(
                partnerKey,
                () -> cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                        Constants.KEYSPACE_SUNBIRD_COURSES,
                        Constants.TABLE_USER_EXTERNAL_ENROLMENT_LOOKUP,
                        Map.of(Constants.PARTNER_ID_REQ, partnerId),
                        null,
                        null
                ).size()
        );

        if (partnerCount >= overallLimit) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.getParams().setMsg(cbServerProperties.getPartnerOverallLimitMsg());
            return true;
        }
        return false;
    }

    private boolean isUserWiseLimitExceeded(
            String userId,
            String partnerId,
            JsonNode providerResponse,
            SBApiResponse response) {

        if (!providerResponse.path(Constants.USER_WISE_LIMIT_ENABLED).asBoolean(false))
            return false;

        int userWiseLimit = providerResponse.path(Constants.USER_WISE_LIMIT).asInt(0);
        if (userWiseLimit <= 0) return false;

        String userWiseKey =
                Constants.PARTNER + partnerId + Constants.USER_KEY + userId + Constants.COUNT;

        int userCount = getCountFromCacheOrDb(
                userWiseKey,
                () -> cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                        Constants.KEYSPACE_SUNBIRD_COURSES,
                        Constants.TABLE_USER_EXTERNAL_ENROLMENT_LOOKUP,
                        Map.of(Constants.USER_ID, userId, Constants.PARTNER_ID_REQ, partnerId),
                        null,
                        null
                ).size()
        );

        if (userCount >= userWiseLimit) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.getParams().setMsg(cbServerProperties.getPartnerUserwiseLimitMsg());
            return true;
        }
        return false;
    }

    private boolean isConcurrentLimitExceeded(
            String userId,
            String partnerId,
            JsonNode providerResponse,
            SBApiResponse response) {

        if (!providerResponse.path(Constants.CONCURRENT_LIMIT_ENABLED).asBoolean(false))
            return false;

        int concurrentLimit = providerResponse.path(Constants.CONCURRENT_LIMIT).asInt(0);
        if (concurrentLimit <= 0) return false;

        String activeKey =
                Constants.PARTNER + partnerId + Constants.USER_KEY + userId + Constants.ACTIVE_COUNT;

        int activeCount = getCountFromCacheOrDb(
                activeKey,
                () -> {
                    List<Map<String, Object>> allUserCourses =
                            cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                                    Constants.KEYSPACE_SUNBIRD_COURSES,
                                    Constants.TABLE_USER_EXTERNAL_ENROLMENTS,
                                    Map.of(Constants.USER_ID, userId),
                                    null,
                                    null
                            );

                    return (int) allUserCourses.stream()
                            .filter(rec -> partnerId.equals(rec.get(Constants.PARTNER_ID_REQ)))
                            .filter(rec -> rec.get(Constants.STATUS) != null
                                    && ((int) rec.get(Constants.STATUS)) == 0)
                            .count();
                }
        );

        if (activeCount >= concurrentLimit) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.getParams().setMsg(cbServerProperties.getPartnerConcurrentLimitMsg());
            return true;
        }
        return false;
    }

    private boolean isKarmaInsufficient(
            String userId,
            JsonNode providerResponse,
            String token,
            Map<String, String> userAttributes,
            SBApiResponse response) {

        if (!providerResponse.path(Constants.KARMA_POINTS_ENABLED).asBoolean(false))
            return false;

        int karmaPoints = providerResponse.path(Constants.KARMA_POINTS).asInt(0);
        if (karmaPoints <= 0) return false;

        Long userKarmaPoints = transformUtility.readUserKarmaPoints(userId, token);

        String userGroup = userAttributes.get(Constants.GROUP);
        boolean isExemptGroup = StringUtils.isNotBlank(userGroup)
                && cbServerProperties.getKarmaExemptGroups()
                .stream()
                .anyMatch(group -> group.equalsIgnoreCase(userGroup.trim()));

        if (!isExemptGroup && userKarmaPoints < karmaPoints) {
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            response.getParams().setMsg(
                    String.format(cbServerProperties.getKarmaInsufficientMsg(), karmaPoints)
            );
            return true;
        }
        return false;
    }

    private int getCountFromCacheOrDb(String key, Supplier<Integer> dbSupplier) {
        String cached = cacheService.getCache(key,cbServerProperties.getRedisIndex());
        if (StringUtils.isNotBlank(cached)) {
            return Integer.parseInt(cached);
        }

        int count = dbSupplier.get();
        cacheService.putCache(key, cbServerProperties.getRedisIndex(), count);
        return count;
    }

    @Override
    public SBApiResponse enrolValidation(JsonNode userCourseEnroll, String token) {
        log.info("EnrollmentService::enrolValidation:inside the method");
        SBApiResponse response = transformUtility.createDefaultResponse(Constants.CIOS_ENROLLMENT_CREATE);
        if (!userCourseEnroll.hasNonNull(Constants.COURSE_ID_RQST)) {
            return transformUtility.buildFailedResponse(response, "CourseId is mandatory", HttpStatus.BAD_REQUEST);
        }

        String partnerId = userCourseEnroll.path(Constants.PARTNER_ID).asText("");
        String courseId = userCourseEnroll.get(Constants.COURSE_ID_RQST).asText("");
        JsonNode contentResponse = transformUtility.callCiosContentReadAPi(courseId);
        if(StringUtils.isBlank(partnerId)){
            partnerId = contentResponse.path(Constants.CONTENT_PARTNER).path(Constants.ID).asText("");
        }

        if (StringUtils.isBlank(partnerId) || StringUtils.isBlank(courseId)) {
            return transformUtility.buildFailedResponse(response, "Both partnerId and CourseId cannot be empty", HttpStatus.BAD_REQUEST);
        }
        try {
            String userId = transformUtility.validateAndGetUserId(token,response);
            if(StringUtils.isBlank(userId)){
                return response;
            }

            JsonNode providerResponse = transformUtility.callContentPartnerReadApi(partnerId);

            Map<String, Object> userProfile = transformUtility.readUserDetails(userId);
            Map<String, String> userAttributes = MapUtils.isNotEmpty(userProfile)
                    ? getUserAttributes(userProfile)
                    : new HashMap<>();
            log.info("User attributes fetched for enrollment: {}", userAttributes);

            if (!validatePartnerEnrollmentLimits(userId, partnerId, response, providerResponse.get(Constants.DATA), token, userAttributes)) {
                return response;
            }
            if (contentResponse.has(Constants.ACCESS_SETTINGS_ENABLED) && contentResponse.get(Constants.ACCESS_SETTINGS_ENABLED).asBoolean()
                    && !handleAccessControlledEnrollment(courseId, userAttributes)) {
                return transformUtility.buildFailedResponse(response, cbServerProperties.getAccessSettingsErrorMessage(), HttpStatus.BAD_REQUEST);
            }
            return transformUtility.buildSuccessResponse(response, "Enrollment validation successful", HttpStatus.OK);
        } catch (Exception e) {
            String errMsg = Constants.ENROLLMENT_ERROR + e.getMessage();
            log.error(errMsg, e);
            return transformUtility.buildFailedResponse(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private boolean validateRequest(JsonNode request, SBApiResponse response) {
        if (!request.hasNonNull(Constants.COURSE_ID_RQST)
                || StringUtils.isBlank(request.get(Constants.COURSE_ID_RQST).asText())) {

            transformUtility.buildFailedResponse(
                    response,
                    "CourseId is mandatory and cannot be empty",
                    HttpStatus.BAD_REQUEST
            );
            return false;
        }

        boolean isPartnerIdMissing =
                !request.hasNonNull(Constants.PARTNER_ID)
                        || StringUtils.isBlank(request.path(Constants.PARTNER_ID).asText());

        if (isPartnerIdMissing) {
            JsonNode contentResponse =
                    transformUtility.callCiosContentReadAPi(
                            request.get(Constants.COURSE_ID_RQST).asText()
                    );

            String partnerIdFromContent =
                    contentResponse
                            .path(Constants.CONTENT_PARTNER)
                            .path(Constants.ID)
                            .asText("");

            if (StringUtils.isBlank(partnerIdFromContent)) {
                transformUtility.buildFailedResponse(
                        response,
                        "PartnerId not found for given CourseId",
                        HttpStatus.BAD_REQUEST
                );
                return false;
            }
            ((ObjectNode) request).put(Constants.PARTNER_ID, partnerIdFromContent);
        }

        return true;
    }

    private boolean isUserEnrolled(SBApiResponse response, String userId, String courseId) {
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.USER_ID, userId);
        propertyMap.put(Constants.COURSE_ID, courseId);
        List<Map<String, Object>> userEnrollmentList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(
                Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.TABLE_USER_EXTERNAL_ENROLMENTS,
                propertyMap,
                null,
                1
        );
        if (!userEnrollmentList.isEmpty()) {
            transformUtility.buildFailedResponse(response, "User already enrolled to the course", HttpStatus.BAD_REQUEST);
            return true;
        }
        return false;
    }

    private SBApiResponse processEnrolment(SBApiResponse response, String userId, String courseId, String partnerId, String token) {
        try {
            JsonNode contentResponse = transformUtility.callCiosContentReadAPi(courseId);

            JsonNode providerResponse = transformUtility.callContentPartnerReadApi(partnerId);

            Map<String, Object> userProfile = transformUtility.readUserDetails(userId);
            Map<String, String> userAttributes = MapUtils.isNotEmpty(userProfile)
                    ? getUserAttributes(userProfile)
                    : new HashMap<>();
            log.info("User attributes fetched for enrollment: {}", userAttributes);

            // Validate provider settings limits
            if (!validatePartnerEnrollmentLimits(userId, partnerId, response, providerResponse.get(Constants.DATA), token, userAttributes)) {
                return response;
            }
            // Check access control settings enabled and validate
            if (contentResponse.has(Constants.ACCESS_SETTINGS_ENABLED) && contentResponse.get(Constants.ACCESS_SETTINGS_ENABLED).asBoolean()
                    && !handleAccessControlledEnrollment(courseId, userAttributes)) {
                return transformUtility.buildFailedResponse(response, cbServerProperties.getAccessSettingsErrorMessage(), HttpStatus.BAD_REQUEST);
            }
            // Special handling for Coursera partner to invite user
            String providerCode = providerResponse.path(Constants.DATA).path(Constants.PARTNER_CODE).asText("").toLowerCase();
            if (cbServerProperties.getCourseraPartnerCode().equalsIgnoreCase(providerCode)) {
                boolean inviteSuccess = transformUtility.callCourseraInviteApi(
                        contentResponse,
                        userProfile);
                    if (!inviteSuccess) {
                        return transformUtility.buildFailedResponse(response, "User invitation failed on Coursera", HttpStatus.BAD_REQUEST);
                    }
            }
            // Enroll user in course
            enrollUserInCourse(userId, courseId, partnerId);
            response.setResponseCode(HttpStatus.OK);
            response.setResult(Map.of("message", "User enrolled successfully"));
        }catch (Exception e) {
            String errMsg = Constants.ENROLLMENT_ERROR + e.getMessage();
            log.error(errMsg, e);
            return transformUtility.buildFailedResponse(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    @Override
    public SBApiResponse getUserEnrolmentByExternalId(String userId, String externalId, String partnerCode) {
        log.info("EnrollmentService::getUserEnrolmentByExternalId:inside the method");
        SBApiResponse response = transformUtility.createDefaultResponse(Constants.CIOS_ENROLLMENT_READ_BY_EXTERNAL_ID);
        try {
            if (StringUtils.isBlank(userId) || StringUtils.isBlank(externalId) || StringUtils.isBlank(partnerCode)) {
                return transformUtility.buildFailedResponse(response,
                        "userId, externalId and partnerCode are mandatory", HttpStatus.BAD_REQUEST);
            }
            String contentId = transformUtility.getContentIdByExternalId(externalId, partnerCode);
            if (StringUtils.isBlank(contentId)) {
                return transformUtility.buildFailedResponse(response,
                        "No content found for given courseId and partnerCode", HttpStatus.BAD_REQUEST);
            }

            Map<String, Object> propertyMap = new HashMap<>();
            propertyMap.put(Constants.USER_ID, userId);
            propertyMap.put(Constants.COURSE_ID, contentId);

            List<Map<String, Object>> userEnrollmentList = cassandraOperation.getRecordsByProperties(
                    Constants.KEYSPACE_SUNBIRD_COURSES,
                    Constants.TABLE_USER_EXTERNAL_ENROLMENTS,
                    propertyMap,
                    null
            );

            if (CollectionUtils.isNotEmpty(userEnrollmentList)) {
                response.getParams().setMsg("User already enrolled into the course");
                response.getParams().setStatus(Constants.SUCCESS);
            } else {
                response.getParams().setMsg("User not enrolled into the course");
                response.getParams().setStatus(Constants.FAILED);
                response.setResponseCode(HttpStatus.NOT_FOUND);
            }
            return response;
        } catch (Exception e) {
            String errMsg = "Error while fetching user enrolment by externalId. " + e.getMessage();
            log.error(errMsg, e);
            return transformUtility.buildFailedResponse(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
