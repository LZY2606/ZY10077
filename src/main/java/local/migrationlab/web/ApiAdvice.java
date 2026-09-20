package local.migrationlab.web;

import java.time.Instant;
import java.util.Map;
import local.migrationlab.support.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiAdvice {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).body(Map.of(
                "error", exception.getMessage(),
                "status", exception.getStatus().value(),
                "at", Instant.now().toString()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", exception.getMessage(),
                "status", 400,
                "at", Instant.now().toString()
        ));
    }
}
