package com.example.migsb.web;

import com.example.migsb.svc.DefinitionService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalErrorHandler {

    @ExceptionHandler(ApiController.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ApiController.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(DefinitionService.ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(DefinitionService.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "DEFINITION_CONFLICT",
                "message", e.getMessage(),
                "familyId", e.familyId,
                "expectedHead", e.expectedHead,
                "actualHead", e.actualHead
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> engineError(RuntimeException e) {
        return ResponseEntity.status(422)
                .body(Map.of("error", "ENGINE_REJECTED", "message", String.valueOf(e.getMessage())));
    }
}
