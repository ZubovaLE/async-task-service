package com.zubova.asynctaskservice.service;

import com.zubova.asynctaskservice.dto.TaskResponseDTO;
import com.zubova.asynctaskservice.exception.TaskNotFoundException;
import com.zubova.asynctaskservice.model.Task;
import com.zubova.asynctaskservice.model.TaskStatus;
import com.zubova.asynctaskservice.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(TaskQueryService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskQueryServiceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskQueryService taskQueryService;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAllInBatch();
    }

    @Test
    void findById_whenTaskExists_shouldReturnTaskResponse() {
        Task task = createTask();
        task.setStatus(TaskStatus.COMPLETED);
        task.setProgress(100);
        task.setResult("Задача выполнена успешно");
        task.setExecutionId(UUID.randomUUID());
        task.setStartedAt(task.getCreatedAt().plusSeconds(1));
        task.setFinishedAt(task.getStartedAt().plusSeconds(1));

        Task savedTask = taskRepository.save(task);

        TaskResponseDTO response = taskQueryService.findById(savedTask.getId());

        assertEquals(
                new TaskResponseDTO(
                        savedTask.getId(),
                        savedTask.getEventId(),
                        savedTask.getName(),
                        savedTask.getDuration(),
                        TaskStatus.COMPLETED,
                        100,
                        savedTask.getResult(),
                        savedTask.getCreatedAt(),
                        savedTask.getStartedAt(),
                        savedTask.getFinishedAt()
                ),
                response
        );
    }

    @Test
    void findByEventId_whenTaskExists_shouldReturnTaskResponse() {
        Task task = taskRepository.save(createTask());

        TaskResponseDTO response = taskQueryService.findByEventId(task.getEventId());

        assertEquals(
                new TaskResponseDTO(
                        task.getId(),
                        task.getEventId(),
                        task.getName(),
                        task.getDuration(),
                        TaskStatus.NEW,
                        0,
                        null,
                        task.getCreatedAt(),
                        null,
                        null
                ),
                response
        );
    }

    @Test
    void findById_whenTaskDoesNotExist_shouldThrowException() {
        Long id = 1L;

        TaskNotFoundException exception = assertThrows(
                TaskNotFoundException.class,
                () -> taskQueryService.findById(id)
        );

        assertEquals("Задача с ID " + id + " не найдена", exception.getMessage());
    }

    @Test
    void findByEventId_whenTaskDoesNotExist_shouldThrowException() {
        UUID eventId = UUID.randomUUID();

        TaskNotFoundException exception = assertThrows(
                TaskNotFoundException.class,
                () -> taskQueryService.findByEventId(eventId)
        );

        assertEquals("Задача с eventId " + eventId + " не найдена", exception.getMessage());
    }

    private Task createTask() {
        Task task = new Task();
        task.setEventId(UUID.randomUUID());
        task.setName("task");
        task.setDuration(1000L);
        task.setCreatedAt(Instant.parse("2026-01-01T10:00:00Z"));
        return task;
    }

}