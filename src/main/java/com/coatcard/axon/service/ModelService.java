package com.coatcard.axon.service;

import com.coatcard.axon.model.AiModel;
import com.coatcard.axon.repository.AiModelRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ModelService {

    private final AiModelRepository modelRepository;

    public ModelService(AiModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    public List<AiModel> getAllModels() {
        return modelRepository.findAll();
    }

    public List<AiModel> getModelsByProvider(String provider) {
        return modelRepository.findByProvider(provider);
    }

    public Optional<AiModel> getModelById(String id) {
        return modelRepository.findById(id);
    }

    public AiModel createModel(AiModel model) {
        if (model.getId() != null && model.getId().isBlank()) {
            model.setId(null);
        }
        if (model.getModelName() == null || model.getModelName().isBlank()) {
            model.setModelName(model.getName());
        }
        model.setEnabled(model.isActive());
        return modelRepository.save(model);
    }

    public AiModel updateModel(String id, AiModel details) {
        AiModel existing = modelRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Model not found with id: " + id));

        existing.setName(details.getName());
        existing.setModelName(details.getName()); // Sync modelName with name
        existing.setProvider(details.getProvider());
        existing.setDisplayName(details.getDisplayName());
        existing.setActive(details.isActive());
        existing.setEnabled(details.isActive()); // Sync enabled with active
        existing.setPriority(details.getPriority());

        return modelRepository.save(existing);
    }

    public void deleteModel(String id) {
        modelRepository.deleteById(id);
    }
}
