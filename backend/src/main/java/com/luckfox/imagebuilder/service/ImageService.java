package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.dto.BuildRequest;
import com.luckfox.imagebuilder.dto.ImageDto;
import com.luckfox.imagebuilder.dto.PageResponse;
import com.luckfox.imagebuilder.entity.Component;
import com.luckfox.imagebuilder.entity.Image;
import com.luckfox.imagebuilder.entity.User;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.ComponentRepository;
import com.luckfox.imagebuilder.repository.ImageRepository;
import com.luckfox.imagebuilder.repository.UserRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);
    private static final String DEFAULT_BOARD = "RV1103_Luckfox_Pico";

    private final ImageRepository imageRepository;
    private final ComponentRepository componentRepository;
    private final UserRepository userRepository;
    private final BuildService buildService;

    /**
     * Страница библиотеки: поиск по названию + фильтры по статусу, плате и компонентам
     * (образ должен содержать все выбранные компоненты, с учётом транзитивных
     * зависимостей). Сортировка — новые сверху или по цене.
     */
    @Transactional(readOnly = true)
    public PageResponse<ImageDto> search(String name, String state, String board, List<Long> componentIds,
                                         String sort, int page, int size) {
        Specification<Image> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (name != null && !name.isBlank()) {
                where.add(cb.like(cb.lower(root.get("name")), "%" + name.trim().toLowerCase() + "%"));
            }
            if (state != null && !state.isBlank()) {
                where.add(cb.equal(root.get("state"), state.toUpperCase()));
            }
            if (board != null && !board.isBlank()) {
                where.add(cb.equal(root.get("board"), board));
            }
            if (componentIds != null) {
                // По EXISTS-подзапросу на каждый компонент — без join-дублей в основной выборке.
                for (Long componentId : new LinkedHashSet<>(componentIds)) {
                    Subquery<Long> sq = query.subquery(Long.class);
                    Root<Image> img = sq.from(Image.class);
                    Join<Image, Component> comp = img.join("components");
                    sq.select(img.get("id")).where(
                            cb.equal(img.get("id"), root.get("id")),
                            cb.equal(comp.get("id"), componentId));
                    where.add(cb.exists(sq));
                }
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        Sort order = PageResponse.sort(sort, Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.from(
                imageRepository.findAll(spec, PageResponse.request(page, size, order)),
                ImageDto::from);
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

        Image image = new Image();
        applyAndQueue(image, req);
        image = imageRepository.save(image);

        // Привязка образа к пользователю (user_image_order).
        if (user.getOrderedImages() == null) {
            user.setOrderedImages(new HashSet<>());
        }
        user.getOrderedImages().add(image);
        userRepository.save(user);

        scheduleBuild(image.getId());
        return ImageDto.from(image);
    }

    /**
     * Изменение образа администратором: новые параметры и состав, пересчёт
     * зависимостей и повторная постановка в очередь сборки.
     */
    @Transactional
    public ImageDto update(Long id, BuildRequest req) {
        Image image = getById(id);
        if ("QUEUED".equals(image.getState()) || "BUILDING".equals(image.getState())) {
            throw new BadRequestException("Образ сейчас в очереди или собирается — дождитесь окончания сборки");
        }
        String oldArtifact = image.getArtifactPath();
        applyAndQueue(image, req);
        image.setArtifactPath(null);
        image.setBuildLog(null);
        image = imageRepository.save(image);

        afterCommit(() -> deleteArtifact(oldArtifact));
        scheduleBuild(image.getId());
        return ImageDto.from(image);
    }

    @Transactional
    public void delete(Long id) {
        Image image = getById(id);
        if ("BUILDING".equals(image.getState())) {
            throw new BadRequestException("Образ сейчас собирается — удалить его можно после окончания сборки");
        }
        // Связь «пользователь заказал образ» принадлежит User — снимаем её до удаления.
        for (User u : userRepository.findByOrderedImagesContains(image)) {
            u.getOrderedImages().remove(image);
        }
        String artifact = image.getArtifactPath();
        imageRepository.delete(image);
        afterCommit(() -> deleteArtifact(artifact));
    }

    /** Общая часть создания и изменения: поля, разрешение зависимостей, стоимость, QUEUED. */
    private void applyAndQueue(Image image, BuildRequest req) {
        Set<Component> selected = new LinkedHashSet<>();
        for (Long id : req.getComponentIds()) {
            selected.add(componentRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Компонент не найден: " + id)));
        }

        // Транзитивное замыкание зависимостей + проверка на циклы.
        Set<Component> resolved = resolveDependencies(selected);
        detectCycles(resolved);

        image.setName(req.getName());
        image.setDescription(req.getDescription());
        image.setDistro(req.getDistro());
        image.setBoard(req.getBoard() == null || req.getBoard().isBlank()
                ? DEFAULT_BOARD : req.getBoard());
        image.setState("QUEUED");
        image.setCost(resolved.stream().mapToLong(Component::getCost).sum());
        image.setComponents(resolved);
    }

    /**
     * Фоновую сборку запускаем строго ПОСЛЕ коммита транзакции, иначе
     * async-поток может не найти ещё не сохранённый образ.
     */
    private void scheduleBuild(Long imageId) {
        afterCommit(() -> buildService.build(imageId));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void deleteArtifact(String artifactPath) {
        if (artifactPath == null) return;
        try {
            Files.deleteIfExists(Paths.get(artifactPath));
        } catch (IOException e) {
            log.warn("Не удалось удалить артефакт {}: {}", artifactPath, e.getMessage());
        }
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
