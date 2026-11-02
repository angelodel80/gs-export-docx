package com.papyrus.api;

import org.springframework.http.HttpStatus;

public class ExportException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ExportException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ExportException invalid(String message) {
        return new ExportException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PACKAGE", message);
    }

    public static ExportException tooLarge(String message) {
        return new ExportException(HttpStatus.PAYLOAD_TOO_LARGE, "PACKAGE_LIMIT", message);
    }
}
