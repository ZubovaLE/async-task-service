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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '5s'")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(TaskClaimService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskClaimServiceTest {

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
    private TaskRepository taskRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAllInBatch();
    }

    @Test
    void claimNextTask_whenNewTasksExist_shouldClaimTaskWithLowestId() {
        Task task1 = createNewTask("task 1", 1000L);
        Task task2 = createNewTask("task 2", 900L);

        TaskRun result = taskClaimService.claimNextTask().orElseThrow();

        Task savedTask1 = taskRepository.findById(task1.getId()).orElseThrow();
        Task savedTask2 = taskRepository.findById(task2.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(task1.getId(), result.id()),
                () -> assertEquals(task1.getDuration(), result.duration()),
                () -> assertEquals(TaskStatus.IN_PROGRESS, savedTask1.getStatus()),
                () -> assertNotNull(savedTask1.getExecutionId()),
                () -> assertEquals(savedTask1.getExecutionId(), result.executionId()),
                () -> assertNotNull(savedTask1.getStartedAt()),
                () -> assertNull(savedTask1.getFinishedAt()),
                () -> assertEquals(0, savedTask1.getProgress()),
                () -> assertNull(savedTask1.getResult()),
                () -> assertEquals(TaskStatus.NEW, savedTask2.getStatus())
        );
    }

    @Test
    void claimNextTask_whenTwoWorkersClaimTwoTasks_shouldClaimDistinctTasks() throws Exception {
        Task task1 = createNewTask("task 1", 1000L);
        Task task2 = createNewTask("task 2", 900L);

        List<TaskRun> results = claimConcurrently().stream()
                .map(Optional::orElseThrow)
                .toList();

        Task savedTask1 = taskRepository.findById(task1.getId()).orElseThrow();
        Task savedTask2 = taskRepository.findById(task2.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(TaskStatus.IN_PROGRESS, savedTask1.getStatus()),
                () -> assertEquals(TaskStatus.IN_PROGRESS, savedTask2.getStatus()),
                () -> assertNotNull(savedTask1.getExecutionId()),
                () -> assertNotNull(savedTask2.getExecutionId()),
                () -> assertNotEquals(savedTask1.getExecutionId(), savedTask2.getExecutionId()),
                () -> assertThat(results).containsExactlyInAnyOrder(
                        new TaskRun(savedTask1.getId(), savedTask1.getExecutionId(), savedTask1.getDuration()),
                        new TaskRun(savedTask2.getId(), savedTask2.getExecutionId(), savedTask2.getDuration())
                )
        );
    }

    @Test
    void claimNextTask_whenTwoWorkersClaimOneTask_shouldClaimOnlyOnce() throws Exception {
        Task task = createNewTask("task", 1000L);

        List<Optional<TaskRun>> results = claimConcurrently();

        Task savedTask = taskRepository.findById(task.getId()).orElseThrow();
        assertAll(
                () -> assertEquals(TaskStatus.IN_PROGRESS, savedTask.getStatus()),
                () -> assertNotNull(savedTask.getExecutionId()),
                () -> assertThat(results).containsExactlyInAnyOrder(
                        Optional.of(new TaskRun(savedTask.getId(), savedTask.getExecutionId(), savedTask.getDuration())),
                        Optional.empty()
                )
        );
    }

    @Test
    void claimNextTask_whenFirstTaskIsLocked_shouldSkipLockedTask() {
        Task task1 = createNewTask("task 1", 1000L);
        Task task2 = createNewTask("task 2", 900L);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            Task lockedTask = taskRepository.findAndLockFirstNewTask().orElseThrow();
            assertEquals(task1.getId(), lockedTask.getId());

            TaskRun result = taskClaimService.claimNextTask().orElseThrow();
            assertEquals(task2.getId(), result.id());
        });

        Task savedTask1 = taskRepository.findById(task1.getId()).orElseThrow();
        Task savedTask2 = taskRepository.findById(task2.getId()).orElseThrow();

        assertAll(
                () -> assertEquals(TaskStatus.NEW, savedTask1.getStatus()),
                () -> assertNull(savedTask1.getExecutionId()),
                () -> assertEquals(TaskStatus.IN_PROGRESS, savedTask2.getStatus()),
                () -> assertNotNull(savedTask2.getExecutionId())
        );
    }

    @Test
    void claimNextTask_whenNoNewTasksExist_shouldReturnEmpty() {
        Task task = createNewTask("task", 1000L);

        task.setStatus(TaskStatus.COMPLETED);
        task.setProgress(100);
        task.setFinishedAt(Instant.now());
        taskRepository.save(task);

        assertTrue(taskClaimService.claimNextTask().isEmpty());
    }

    private List<Optional<TaskRun>> claimConcurrently() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Optional<TaskRun>> claim = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return taskClaimService.claimNextTask();
        };

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<TaskRun>> first = executor.submit(claim);
            Future<Optional<TaskRun>> second = executor.submit(claim);
            return List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
        }
    }

    private Task createNewTask(String name, long duration) {
        Task task = new Task();
        task.setEventId(UUID.randomUUID());
        task.setStatus(TaskStatus.NEW);
        task.setName(name);
        task.setDuration(duration);
        return taskRepository.save(task);
    }

}