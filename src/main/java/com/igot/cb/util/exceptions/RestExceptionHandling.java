package com.igot.cb.util.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class RestExceptionHandling {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex) {
        log.debug("RestExceptionHandler::handleException::" + ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorResponse errorResponse;
        if (ex instanceof CustomException customException) {
            status = HttpStatus.BAD_REQUEST;
            // Check if the CustomException provides an HTTP status code
            try {
                status = customException.getHttpStatusCode();
            } catch (IllegalArgumentException e) {
                log.warn("Invalid HTTP status code provided in CustomException: " + customException.getHttpStatusCode());
            }
            errorResponse = ErrorResponse.builder()
                    .code(customException.getCode())
                    .message(customException.getMessage())
                    .httpStatusCode(customException.getHttpStatusCode() != null
                            ? customException.getHttpStatusCode().value()
                            : status.value())
                    .build();
            if (StringUtils.isNotBlank(customException.getMessage())) {
                log.error(customException.getMessage());
            }

            return new ResponseEntity<>(errorResponse, status);
        }
        errorResponse = ErrorResponse.builder()
                .code("ERROR")
                .message(ex.getMessage())
                .httpStatusCode(status.value())
                .build();
        return new ResponseEntity<>(errorResponse, status);
    }

}
