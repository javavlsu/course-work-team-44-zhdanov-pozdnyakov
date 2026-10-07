package com.luckfox.imagebuilder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Очередь сборок: ровно ОДИН воркер. Пока собирается образ одного пользователя,
     * остальные заказы ждут в очереди (FIFO) в статусе QUEUED и берутся по одному.
     *
     * Это обязательно: реальная сборка SDK использует единый рабочий каталог
     * (luckfox-pico/output, .BoardConfig.mk) — параллельные сборки повредили бы друг друга.
     */
    @Bean(name = "buildExecutor")
    public Executor buildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);          // строго один воркер — настоящая очередь
        executor.setQueueCapacity(1000);     // ожидающие заказы
        executor.setThreadNamePrefix("build-worker-");
        executor.initialize();
        return executor;
    }
}
