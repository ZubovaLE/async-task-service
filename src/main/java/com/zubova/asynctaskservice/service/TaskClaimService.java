package com.zubova.asynctaskservice.service;

import com.zubova.asynctaskservice.model.TaskRun;
import com.zubova.asynctaskservice.model.TaskStatus;
import com.zubova.asynctaskservice.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskClaimService {

    private final TaskRepository taskRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public Optional<TaskRun> claimNextTask() {
        return taskRepository.findAndLockFirstNewTask()
                .map(task -> {
                    task.setStatus(TaskStatus.IN_PROGRESS);
                    task.setExecutionId(UUID.randomUUID());
                    task.setStartedAt(Instant.now());
                    task.setFinishedAt(null);
                    task.setProgress(0);
                    task.setResult(null);
                    taskRepository.saveAndFlush(task);

                    return new TaskRun(task.getId(), task.getExecutionId(), task.getDuration());
                });
    }

}