package com.papyrus.jobs;

import java.time.Instant;
import java.util.UUID;

public record JobSnapshot(UUID id, State state, String requestedDelivery, Instant acceptedAt,
        Instant transformationStartedAt, Instant completedAt, Instant expiresAt,
        String filename, String mediaType, Integer fragments, String delivery, String resultUrl,
        String warning, String errorCode, String error) {
    public enum State { ACCEPTED, TRANSFORMING, READY, FAILED }
    public boolean terminal() { return state == State.READY || state == State.FAILED; }
}
