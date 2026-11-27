package com.zubova.asynctaskservice.service;

import com.zubova.asynctaskservice.model.TaskRun;
import com.zubova.asynctaskservice.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskStatusServiceTest {

    @Mock
    private TaskUpdateService taskUpdateService;

    @InjectMocks
    private TaskStatusService taskStatusService;

    @Test
    void executeTask_whenProgressUpdateIsRejected_shouldStopExecution() {
        TaskRun run = new TaskRun(1L, UUID.randomUUID(), 2000L);

        when(taskUpdateService.updateProgress(eq(run.id()), eq(run.executionId()), anyInt())).thenReturn(false);

        taskStatusService.executeTask(run);

        verify(taskUpdateService).updateProgress(eq(run.id()), eq(run.executionId()), anyInt());
        verifyNoMoreInteractions(taskUpdateService);
    }

    @Test
    void executeTask_whenTaskCompletes_shouldSaveSuccessfulResult() {
        TaskRun run = new TaskRun(1L, UUID.randomUUID(), 100L);
        String result = "Задача выполнена успешно";

        when(taskUpdateService.updateTaskStatus(run.id(), run.executionId(), TaskStatus.COMPLETED, 100, result))
                .thenReturn(true);

        taskStatusService.executeTask(run);

        verify(taskUpdateService).updateTaskStatus(run.id(), run.executionId(), TaskStatus.COMPLETED, 100, result);
        verifyNoMoreInteractions(taskUpdateService);
    }

    @Test
    void executeTask_whenProgressIncreases_shouldSaveProgressOnlyAfterMinimumIncrease() {
        TaskRun run = new TaskRun(1L, UUID.randomUUID(), 5000L);
        String result = "Задача выполнена успешно";

        when(taskUpdateService.updateProgress(eq(run.id()), eq(run.executionId()), anyInt()))
                .thenReturn(true);
        when(taskUpdateService.updateTaskStatus(run.id(), run.executionId(), TaskStatus.COMPLETED, 100, result))
                .thenReturn(true);

        taskStatusService.executeTask(run);

        ArgumentCaptor<Integer> progressCaptor = ArgumentCaptor.forClass(Integer.class);

        verify(taskUpdateService, times(2))
                .updateProgress(eq(run.id()), eq(run.executionId()), progressCaptor.capture());
        assertEquals(List.of(40, 80), progressCaptor.getAllValues());

        verify(taskUpdateService).updateTaskStatus(run.id(), run.executionId(), TaskStatus.COMPLETED, 100, result);
        verifyNoMoreInteractions(taskUpdateService);
    }

    @Test
    void executeTask_whenUnexpectedExceptionOccurs_shouldSaveFailedResult() {
        TaskRun run = new TaskRun(1L, UUID.randomUUID(), 2000L);
        String result = "Ошибка выполнения задачи";

        when(taskUpdateService.updateProgress(run.id(), run.executionId(), 50))
                .thenThrow(new IllegalStateException("Unexpected error"));
        when(taskUpdateService.updateTaskStatus(run.id(), run.executionId(), TaskStatus.FAILED, 50, result))
                .thenReturn(true);

        taskStatusService.executeTask(run);

        verify(taskUpdateService).updateProgress(run.id(), run.executionId(), 50);
        verify(taskUpdateService).updateTaskStatus(run.id(), run.executionId(), TaskStatus.FAILED, 50, result);
        verifyNoMoreInteractions(taskUpdateService);
    }

    @Test
    void executeTask_whenDatabaseIsUnavailable_shouldStopExecution() {
        TaskRun run = new TaskRun(1L, UUID.randomUUID(), 2000L);

        when(taskUpdateService.updateProgress(run.id(), run.executionId(), 50))
                .thenThrow(new DataAccessResourceFailureException("Database unavailable"));

        taskStatusService.executeTask(run);

        verify(taskUpdateService).updateProgress(run.id(), run.executionId(), 50);
        verifyNoMoreInteractions(taskUpdateService);
    }

    @Test
    void executeTask_whenThreadIsInterrupted_shouldSaveFailedResultAndRestoreInterruptFlag() {
        TaskRun run = new TaskRun(1L, UUID.randomUUID(), 2000L);
        String result = "Выполнение прервано";

        when(taskUpdateService.updateTaskStatus(run.id(), run.executionId(), TaskStatus.FAILED, 0, result))
                .thenReturn(true);
        try {
            Thread.currentThread().interrupt();

            taskStatusService.executeTask(run);

            assertTrue(Thread.currentThread().isInterrupted());
            verify(taskUpdateService).updateTaskStatus(run.id(), run.executionId(), TaskStatus.FAILED, 0, result);
            verifyNoMoreInteractions(taskUpdateService);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void executeTask_whenFinalUpdateFails_shouldNotPropagateException() {
        TaskRun run = new TaskRun(1L, UUID.randomUUID(), 100L);
        String result = "Задача выполнена успешно";

        when(taskUpdateService.updateTaskStatus(run.id(), run.executionId(), TaskStatus.COMPLETED, 100, result))
                .thenThrow(new DataAccessResourceFailureException("Database unavailable"));

        assertDoesNotThrow(() -> taskStatusService.executeTask(run));

        verify(taskUpdateService).updateTaskStatus(run.id(), run.executionId(), TaskStatus.COMPLETED, 100, result);
        verifyNoMoreInteractions(taskUpdateService);
    }

}