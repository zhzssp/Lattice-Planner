package org.zhzssp.memorandum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// 程序主入口，可从浏览器访问http://localhost:8080
@EnableAsync
@EnableScheduling // 主动式 Agent 晨报/晚报：开启 @Scheduled 定时预生成
@SpringBootApplication
public class MemorandumApplication {
    public static void main(String[] args) {
        // 返回值是一个ConfigurableApplicationContext对象 --> Spring容器，可以获取任意管理的Bean
        SpringApplication.run(MemorandumApplication.class, args);
    }
}

