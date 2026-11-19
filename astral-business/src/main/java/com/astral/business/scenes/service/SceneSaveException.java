package com.astral.business.scenes.service;

public class SceneSaveException extends RuntimeException {
    private final int status;
    private final String reason;

    public SceneSaveException(int status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    public int getStatus() { return status; }
    public String getReason() { return reason; }
}
