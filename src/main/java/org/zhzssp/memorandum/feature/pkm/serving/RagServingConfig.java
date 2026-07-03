package org.zhzssp.memorandum.feature.pkm.serving;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * RAG Serving 线程池配置（R3）。
 *
 * <p>独立线程池，避免检索异步/预取任务占用 WebSocket 或主业务线程。
 * 有界队列 + CallerRunsPolicy 防止过载时撑爆内存。</p>
 */
@Configuration
public class RagServingConfig {

    @Value("${pkm.rag.serving.pool.core-size:1}")
    private int coreSize;

    @Value("${pkm.rag.serving.pool.max-size:2}")
    private int maxSize;

    @Value("${pkm.rag.serving.pool.queue-capacity:64}")
    private int queueCapacity;

    @Bean("ragExecutor")
    public Executor ragExecutor() {
        return new ThreadPoolExecutor(
                Math.max(1, coreSize),
                Math.max(coreSize, maxSize),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> {
                    Thread t = new Thread(r, "rag-serving");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
