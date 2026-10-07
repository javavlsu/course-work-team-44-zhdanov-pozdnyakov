package com.luckfox.imagebuilder.dto;

import com.luckfox.imagebuilder.entity.Component;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Плоское представление компонента. Зависимости отдаются только идентификаторами,
 * чтобы исключить рекурсию при сериализации рекурсивного графа.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ComponentDto {
    private Long id;
    private String name;
    private String description;
    private Long cost;
    private ComponentTypeDto type;
    private List<Long> dependencyIds;
    /** Прямые зависимости (id + название) — для отображения в редакторе без доп. запросов. */
    private List<Ref> dependencies;

    public record Ref(Long id, String name) {}

    public static ComponentDto from(Component c) {
        if (c == null) return null;
        Set<Component> deps = c.getDependencies() == null ? Set.of() : c.getDependencies();
        return ComponentDto.builder()
                .id(c.getId())
                .name(c.getName())
                .description(c.getDescription())
                .cost(c.getCost())
                .type(ComponentTypeDto.from(c.getType()))
                .dependencyIds(deps.stream().map(Component::getId).collect(Collectors.toList()))
                .dependencies(deps.stream().map(d -> new Ref(d.getId(), d.getName())).collect(Collectors.toList()))
                .build();
    }
}
