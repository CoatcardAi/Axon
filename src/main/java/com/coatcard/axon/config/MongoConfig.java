package com.coatcard.axon.config;

import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.reverse.transitions.Start;
import de.flapdoodle.reverse.StateID;
import de.flapdoodle.reverse.TransitionWalker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.Socket;

@Configuration
public class MongoConfig {

    private final Environment env;

    public MongoConfig(Environment env) {
        this.env = env;
    }

    public static class SafeMongoServer {
        private final TransitionWalker.ReachedState<RunningMongodProcess> running;

        public SafeMongoServer(TransitionWalker.ReachedState<RunningMongodProcess> running) {
            this.running = running;
        }

        public void start() {
            // Started during bean initialization
        }

        public void stop() {
            if (running != null) {
                try {
                    running.close();
                    System.out.println("Embedded MongoDB server stopped.");
                } catch (Exception e) {
                    System.err.println("Failed to stop embedded MongoDB: " + e.getMessage());
                }
            }
        }
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SafeMongoServer embeddedMongoServer() {
        String mongoUri = env.getProperty("spring.data.mongodb.uri");
        if (mongoUri != null && (mongoUri.contains("localhost:27017") || mongoUri.contains("127.0.0.1:27017"))) {
            try (Socket socket = new Socket("localhost", 27017)) {
                System.out.println("MongoDB port 27017 is already in use (possibly by Docker or local service). Skipping embedded MongoDB startup.");
                return new SafeMongoServer(null);
            } catch (IOException e) {
                try {
                    System.out.println("Starting embedded MongoDB server on port 27017...");
                    Version.Main version = Version.Main.PRODUCTION;
                    
                    TransitionWalker.ReachedState<RunningMongodProcess> running = Mongod.builder()
                            .net(Start.to(Net.class).initializedWith(Net.defaults().withPort(27017)))
                            .build()
                            .transitions(version)
                            .walker()
                            .initState(StateID.of(RunningMongodProcess.class));
                            
                    System.out.println("Embedded MongoDB server started successfully on port 27017.");
                    return new SafeMongoServer(running);
                } catch (Exception ex) {
                    System.err.println("Failed to start embedded MongoDB: " + ex.getMessage());
                    return new SafeMongoServer(null);
                }
            }
        }
        return new SafeMongoServer(null);
    }
}
