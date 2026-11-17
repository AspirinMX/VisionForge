package app.pagefault.visionforge.api.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import app.pagefault.visionforge.common.api.ApiResponse;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;
import app.pagefault.visionforge.common.error.ErrorCode;
import app.pagefault.visionforge.common.error.SystemException;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Translates exceptions into the unified {@link ApiResponse} envelope.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        log.warn("Business exception [{}]: {}", errorCode.code(), exception.getMessage());
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.error(errorCode, exception.getMessage()));
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiResponse<Void>> handleSystemException(SystemException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        log.error("System exception [{}]: {}", errorCode.code(), exception.getMessage(), exception);
        return ResponseEntity.status(errorCode.httpStatus()).body(ApiResponse.error(errorCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        exception.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        log.warn("Request validation failed: {}", errors.keySet());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(CommonErrorCode.INVALID_ARGUMENT,
                        CommonErrorCode.INVALID_ARGUMENT.message(), errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleConstraintViolation(
            ConstraintViolationException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation -> errors.putIfAbsent(
                violation.getPropertyPath().toString(), violation.getMessage()));
        log.warn("Constraint violation: {}", errors.keySet());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(CommonErrorCode.INVALID_ARGUMENT,
                        CommonErrorCode.INVALID_ARGUMENT.message(), errors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidation(
            HandlerMethodValidationException exception) {
        log.warn("Handler method validation failed");
        return ResponseEntity.badRequest().body(ApiResponse.error(CommonErrorCode.INVALID_ARGUMENT));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException exception) {
        log.warn("Missing request parameter: {}", exception.getParameterName());
        return ResponseEntity.badRequest().body(ApiResponse.error(CommonErrorCode.INVALID_ARGUMENT));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        log.warn("Request parameter type mismatch: {}", exception.getName());
        return ResponseEntity.badRequest().body(ApiResponse.error(CommonErrorCode.INVALID_ARGUMENT));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(HttpMessageNotReadableException exception) {
        log.warn("Unreadable request body");
        return ResponseEntity.badRequest().body(ApiResponse.error(CommonErrorCode.INVALID_ARGUMENT));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception) {
        log.warn("Unsupported HTTP method: {}", exception.getMethod());
        return ResponseEntity.status(CommonErrorCode.METHOD_NOT_ALLOWED.httpStatus())
                .body(ApiResponse.error(CommonErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        log.warn("Resource not found: {}", exception.getResourcePath());
        return ResponseEntity.status(CommonErrorCode.NOT_FOUND.httpStatus())
                .body(ApiResponse.error(CommonErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_ERROR));
    }
}
