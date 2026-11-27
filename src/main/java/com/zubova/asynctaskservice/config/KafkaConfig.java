
package com.zubova.asynctaskservice.config;

import com.zubova.asynctaskservice.dto.TaskRequestDTO;
import com.zubova.asynctaskservice.exception.InvalidTaskMessageException;
import jakarta.validation.ConstraintViolationException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.serializer.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.sql.SQLTransientConnectionException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:task-group}")
    private String groupId;

    @Value("${kafka.listener.concurrency:1}")
    private int concurrency;

    @Bean
    public ConsumerFactory<String, TaskRequestDTO> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);

        JsonDeserializer<TaskRequestDTO> json = new JsonDeserializer<>(TaskRequestDTO.class, false);

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new ErrorHandlingDeserializer<>(json));
    }

    @Bean
    public ProducerFactory<String, Object> dltProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        Map<Class<?>, Serializer<?>> serializers = new HashMap<>();
        serializers.put(byte[].class, new ByteArraySerializer());
        serializers.put(TaskRequestDTO.class, new JsonSerializer<>());

        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new DelegatingByTypeSerializer(serializers));
    }

    @Bean
    public KafkaTemplate<String, Object> dltKafkaTemplate(ProducerFactory<String, Object> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    @Bean
    public ThreadPoolTaskScheduler kafkaRetryScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kafka-retry-");
        return scheduler;
    }

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> dltKafkaTemplate,
                                                ThreadPoolTaskScheduler kafkaRetryScheduler) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dltKafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition())
        );
        recoverer.setFailIfSendResultIsError(true);

        DefaultErrorHandler dltHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
        DefaultErrorHandler databaseHandler = createDatabaseErrorHandler(kafkaRetryScheduler);
        return createDelegatingErrorHandler(dltHandler, databaseHandler);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskRequestDTO> kafkaListenerContainerFactory(
            ConsumerFactory<String, TaskRequestDTO> consumerFactory, CommonErrorHandler kafkaErrorHandler) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("Kafka concurrency должна быть положительной");
        }

        ConcurrentKafkaListenerContainerFactory<String, TaskRequestDTO> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    private DefaultErrorHandler createDatabaseErrorHandler(ThreadPoolTaskScheduler kafkaRetryScheduler) {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);

        ListenerContainerPauseService pauseService = new ListenerContainerPauseService(null, kafkaRetryScheduler);
        ContainerPausingBackOffHandler pausingBackOffHandler = new ContainerPausingBackOffHandler(pauseService);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> {
                    throw new IllegalStateException("Database retry exhausted", exception);
                },
                backOff,
                pausingBackOffHandler
        );

        handler.setResetStateOnExceptionChange(false);
        handler.setResetStateOnRecoveryFailure(true);

        return handler;
    }

    private CommonDelegatingErrorHandler createDelegatingErrorHandler(DefaultErrorHandler dltHandler,
                                                                      DefaultErrorHandler databaseHandler) {
        CommonDelegatingErrorHandler handler = new CommonDelegatingErrorHandler(new CommonContainerStoppingErrorHandler());

        handler.setCauseChainTraversing(true);

        Map<Class<? extends Throwable>, CommonErrorHandler> delegates = new LinkedHashMap<>();
        delegates.put(DeserializationException.class, dltHandler);
        delegates.put(ConstraintViolationException.class, dltHandler);
        delegates.put(InvalidTaskMessageException.class, dltHandler);
        delegates.put(DataAccessResourceFailureException.class, databaseHandler);
        delegates.put(TransientDataAccessException.class, databaseHandler);
        delegates.put(JDBCConnectionException.class, databaseHandler);
        delegates.put(SQLTransientConnectionException.class, databaseHandler);

        handler.setErrorHandlers(delegates);

        return handler;
    }

}