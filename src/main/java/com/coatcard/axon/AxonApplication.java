package com.coatcard.axon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class AxonApplication {

    public static void main(String[] args) {
        loadDotenv();
        SpringApplication.run(AxonApplication.class, args);
    }

    public static void loadDotenv() {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(".env");
            if (!java.nio.file.Files.exists(path)) {
                path = java.nio.file.Paths.get("../.env");
            }
            if (java.nio.file.Files.exists(path)) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(path);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int equalsIndex = line.indexOf('=');
                    if (equalsIndex > 0) {
                        String key = line.substring(0, equalsIndex).trim();
                        String value = line.substring(equalsIndex + 1).trim();
                        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                            value = value.substring(1, value.length() - 1);
                        } else if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                            value = value.substring(1, value.length() - 1);
                        }
                        System.setProperty(key, value);
                        
                        // Map directly to Spring Boot properties as well
                        if ("SPRING_DATA_MONGODB_URI".equals(key)) {
                            System.setProperty("spring.data.mongodb.uri", value);
                        } else if ("SPRING_DATA_REDIS_URL".equals(key)) {
                            System.setProperty("spring.data.redis.url", value);
                        }
                    }
                }
                System.out.println("Successfully loaded environment variables from " + path.toAbsolutePath());
            } else {
                System.out.println("No .env file found. Proceeding with system environment variables.");
            }
        } catch (Exception e) {
            System.err.println("Failed to load .env file: " + e.getMessage());
        }
    }

}
