package com.zubova.asynctaskservice.service;

import com.zubova.asynctaskservice.model.Task;
import com.zubova.asynctaskservice.model.TaskRun;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({TaskClaimService.class, TaskUpdateService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskUpdateServiceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TaskClaimService taskClaimService;

    @Autowired
    private TaskUpdateService taskUpdateService;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAllInBatch();
    }

    @Test
    void updateProgress_whenExecutionIsActive_shouldUpdateProgress() {
        TaskRun run = createRunningTask();
        int progress = 25;

        assertTrue(taskUpdateService.updateProgress(run.id(), run.executionId(), progress));

        Task savedTask = taskRepository.findById(run.id()).orElseThrow();
        assertAll(
                () -> assertEquals(progress, savedTask.getProgress()),
                () -> assertEquals(TaskStatus.IN_PROGRESS, savedTask.getStatus()),
                () -> assertEquals(run.executionId(), savedTask.getExecutionId()),
                () -> assertNull(savedTask.getFinishedAt())
        );
    }

    @Test
    void updateProgress_whenProgressDecreases_shouldRejectUpdate() {
        TaskRun run = createRunningTask();

        assertTrue(taskUpdateService.updateProgress(run.id(), run.executionId(), 50));
        assertFalse(taskUpdateService.updateProgress(run.id(), run.executionId(), 25));

        Task savedTask = taskRepository.findById(run.id()).orElseThrow();
        assertEquals(50, savedTask.getProgress());
    }

    @Test
    void updateTaskStatus_whenTaskCompletes_shouldSaveResult() {
        TaskRun run = createRunningTask();
        String result = "Задача выполнена успешно";

        assertTrue(taskUpdateService.updateTaskStatus(
                run.id(), run.executionId(), TaskStatus.COMPLETED, 100, result
        ));

        Task savedTask = taskRepository.findById(run.id()).orElseThrow();
        assertAll(
                () -> assertEquals(TaskStatus.COMPLETED, savedTask.getStatus()),
                () -> assertEquals(100, savedTask.getProgress()),
                () -> assertEquals(result, savedTask.getResult()),
                () -> assertNotNull(savedTask.getFinishedAt())
        );
    }

    @Test
    void updateTaskStatus_whenTaskFails_shouldSaveFailure() {
        TaskRun run = createRunningTask();
        String result = "Ошибка выполнения задачи";
        int progress = 50;

        assertTrue(taskUpdateService.updateTaskStatus(
                run.id(), run.executionId(), TaskStatus.FAILED, progress, result
        ));

        Task savedTask = taskRepository.findById(run.id()).orElseThrow();
        assertAll(
                () -> assertEquals(TaskStatus.FAILED, savedTask.getStatus()),
                () -> assertEquals(progress, savedTask.getProgress()),
                () -> assertEquals(result, savedTask.getResult()),
                () -> assertNotNull(savedTask.getFinishedAt())
        );
    }

    @Test
    void updateProgress_whenExecutionIdDiffers_shouldRejectUpdate() {
        TaskRun run = createRunningTask();

        assertFalse(taskUpdateService.updateProgress(run.id(), UUID.randomUUID(), 50));

        Task savedTask = taskRepository.findById(run.id()).orElseThrow();
        assertEquals(0, savedTask.getProgress());
    }

    @Test
    void updateTaskStatus_whenExecutionIdDiffers_shouldRejectUpdate() {
        TaskRun run = createRunningTask();

        assertFalse(taskUpdateService.updateTaskStatus(
                run.id(), UUID.randomUUID(), TaskStatus.COMPLETED, 100, "Устаревший результат"
        ));

        Task savedTask = taskRepository.findById(run.id()).orElseThrow();
        assertAll(
                () -> assertEquals(TaskStatus.IN_PROGRESS, savedTask.getStatus()),
                () -> assertEquals(run.executionId(), savedTask.getExecutionId()),
                () -> assertEquals(0, savedTask.getProgress()),
                () -> assertNull(savedTask.getResult()),
                () -> assertNull(savedTask.getFinishedAt())
        );
    }

    @Test
    void updateTaskStatus_whenTaskIsAlreadyCompleted_shouldRejectUpdate() {
        TaskRun run = createRunningTask();
        String result = "Задача выполнена успешно";

        assertTrue(taskUpdateService.updateTaskStatus(
                run.id(), run.executionId(), TaskStatus.COMPLETED, 100, result
        ));
        assertFalse(taskUpdateService.updateTaskStatus(
                run.id(), run.executionId(), TaskStatus.FAILED, 50, "Ошибка выполнения"
        ));
        assertFalse(taskUpdateService.updateProgress(run.id(), run.executionId(), 50));

        Task savedTask = taskRepository.findById(run.id()).orElseThrow();
        assertAll(
                () -> assertEquals(TaskStatus.COMPLETED, savedTask.getStatus()),
                () -> assertEquals(100, savedTask.getProgress()),
                () -> assertEquals(result, savedTask.getResult())
        );
    }

    private TaskRun createRunningTask() {
        Task task = new Task();
        task.setEventId(UUID.randomUUID());
        task.setName("task");
        task.setDuration(1000L);
        taskRepository.save(task);

        return taskClaimService.claimNextTask().orElseThrow();
    }

}