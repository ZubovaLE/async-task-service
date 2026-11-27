package com.zubova.asynctaskservice.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.UUID;

@Data
public class TaskRequestDTO {

    @NotNull(message = "Идентификатор события обязателен")
    private UUID eventId;

    @NotBlank(message = "Название задачи обязательно")
    @Size(max = 255, message = "Название не должно превышать 255 символов")
    private String name;

    @NotNull(message = "Длительность обязательна")
    @Positive(message = "Длительность должна быть положительной")
    @Max(value = 60_000, message = "Длительность не должна превышать 60000 мс")
    private Long duration;

}