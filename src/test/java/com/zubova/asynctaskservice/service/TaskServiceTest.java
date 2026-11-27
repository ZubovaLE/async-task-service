package com.zubova.asynctaskservice.service;

import com.zubova.asynctaskservice.config.WorkerConfig;
import com.zubova.asynctaskservice.model.TaskRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskExecutionService taskExecutionService;

    @Mock
    private TaskStatusService taskStatusService;

    private ThreadPoolExecutor taskExecutor;
    private TaskService taskService;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (taskExecutor != null) {
            taskExecutor.shutdownNow();
            assertTrue(taskExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void processTask_whenNoTasksExist_shouldNotExecuteTasks() throws InterruptedException {
        initializeTaskService(1);

        when(taskExecutionService.claimNextTask()).thenReturn(Optional.empty());

        taskService.processTask();
        taskExecutor.shutdown();
        assertTrue(taskExecutor.awaitTermination(5, TimeUnit.SECONDS));

        verify(taskExecutionService).claimNextTask();
        verifyNoInteractions(taskStatusService);
    }

    @Test
    void processTask_whenTasksExist_shouldExecuteTasksUntilQueueIsEmpty() throws InterruptedException {
        initializeTaskService(1);

        TaskRun first = new TaskRun(1L, UUID.randomUUID(), 1000L);
        TaskRun second = new TaskRun(2L, UUID.randomUUID(), 1000L);

        when(taskExecutionService.claimNextTask())
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second))
                .thenReturn(Optional.empty());

        taskService.processTask();
        taskExecutor.shutdown();
        assertTrue(taskExecutor.awaitTermination(5, TimeUnit.SECONDS));

        InOrder inOrder = inOrder(taskExecutionService, taskStatusService);
        inOrder.verify(taskExecutionService).claimNextTask();
        inOrder.verify(taskStatusService).executeTask(first);
        inOrder.verify(taskExecutionService).claimNextTask();
        inOrder.verify(taskStatusService).executeTask(second);
        inOrder.verify(taskExecutionService).claimNextTask();

        verifyNoMoreInteractions(taskExecutionService, taskStatusService);
    }

    @Test
    void processTask_whenAllWorkersAreBusy_shouldNotClaimAdditionalTasks() throws InterruptedException {
        initializeTaskService(2);

        TaskRun first = new TaskRun(1L, UUID.randomUUID(), 1000L);
        TaskRun second = new TaskRun(2L, UUID.randomUUID(), 1000L);

        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);

        when(taskExecutionService.claimNextTask())
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second))
                .thenReturn(Optional.empty());

        doAnswer(invocation -> {
            started.countDown();
            release.await();
            completed.countDown();
            return null;
        }).when(taskStatusService).executeTask(any(TaskRun.class));

        try {
            taskService.processTask();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            taskService.processTask();

            verify(taskExecutionService, times(2)).claimNextTask();
            verify(taskStatusService).executeTask(first);
            verify(taskStatusService).executeTask(second);
            assertTrue(taskExecutor.getQueue().isEmpty());
        } finally {
            release.countDown();
        }
        taskExecutor.shutdown();
        assertTrue(taskExecutor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0L, completed.getCount());
        verify(taskStatusService, times(2)).executeTask(any(TaskRun.class));
    }

    @Test
    void shutdown_whenTaskIsRunning_shouldWaitForCompletionAndStopClaiming() throws Exception {
        initializeTaskService(1);

        TaskRun run = new TaskRun(1L, UUID.randomUUID(), 1000L);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        when(taskExecutionService.claimNextTask())
                .thenReturn(Optional.of(run))
                .thenReturn(Optional.empty());

        doAnswer(invocation -> {
            started.countDown();
            release.await();
            completed.countDown();
            return null;
        }).when(taskStatusService).executeTask(run);

        try (ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor()) {
            Future<?> shutdown;

            try {
                taskService.processTask();

                assertTrue(started.await(5, TimeUnit.SECONDS));

                shutdown = shutdownExecutor.submit(taskService::shutdown);

                await().atMost(5, TimeUnit.SECONDS)
                        .until(taskExecutor::isShutdown);

                assertFalse(shutdown.isDone());
            } finally {
                release.countDown();
            }

            shutdown.get(5, TimeUnit.SECONDS);
        }

        taskService.processTask();

        assertAll(
                () -> assertEquals(0L, completed.getCount()),
                () -> assertTrue(taskExecutor.isTerminated())
        );
        verify(taskExecutionService).claimNextTask();
        verify(taskStatusService).executeTask(run);
        verifyNoMoreInteractions(taskExecutionService, taskStatusService);
    }

    private void initializeTaskService(int poolSize) {
        taskExecutor = new WorkerConfig().taskExecutor(poolSize);
        taskService = new TaskService(taskExecutor, taskExecutionService, taskStatusService);
    }

}