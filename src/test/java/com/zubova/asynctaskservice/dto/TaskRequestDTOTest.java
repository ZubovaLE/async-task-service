package com.zubova.asynctaskservice.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TaskRequestDTOTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        validatorFactory.close();
    }

    @Test
    void deserialize_whenJsonIsValid_shouldCreateTaskRequest() {
        UUID eventId = UUID.randomUUID();
        String json = """
                {
                    "eventId": "%s",
                    "name": "task",
                    "duration": 1000
                }
                """.formatted(eventId);

        try (JsonDeserializer<TaskRequestDTO> deserializer = new JsonDeserializer<>(TaskRequestDTO.class, false)) {
            TaskRequestDTO request = deserializer.deserialize("tasks", json.getBytes(StandardCharsets.UTF_8));

            assertEquals(new TaskRequestDTO(eventId, "task", 1000L), request);
            assertTrue(validator.validate(request).isEmpty());
        }
    }

    @Test
    void deserialize_whenDurationIsMissing_shouldFailValidation() {
        UUID eventId = UUID.randomUUID();
        String json = """
                {
                    "eventId": "%s",
                    "name": "task"
                }
                """.formatted(eventId);

        try (JsonDeserializer<TaskRequestDTO> deserializer = new JsonDeserializer<>(TaskRequestDTO.class, false)) {
            TaskRequestDTO request = deserializer.deserialize("tasks", json.getBytes(StandardCharsets.UTF_8));

            assertNull(request.duration());
            assertEquals(1, validator.validate(request).size());
            assertFalse(validator.validateProperty(request, "duration").isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 60_000L})
    void validate_whenDurationIsWithinAllowedRange_shouldAcceptRequest(long duration) {
        TaskRequestDTO request = new TaskRequestDTO(UUID.randomUUID(), "task", duration);

        assertTrue(validator.validate(request).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void validate_whenRequestIsInvalid_shouldRejectRequest(TaskRequestDTO request, String field) {
        assertFalse(validator.validateProperty(request, field).isEmpty());
    }

    private static Stream<Arguments> invalidRequests() {
        UUID eventId = UUID.randomUUID();
        return Stream.of(
                Arguments.of(new TaskRequestDTO(null, "task", 1000L), "eventId"),
                Arguments.of(new TaskRequestDTO(eventId, null, 1000L), "name"),
                Arguments.of(new TaskRequestDTO(eventId, " ", 1000L), "name"),
                Arguments.of(new TaskRequestDTO(eventId, "a".repeat(256), 1000L), "name"),
                Arguments.of(new TaskRequestDTO(eventId, "task", null), "duration"),
                Arguments.of(new TaskRequestDTO(eventId, "task", 0L), "duration"),
                Arguments.of(new TaskRequestDTO(eventId, "task", -1L), "duration"),
                Arguments.of(new TaskRequestDTO(eventId, "task", 60_001L), "duration")
        );
    }

}