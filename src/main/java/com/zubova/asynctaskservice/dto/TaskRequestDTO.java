package com.zubova.asynctaskservice.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record TaskRequestDTO(
        @NotNull(message = "Идентификатор события обязателен")
        UUID eventId,

        @NotBlank(message = "Название задачи обязательно")
        @Size(max = 255, message = "Название не должно превышать 255 символов")
        String name,

        @NotNull(message = "Длительность обязательна")
        @Positive(message = "Длительность должна быть положительной")
        @Max(value = 60_000, message = "Длительность не должна превышать 60000 мс")
        Long duration
) {
}