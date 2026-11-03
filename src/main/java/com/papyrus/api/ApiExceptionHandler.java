package com.papyrus.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ExportException.class)
    ResponseEntity<ProblemDetail> export(ExportException ex, HttpServletRequest request) {
        return problem(ex.status(), ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> size(HttpServletRequest request) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_LIMIT", "Archivio oltre il limite multipart configurato.", request);
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> badRequest(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Richiesta multipart non valida: controlla file e parametri.", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> media(HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_MEDIA_TYPE", "Usa multipart/form-data con il campo file.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected failure requestId={}", request.getAttribute(RequestIdFilter.ATTRIBUTE), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Conversione non completata. Conserva l'identificativo richiesta per la diagnosi.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Risorsa non trovata.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Metodo HTTP non supportato.", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String message, HttpServletRequest request) {
        var body = ProblemDetail.forStatusAndDetail(status, message);
        body.setTitle(code);
        body.setProperty("code", code);
        body.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        var builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == HttpStatus.TOO_MANY_REQUESTS) builder.header(HttpHeaders.RETRY_AFTER, "5");
        return builder.body(body);
    }
}
