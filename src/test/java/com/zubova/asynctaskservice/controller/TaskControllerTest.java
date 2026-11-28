package com.zubova.asynctaskservice.controller;

import com.zubova.asynctaskservice.dto.TaskResponseDTO;
import com.zubova.asynctaskservice.exception.TaskNotFoundException;
import com.zubova.asynctaskservice.model.TaskStatus;
import com.zubova.asynctaskservice.service.TaskQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskQueryService taskQueryService;

    @Test
    void getTask_whenTaskExists_shouldReturnTaskResponse() throws Exception {
        TaskResponseDTO response = createResponse();

        when(taskQueryService.findById(response.id())).thenReturn(response);

        mockMvc.perform(get("/api/tasks/{id}", response.id()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(response.id()))
                .andExpect(jsonPath("$.eventId").value(response.eventId().toString()))
                .andExpect(jsonPath("$.name").value(response.name()))
                .andExpect(jsonPath("$.duration").value(response.duration()))
                .andExpect(jsonPath("$.status").value(response.status().name()))
                .andExpect(jsonPath("$.progress").value(response.progress()))
                .andExpect(jsonPath("$.result").value(response.result()))
                .andExpect(jsonPath("$.createdAt").value(response.createdAt().toString()))
                .andExpect(jsonPath("$.startedAt").value(response.startedAt().toString()))
                .andExpect(jsonPath("$.finishedAt").value(response.finishedAt().toString()));

        verify(taskQueryService).findById(response.id());
    }

    @Test
    void getTaskByEventId_whenTaskExists_shouldReturnTaskResponse() throws Exception {
        TaskResponseDTO response = createResponse();

        when(taskQueryService.findByEventId(response.eventId())).thenReturn(response);

        mockMvc.perform(get("/api/tasks/by-event-id/{eventId}", response.eventId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(response.id()))
                .andExpect(jsonPath("$.eventId").value(response.eventId().toString()))
                .andExpect(jsonPath("$.status").value(response.status().name()));

        verify(taskQueryService).findByEventId(response.eventId());
    }

    @Test
    void getTask_whenTaskDoesNotExist_shouldReturnNotFound() throws Exception {
        Long id = 1L;

        when(taskQueryService.findById(id)).thenThrow(new TaskNotFoundException(id));

        mockMvc.perform(get("/api/tasks/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Задача с ID " + id + " не найдена"));
    }

    @Test
    void getTaskByEventId_whenTaskDoesNotExist_shouldReturnNotFound() throws Exception {
        UUID eventId = UUID.randomUUID();

        when(taskQueryService.findByEventId(eventId))
                .thenThrow(new TaskNotFoundException(eventId));

        mockMvc.perform(get("/api/tasks/by-event-id/{eventId}", eventId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Задача с eventId " + eventId + " не найдена"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    void getTask_whenIdIsInvalid_shouldReturnBadRequest(String id) throws Exception {
        mockMvc.perform(get("/api/tasks/{id}", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("ID задачи должен быть положительным целым числом"));

        verifyNoInteractions(taskQueryService);
    }

    @Test
    void getTaskByEventId_whenEventIdIsInvalid_shouldReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/tasks/by-event-id/{eventId}", "invalid-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("eventId должен быть корректным UUID"));

        verifyNoInteractions(taskQueryService);
    }

    @Test
    void getTask_whenDatabaseIsUnavailable_shouldReturnServiceUnavailable() throws Exception {
        Long id = 1L;

        when(taskQueryService.findById(id))
                .thenThrow(new DataAccessResourceFailureException("Database unavailable"));

        mockMvc.perform(get("/api/tasks/{id}", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Сервис временно недоступен"));
    }

    @Test
    void getTask_whenTransactionCannotBeCreated_shouldReturnServiceUnavailable() throws Exception {
        Long id = 1L;

        when(taskQueryService.findById(id))
                .thenThrow(new CannotCreateTransactionException("Cannot create transaction"));

        mockMvc.perform(get("/api/tasks/{id}", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Сервис временно недоступен"));
    }

    @Test
    void getTask_whenUnexpectedExceptionOccurs_shouldReturnInternalServerError() throws Exception {
        Long id = 1L;

        when(taskQueryService.findById(id))
                .thenThrow(new IllegalStateException("Unexpected error"));

        mockMvc.perform(get("/api/tasks/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Внутренняя ошибка сервиса"));
    }

    @Test
    void getTask_whenHttpMethodIsNotSupported_shouldReturnMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/tasks/{id}", 1L))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("HTTP_405"));

        verifyNoInteractions(taskQueryService);
    }

    private TaskResponseDTO createResponse() {
        return new TaskResponseDTO(
                1L,
                UUID.randomUUID(),
                "task",
                1000L,
                TaskStatus.COMPLETED,
                100,
                "Задача выполнена успешно",
                Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:00:01Z"),
                Instant.parse("2026-01-01T10:00:02Z")
        );
    }

}