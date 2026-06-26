package com.luckfox.imagebuilder.dto;

import com.luckfox.imagebuilder.entity.Image;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Представление образа для библиотеки и детального просмотра.
 * Компоненты отдаются плоско, без рекурсивных зависимостей.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ImageDto {
    private Long id;
    private String name;
    private String description;
    private String distro;
    private String board;
    private Long cost;
    private String state;
    private boolean downloadable;
    private List<ComponentDto> components;

    public static ImageDto from(Image image) {
        if (image == null) return null;
        Set<com.luckfox.imagebuilder.entity.Component> comps = image.getComponents();
        return ImageDto.builder()
                .id(image.getId())
                .name(image.getName())
                .description(image.getDescription())
                .distro(image.getDistro())
                .board(image.getBoard())
                .cost(image.getCost())
                .state(image.getState())
                .downloadable("READY".equalsIgnoreCase(image.getState())
                        && image.getArtifactPath() != null)
                .components(comps == null ? List.of()
                        : comps.stream().map(ComponentDto::from).collect(Collectors.toList()))
                .build();
    }
}
