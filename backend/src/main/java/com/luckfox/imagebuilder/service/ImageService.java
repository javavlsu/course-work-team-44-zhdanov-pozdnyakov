package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.dto.BuildRequest;
import com.luckfox.imagebuilder.dto.ImageDto;
import com.luckfox.imagebuilder.entity.Component;
import com.luckfox.imagebuilder.entity.Image;
import com.luckfox.imagebuilder.entity.User;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.ComponentRepository;
import com.luckfox.imagebuilder.repository.ImageRepository;
import com.luckfox.imagebuilder.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImageService {

    private static final String DEFAULT_BOARD = "RV1103_Luckfox_Pico";

    private final ImageRepository imageRepository;
    private final ComponentRepository componentRepository;
    private final UserRepository userRepository;
    private final BuildService buildService;

    @Transactional(readOnly = true)
    public List<ImageDto> findAllDto() {
        return imageRepository.findAll().stream().map(ImageDto::from).toList();
    }

    @Transactional(readOnly = true)
    public ImageDto getDtoById(Long id) {
        return ImageDto.from(getById(id));
    }

    @Transactional(readOnly = true)
    public Image getById(Long id) {
        return imageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Образ не найден: " + id));
    }

    /**
     * Заказ сборки: разрешает транзитивные зависимости выбранных компонентов,
     * проверяет граф на циклы, сохраняет образ в статусе QUEUED, привязывает его
     * к пользователю-заказчику и запускает фоновую сборку.
     */
    @Transactional
    public ImageDto orderBuild(BuildRequest req, String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Пользователь не найден: " + userEmail));

        // Загружаем явно выбранные компоненты.
        Set<Component> selected = new LinkedHashSet<>();
        for (Long id : req.getComponentIds()) {
            selected.add(componentRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Компонент не найден: " + id)));
        }

        // Транзитивное замыкание зависимостей + проверка на циклы.
        Set<Component> resolved = resolveDependencies(selected);
        detectCycles(resolved);

        long totalCost = resolved.stream().mapToLong(Component::getCost).sum();

        Image image = new Image();
        image.setName(req.getName());
        image.setDescription(req.getDescription());
        image.setDistro(req.getDistro());
        image.setBoard(req.getBoard() == null || req.getBoard().isBlank()
                ? DEFAULT_BOARD : req.getBoard());
        image.setState("QUEUED");
        image.setCost(totalCost);
        image.setComponents(resolved);
        image = imageRepository.save(image);

        // Привязка образа к пользователю (user_image_order).
        if (user.getOrderedImages() == null) {
            user.setOrderedImages(new HashSet<>());
        }
        user.getOrderedImages().add(image);
        userRepository.save(user);

        // Фоновую сборку запускаем строго ПОСЛЕ коммита транзакции, иначе
        // async-поток может не найти ещё не сохранённый образ.
        final Long imageId = image.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    buildService.build(imageId);
                }
            });
        } else {
            buildService.build(imageId);
        }

        return ImageDto.from(image);
    }

    /** BFS по графу зависимостей: возвращает выбранные компоненты + все требуемые ими. */
    private Set<Component> resolveDependencies(Set<Component> selected) {
        Set<Component> result = new LinkedHashSet<>();
        Deque<Component> queue = new ArrayDeque<>(selected);
        while (!queue.isEmpty()) {
            Component c = queue.poll();
            if (!result.add(c)) {
                continue;
            }
            if (c.getDependencies() != null) {
                for (Component dep : c.getDependencies()) {
                    if (!result.contains(dep)) {
                        queue.add(dep);
                    }
                }
            }
        }
        return result;
    }

    /** DFS-детекция циклов в графе зависимостей замыкания. */
    private void detectCycles(Set<Component> components) {
        Set<Long> visited = new HashSet<>();
        Set<Long> inStack = new HashSet<>();
        for (Component c : components) {
            if (!visited.contains(c.getId())) {
                dfs(c, visited, inStack);
            }
        }
    }

    private void dfs(Component c, Set<Long> visited, Set<Long> inStack) {
        visited.add(c.getId());
        inStack.add(c.getId());
        if (c.getDependencies() != null) {
            for (Component dep : c.getDependencies()) {
                if (inStack.contains(dep.getId())) {
                    throw new BadRequestException(
                            "Обнаружена циклическая зависимость с участием компонента '" + c.getName() + "'");
                }
                if (!visited.contains(dep.getId())) {
                    dfs(dep, visited, inStack);
                }
            }
        }
        inStack.remove(c.getId());
    }
}
