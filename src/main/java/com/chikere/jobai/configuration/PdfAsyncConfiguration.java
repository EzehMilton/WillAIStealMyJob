package com.chikere.jobai.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class PdfAsyncConfiguration {

    @Bean(name = "pdfTaskExecutor")
    public ThreadPoolTaskExecutor pdfTaskExecutor(
            @Value("${app.pdf.rendering.core-pool-size:2}") int corePoolSize,
            @Value("${app.pdf.rendering.max-pool-size:4}") int maxPoolSize,
            @Value("${app.pdf.rendering.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("pdf-render-");
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
