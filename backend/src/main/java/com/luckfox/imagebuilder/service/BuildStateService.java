package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.entity.Component;
import com.luckfox.imagebuilder.entity.Image;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Транзакционные операции над состоянием образа, вызываемые из фонового
 * {@link BuildService}. Вынесено отдельно, чтобы избежать самовызова
 * @Transactional/@Async и циклической зависимости с ImageService.
 */
@Service
@RequiredArgsConstructor
public class BuildStateService {

    private final ImageRepository imageRepository;

    /** Снимок входных данных сборки (читается в транзакции, lazy-коллекция инициализируется). */
    public record BuildInputs(Long id, String name, String distro, String board, List<String> components) {}

    @Transactional(readOnly = true)
    public BuildInputs loadInputs(Long imageId) {
        Image image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("Образ не найден: " + imageId));
        List<String> names = image.getComponents() == null ? List.of()
                : image.getComponents().stream().map(Component::getName).sorted().collect(Collectors.toList());
        return new BuildInputs(image.getId(), image.getName(), image.getDistro(), image.getBoard(), names);
    }

    @Transactional
    public void markBuilding(Long imageId) {
        setState(imageId, "BUILDING", null, null);
    }

    @Transactional
    public void markReady(Long imageId, String artifactPath, String log) {
        setState(imageId, "READY", artifactPath, log);
    }

    @Transactional
    public void markFailed(Long imageId, String log) {
        setState(imageId, "FAILED", null, log);
    }

    private void setState(Long imageId, String state, String artifactPath, String log) {
        Image image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ResourceNotFoundException("Образ не найден: " + imageId));
        image.setState(state);
        if (artifactPath != null) {
            image.setArtifactPath(artifactPath);
        }
        if (log != null) {
            image.setBuildLog(log);
        }
        imageRepository.save(image);
    }
}
