package com.igot.cb.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.igot.cb.util.exceptions.CustomException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PayloadValidation {


  public void validatePayload(String fileName, JsonNode payload) {
    log.info("PayloadValidation::validatePayload:inside");
    try {
      JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4);
      InputStream schemaStream = schemaFactory.getClass().getResourceAsStream(fileName);
      JsonSchema schema = schemaFactory.getSchema(schemaStream);

      Set<ValidationMessage> validationMessages = schema.validate(payload);
      if (!validationMessages.isEmpty()) {
        StringBuilder errorMessage = new StringBuilder("Validation error(s): \n");
        for (ValidationMessage message : validationMessages) {
          errorMessage.append(message.getMessage()).append("\n");
        }
        log.error("Validation Error", errorMessage.toString());
        throw new CustomException("Validation Error", errorMessage.toString(), HttpStatus.BAD_REQUEST);
      }
    } catch (Exception e) {
      log.error("Failed to validate payload",e);
      throw new CustomException("Failed to validate payload", e.getMessage(), HttpStatus.BAD_REQUEST);
    }
  }
}
