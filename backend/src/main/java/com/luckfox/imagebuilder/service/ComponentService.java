package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.dto.ComponentDto;
import com.luckfox.imagebuilder.dto.ComponentRequest;
import com.luckfox.imagebuilder.dto.PageResponse;
import com.luckfox.imagebuilder.entity.Component;
import com.luckfox.imagebuilder.entity.ComponentType;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.ComponentRepository;
import com.luckfox.imagebuilder.repository.ComponentTypeRepository;
import com.luckfox.imagebuilder.repository.ImageRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ComponentService {

    private final ComponentRepository componentRepository;
    private final ComponentTypeRepository componentTypeRepository;
    private final ImageRepository imageRepository;

    /**
     * Страница каталога. Поиск по названию и фильтр по типу применяются совместно
     * (AND); пагинация выполняется в SQL (LIMIT/OFFSET + COUNT).
     */
    @Transactional(readOnly = true)
    public PageResponse<ComponentDto> search(String name, Long typeId, String sort, int page, int size) {
        Specification<Component> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (name != null && !name.isBlank()) {
                where.add(cb.like(cb.lower(root.get("name")), "%" + name.trim().toLowerCase() + "%"));
            }
            if (typeId != null) {
                where.add(cb.equal(root.get("type").get("id"), typeId));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        return PageResponse.from(
                componentRepository.findAll(spec, PageResponse.request(page, size,
                        PageResponse.sort(sort, Sort.by("name", "id")))),
                ComponentDto::from);
    }

    @Transactional(readOnly = true)
    public ComponentDto getDtoById(Long id) {
        return ComponentDto.from(getById(id));
    }

    @Transactional(readOnly = true)
    public Component getById(Long id) {
        return componentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Компонент не найден: " + id));
    }

    @Transactional
    public ComponentDto create(ComponentRequest req) {
        Component c = new Component();
        apply(c, req);
        return ComponentDto.from(componentRepository.save(c));
    }

    @Transactional
    public ComponentDto update(Long id, ComponentRequest req) {
        Component c = getById(id);
        apply(c, req);
        return ComponentDto.from(componentRepository.save(c));
    }

    @Transactional
    public void delete(Long id) {
        Component c = getById(id);
        if (imageRepository.existsByComponentsContains(c)) {
            throw new BadRequestException("Компонент «" + c.getName()
                    + "» входит в образы — сначала измените или удалите эти образы");
        }
        if (componentRepository.existsByDependenciesContains(c)) {
            throw new BadRequestException("От компонента «" + c.getName()
                    + "» зависят другие компоненты — сначала уберите эту зависимость");
        }
        componentRepository.delete(c);
    }

    private void apply(Component c, ComponentRequest req) {
        ComponentType type = componentTypeRepository.findById(req.getTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Тип компонента не найден: " + req.getTypeId()));
        c.setName(req.getName());
        c.setDescription(req.getDescription());
        c.setCost(req.getCost());
        c.setType(type);

        Set<Component> deps = new HashSet<>();
        if (req.getDependencyIds() != null) {
            for (Long depId : req.getDependencyIds()) {
                if (c.getId() != null && depId.equals(c.getId())) {
                    throw new BadRequestException("Компонент не может зависеть сам от себя");
                }
                Component dep = componentRepository.findById(depId)
                        .orElseThrow(() -> new ResourceNotFoundException("Зависимость не найдена: " + depId));
                if (c.getId() != null && dependsOn(dep, c.getId())) {
                    throw new BadRequestException("Циклическая зависимость: «" + dep.getName()
                            + "» уже зависит от «" + c.getName() + "»");
                }
                deps.add(dep);
            }
        }
        c.setDependencies(deps);
    }

    /** true, если from транзитивно зависит от компонента targetId. */
    private boolean dependsOn(Component from, Long targetId) {
        Set<Long> seen = new HashSet<>();
        Deque<Component> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            Component cur = queue.poll();
            if (cur.getDependencies() == null) continue;
            for (Component dep : cur.getDependencies()) {
                if (dep.getId().equals(targetId)) return true;
                if (seen.add(dep.getId())) queue.add(dep);
            }
        }
        return false;
    }
}
