package com.papyrus;

import com.papyrus.config.ExportProperties;
import com.papyrus.config.ExistProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ExportProperties.class, ExistProperties.class})
public class CoreBackend {
    public static void main(String[] args) {
        SpringApplication.run(CoreBackend.class, args);
    }
}
