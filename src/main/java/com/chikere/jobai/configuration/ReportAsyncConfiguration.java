package com.chikere.jobai.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ReportAsyncConfiguration {

    /**
     * Runs premium report generations off the servlet request threads. The bounded pool + queue
     * doubles as a global budget on concurrent AI generations: when full, submissions are
     * rejected and the client receives 503 rather than the site's thread pool starving.
     */
    @Bean(name = "reportTaskExecutor")
    public ThreadPoolTaskExecutor reportTaskExecutor(
            @Value("${app.report.generation.core-pool-size:2}") int corePoolSize,
            @Value("${app.report.generation.max-pool-size:4}") int maxPoolSize,
            @Value("${app.report.generation.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("report-gen-");
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
