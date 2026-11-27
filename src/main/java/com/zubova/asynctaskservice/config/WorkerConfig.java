package com.zubova.asynctaskservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableScheduling
public class WorkerConfig {

    @Bean(destroyMethod = "")
    public ThreadPoolExecutor taskExecutor(@Value("${worker.pool.size:4}") int poolSize) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("worker.pool.size должен быть положительным");
        }
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                0L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

}