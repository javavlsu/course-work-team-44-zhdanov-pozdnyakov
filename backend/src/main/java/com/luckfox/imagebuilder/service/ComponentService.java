package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.dto.ComponentDto;
import com.luckfox.imagebuilder.dto.ComponentRequest;
import com.luckfox.imagebuilder.entity.Component;
import com.luckfox.imagebuilder.entity.ComponentType;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.ComponentRepository;
import com.luckfox.imagebuilder.repository.ComponentTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ComponentService {

    private final ComponentRepository componentRepository;
    private final ComponentTypeRepository componentTypeRepository;

    @Transactional(readOnly = true)
    public List<ComponentDto> search(String name, Long typeId) {
        List<Component> result;
        if (typeId != null) {
            ComponentType type = componentTypeRepository.findById(typeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Тип компонента не найден: " + typeId));
            result = componentRepository.findByType(type);
        } else if (name != null && !name.isBlank()) {
            result = componentRepository.findByNameContainingIgnoreCase(name);
        } else {
            result = componentRepository.findAll();
        }
        return result.stream().map(ComponentDto::from).toList();
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
        // Ссылочную целостность (компонент в зависимостях/образах) контролирует БД:
        // при наличии FK-ссылок MySQL вернёт ошибку, которая поднимется наверх.
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
                deps.add(componentRepository.findById(depId)
                        .orElseThrow(() -> new ResourceNotFoundException("Зависимость не найдена: " + depId)));
            }
        }
        c.setDependencies(deps);
    }
}
