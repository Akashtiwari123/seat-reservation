package com.example.seats;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);

  private static ResponseEntity<Map<String, Object>> resp(int status, String code, String msg) {
    return ResponseEntity.status(status).body(Map.of("error", code, "message", String.valueOf(msg)));
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> api(ApiException e) { return resp(e.status, e.code, e.getMessage()); }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> badBody(HttpMessageNotReadableException e) { return resp(400, "bad_request", "malformed JSON body"); }

  @ExceptionHandler(CannotGetJdbcConnectionException.class)
  ResponseEntity<Map<String, Object>> db(CannotGetJdbcConnectionException e) {
    log.error("database unavailable", e);
    return resp(503, "unavailable", "database unavailable");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> other(Exception e) {
    if (e instanceof ErrorResponse er) { // 404 / 405 / 415 etc. raised by Spring itself
      HttpStatusCode sc = er.getStatusCode();
      return resp(sc.value(), HttpStatus.valueOf(sc.value()).name().toLowerCase(), "request not handled");
    }
    log.error("unhandled", e);
    return resp(500, "internal_error", "unexpected error");
  }
}
