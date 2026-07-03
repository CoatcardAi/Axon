package com.coatcard.axon.config;

import com.coatcard.axon.model.AiModel;
import com.coatcard.axon.model.ApiKey;
import com.coatcard.axon.model.Provider;
import com.coatcard.axon.model.User;
import com.coatcard.axon.repository.AiModelRepository;
import com.coatcard.axon.repository.ApiKeyRepository;
import com.coatcard.axon.repository.ProviderRepository;
import com.coatcard.axon.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    private static final String GEMINI_PROVIDER = "gemini";

    // Verified working Gemini models as of July 2026.
    // Gemini 1.5, 2.0 were shut down June 2026. Gemma aliases and
    // gemini-flash-latest style aliases are not stable model IDs.
    private static final List<String> GEMINI_MODELS = List.of(
            "gemini-3.5-flash",       // Current flagship, fastest for complex tasks
            "gemini-2.5-flash",       // Stable production model
            "gemini-2.5-flash-lite",  // Fastest, cheapest — use for simple prompts
            "gemini-2.5-pro"          // Largest, most capable
    );


    private final UserRepository userRepository;
    private final ProviderRepository providerRepository;
    private final AiModelRepository modelRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${gemini.api.keys:}")
    private String geminiApiKeys;

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
        java.util.Optional<User> existingAdmin =
                userRepository.findByUsername("kumaranand43856@gmail.com");

        if (existingAdmin.isPresent()) {
            User admin = existingAdmin.get();

            if (!admin.getRoles().contains("ROLE_ADMIN")) {
                admin.setRoles(Set.of("ROLE_ADMIN"));
                userRepository.save(admin);
                System.out.println("Promoted existing user kumaranand43856@gmail.com to ROLE_ADMIN");
            }
        } else {
            User admin = User.builder()
                    .username("kumaranand43856@gmail.com")
                    .password(passwordEncoder.encode("admin123"))
                    .roles(Set.of("ROLE_ADMIN"))
                    .build();

            userRepository.save(admin);
            System.out.println("Default admin user seeded: kumaranand43856@gmail.com / admin123");
        }

        java.util.Optional<User> existingDhriti =
                userRepository.findByUsername("dhriti44nayyar@gmail.com");

        if (existingDhriti.isPresent()) {
            User admin = existingDhriti.get();

            if (!admin.getRoles().contains("ROLE_ADMIN")) {
                admin.setRoles(Set.of("ROLE_ADMIN"));
                userRepository.save(admin);
                System.out.println("Promoted existing user dhriti44nayyar@gmail.com to ROLE_ADMIN");
            }
        } else {
            User admin = User.builder()
                    .username("dhriti44nayyar@gmail.com")
                    .password(passwordEncoder.encode("admin123"))
                    .roles(Set.of("ROLE_ADMIN"))
                    .build();

            userRepository.save(admin);
            System.out.println("Dhriti admin user seeded: dhriti44nayyar@gmail.com / admin123");
        }

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
        providerRepository.deleteAll();

        Provider geminiProvider = Provider.builder()
                .name(GEMINI_PROVIDER)
                .displayName("Google Gemini")
                .active(true)
                .build();

        providerRepository.save(geminiProvider);

        System.out.println("Gemini provider seeded.");
    }

    private void seedModels() {
        modelRepository.deleteAll();

        List<AiModel> models = GEMINI_MODELS.stream()
                .map(modelName -> AiModel.builder()
                        .provider(GEMINI_PROVIDER)
                        .name(modelName)
                        .displayName(modelName)
                        .active(true)
                        .build())
                .toList();

        modelRepository.saveAll(models);

        System.out.println("Gemini-only AI models seeded: " + models.size());
    }

    private void seedApiKeys() {
        apiKeyRepository.deleteAll();

        if (geminiApiKeys == null || geminiApiKeys.isBlank()) {
            System.out.println("No Gemini API keys found. Please configure gemini.api.keys.");
            return;
        }

        List<String> keys = Arrays.stream(geminiApiKeys.split(","))
                .map(String::trim)
                .filter(key -> !key.isBlank())
                .toList();

        List<ApiKey> apiKeys = new ArrayList<>();

        for (int i = 0; i < keys.size(); i++) {
            ApiKey apiKey = ApiKey.builder()
                    .name("Gemini API Key " + (i + 1))
                    .provider(GEMINI_PROVIDER)
                    .keyValue(keys.get(i))
                    .models(GEMINI_MODELS)
                    .limitRpm(100)
                    .limitTpm(200000)
                    .cooldownDurationSeconds(10)
                    .active(true)
                    .metadata(Map.of(
                            "tier", "gemini",
                            "instance", i + 1
                    ))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            apiKeys.add(apiKey);
        }

        apiKeyRepository.saveAll(apiKeys);

        System.out.println("Gemini API keys seeded: " + apiKeys.size());
    }
}