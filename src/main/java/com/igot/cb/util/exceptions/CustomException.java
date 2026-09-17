package com.igot.cb.util.exceptions;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class CustomException extends RuntimeException{

  private String code;
  private String message;
  private HttpStatus httpStatusCode;

  public CustomException() {
  }

  public CustomException(String code, String message, HttpStatus httpStatusCode) {
    this.code = code;
    this.message = message;
    this.httpStatusCode = httpStatusCode;
  }

}
