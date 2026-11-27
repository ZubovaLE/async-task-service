package com.zubova.asynctaskservice.repository;

import com.zubova.asynctaskservice.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    Optional<Task> findByEventId(UUID eventId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO tasks (event_id, name, duration)
            VALUES (:eventId, :name, :duration)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("name") String name, @Param("duration") long duration);

    @Query(value = """
            SELECT *
            FROM tasks
            WHERE status = 'NEW'
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Task> findAndLockFirstNewTask();

}