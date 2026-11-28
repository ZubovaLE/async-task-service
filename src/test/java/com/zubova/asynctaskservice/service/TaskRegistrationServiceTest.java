
package com.zubova.asynctaskservice.service;

import com.zubova.asynctaskservice.dto.TaskRequestDTO;
import com.zubova.asynctaskservice.exception.InvalidTaskMessageException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(TaskRegistrationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskRegistrationServiceTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TaskRegistrationService taskRegistrationService;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAllInBatch();
    }

    @Test
    void register_whenNewEvent_shouldCreateTask() {
        UUID eventId = UUID.randomUUID();

        taskRegistrationService.register(new TaskRequestDTO(eventId, "task", 1000L));

        Task task = taskRepository.findByEventId(eventId).orElseThrow();
        assertAll(
                () -> assertEquals(eventId, task.getEventId()),
                () -> assertEquals(TaskStatus.NEW, task.getStatus()),
                () -> assertEquals("task", task.getName()),
                () -> assertEquals(1000L, task.getDuration()),
                () -> assertEquals(0, task.getProgress()),
                () -> assertEquals(1L, taskRepository.count())
        );
    }

    @Test
    void register_whenDuplicateEvent_shouldNotCreateAnotherTask() {
        UUID eventId = UUID.randomUUID();
        TaskRequestDTO request = new TaskRequestDTO(eventId, "task", 1000L);

        taskRegistrationService.register(request);
        taskRegistrationService.register(request);

        assertEquals(1L, taskRepository.count());
    }

    @Test
    void register_whenNormalizedNameMatches_shouldNotDuplicateTask() {
        UUID eventId = UUID.randomUUID();

        TaskRequestDTO request1 = new TaskRequestDTO(eventId, "task", 1000L);
        taskRegistrationService.register(request1);

        TaskRequestDTO request2 = new TaskRequestDTO(eventId, "  task ", 1000L);
        taskRegistrationService.register(request2);

        assertEquals(1L, taskRepository.count());
    }

    @Test
    void register_whenNameConflicts_shouldRejectEvent() {
        UUID eventId = UUID.randomUUID();
        String name = "task";
        long duration = 1000L;

        taskRegistrationService.register(new TaskRequestDTO(eventId, name, duration));

        assertThrows(
                InvalidTaskMessageException.class,
                () -> taskRegistrationService.register(new TaskRequestDTO(eventId, "another-name", duration))
        );

        Task savedTask = taskRepository.findByEventId(eventId).orElseThrow();
        assertAll(
                () -> assertEquals(name, savedTask.getName()),
                () -> assertEquals(duration, savedTask.getDuration()),
                () -> assertEquals(1L, taskRepository.count())
        );
    }

    @Test
    void register_whenDurationConflicts_shouldRejectEvent() {
        UUID eventId = UUID.randomUUID();
        String name = "task";
        long duration = 1000L;

        taskRegistrationService.register(new TaskRequestDTO(eventId, name, duration));

        assertThrows(
                InvalidTaskMessageException.class,
                () -> taskRegistrationService.register(new TaskRequestDTO(eventId, name, 900L))
        );

        Task savedTask = taskRepository.findByEventId(eventId).orElseThrow();
        assertAll(
                () -> assertEquals(name, savedTask.getName()),
                () -> assertEquals(duration, savedTask.getDuration()),
                () -> assertEquals(1L, taskRepository.count())
        );
    }

    @Test
    void register_whenConcurrentDuplicateEvents_shouldCreateOnlyOneTask() throws Exception {
        UUID eventId = UUID.randomUUID();
        String name = "task";
        long duration = 1000L;
        int threads = 8;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            try {
                for (int i = 0; i < threads; i++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();

                        taskRegistrationService.register(new TaskRequestDTO(eventId, name, duration));
                        return null;
                    }));
                }

                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();

                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            } finally {
                start.countDown();
            }
        }

        Task savedTask = taskRepository.findByEventId(eventId).orElseThrow();

        assertAll(
                () -> assertEquals(name, savedTask.getName()),
                () -> assertEquals(duration, savedTask.getDuration()),
                () -> assertEquals(1L, taskRepository.count())
        );
    }

    @Test
    void register_whenDifferentEventsHaveSameParameters_shouldCreateTwoTasks() {
        taskRegistrationService.register(new TaskRequestDTO(UUID.randomUUID(), "task", 1000L));
        taskRegistrationService.register(new TaskRequestDTO(UUID.randomUUID(), "task", 1000L));

        assertEquals(2L, taskRepository.count());
    }

}