package com.zubova.asynctaskservice.model;

import java.util.UUID;

public record TaskRun(Long id, UUID executionId, long duration) {
}