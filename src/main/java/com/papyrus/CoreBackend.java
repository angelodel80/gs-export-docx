package com.papyrus;

import com.papyrus.config.ExportProperties;
import com.papyrus.config.ExistProperties;
import com.papyrus.config.AsyncExportProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ExportProperties.class, ExistProperties.class, AsyncExportProperties.class})
public class CoreBackend {
    public static void main(String[] args) {
        SpringApplication.run(CoreBackend.class, args);
    }
}
