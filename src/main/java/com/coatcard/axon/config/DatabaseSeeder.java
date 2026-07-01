package com.coatcard.axon.config;

import com.coatcard.axon.model.AiModel;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.Provider;
import com.coatcard.axon.model.User;
import com.coatcard.axon.repository.AiModelRepository;
import com.coatcard.axon.repository.ApiKeyRepository;
import com.coatcard.axon.repository.ProviderRepository;
import com.coatcard.axon.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ProviderRepository providerRepository;
    private final AiModelRepository modelRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;

    public DatabaseSeeder(UserRepository userRepository,
                          ProviderRepository providerRepository,
                          AiModelRepository modelRepository,
                          ApiKeyRepository apiKeyRepository,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        try {
            seedUsers();
            seedProviders();
            seedModels();
            seedApiKeys();
        } catch (Exception e) {
            System.err.println("Database seeding failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void seedUsers() {
        // Seed Admin User
        if (userRepository.findByUsername("kumaranand43856@gmail.com").isEmpty()) {
            User admin = User.builder()
                    .username("kumaranand43856@gmail.com")
                    .password(passwordEncoder.encode("admin123"))
                    .roles(Set.of("ROLE_ADMIN"))
                    .build();
            userRepository.save(admin);
            System.out.println("Default admin user seeded: kumaranand43856@gmail.com / admin123");
        }

        // Seed Client User
        if (userRepository.findByUsername("client@axon.com").isEmpty()) {
            User client = User.builder()
                    .username("client@axon.com")
                    .password(passwordEncoder.encode("client123"))
                    .roles(Set.of("ROLE_CLIENT"))
                    .build();
            userRepository.save(client);
            System.out.println("Default client user seeded: client@axon.com / client123");
        }
    }

    private void seedProviders() {
        if (providerRepository.count() == 0) {
            providerRepository.save(Provider.builder().name("openai").displayName("OpenAI").active(true).build());
            providerRepository.save(Provider.builder().name("anthropic").displayName("Anthropic").active(true).build());
            providerRepository.save(Provider.builder().name("gemini").displayName("Google Gemini").active(true).build());
            providerRepository.save(Provider.builder().name("cohere").displayName("Cohere").active(true).build());
            System.out.println("Default AI Providers seeded.");
        }
    }

    private void seedModels() {
        if (modelRepository.count() == 0) {
            // OpenAI Models
            modelRepository.save(AiModel.builder().provider("openai").name("gpt-4o").displayName("GPT-4o").active(true).build());
            modelRepository.save(AiModel.builder().provider("openai").name("gpt-4-turbo").displayName("GPT-4 Turbo").active(true).build());
            modelRepository.save(AiModel.builder().provider("openai").name("gpt-3.5-turbo").displayName("GPT-3.5 Turbo").active(true).build());

            // Anthropic Models
            modelRepository.save(AiModel.builder().provider("anthropic").name("claude-3-5-sonnet").displayName("Claude 3.5 Sonnet").active(true).build());
            modelRepository.save(AiModel.builder().provider("anthropic").name("claude-3-opus").displayName("Claude 3 Opus").active(true).build());

            // Gemini Models
            modelRepository.save(AiModel.builder().provider("gemini").name("gemini-1.5-pro").displayName("Gemini 1.5 Pro").active(true).build());
            modelRepository.save(AiModel.builder().provider("gemini").name("gemini-1.5-flash").displayName("Gemini 1.5 Flash").active(true).build());

            System.out.println("Default AI Models seeded.");
        }
    }

    private void seedApiKeys() {
        if (apiKeyRepository.count() == 0) {
            List<ApiKey> defaultKeys = new ArrayList<>();

            // Seed mock keys for testing routing and load balancing (need 100+ keys capability, let's seed 15 active keys initially)
            for (int i = 1; i <= 5; i++) {
                defaultKeys.add(ApiKey.builder()
                        .name("OpenAI Production Key " + i)
                        .provider("openai")
                        .keyValue("sk-mock-openai-key-prod-00" + i + "-xyz123abc456")
                        .models(List.of("gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo"))
                        .limitRpm(60) // 1 request per second average
                        .limitTpm(100000)
                        .cooldownDurationSeconds(15)
                        .active(true)
                        .metadata(Map.of("tier", "enterprise", "instance", i))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build());
            }

            for (int i = 1; i <= 5; i++) {
                defaultKeys.add(ApiKey.builder()
                        .name("Anthropic Production Key " + i)
                        .provider("anthropic")
                        .keyValue("sk-ant-mock-key-prod-00" + i + "-xyz123abc456")
                        .models(List.of("claude-3-5-sonnet", "claude-3-opus"))
                        .limitRpm(30)
                        .limitTpm(50000)
                        .cooldownDurationSeconds(20)
                        .active(true)
                        .metadata(Map.of("tier", "pay-as-you-go", "instance", i))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build());
            }

            for (int i = 1; i <= 5; i++) {
                defaultKeys.add(ApiKey.builder()
                        .name("Gemini Production Key " + i)
                        .provider("gemini")
                        .keyValue("gemini-mock-key-prod-00" + i + "-xyz123abc456")
                        .models(List.of("gemini-1.5-pro", "gemini-1.5-flash"))
                        .limitRpm(100)
                        .limitTpm(200000)
                        .cooldownDurationSeconds(10)
                        .active(true)
                        .metadata(Map.of("tier", "free-tier", "instance", i))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build());
            }

            apiKeyRepository.saveAll(defaultKeys);
            System.out.println("Sample API Keys seeded for OpenAI, Anthropic, and Gemini (15 keys total).");
        }
    }
}
