package com.luckfox.imagebuilder.dto;

import com.luckfox.imagebuilder.entity.ComponentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ComponentTypeDto {
    private Long id;
    private String type;

    public static ComponentTypeDto from(ComponentType t) {
        if (t == null) return null;
        return ComponentTypeDto.builder().id(t.getId()).type(t.getType()).build();
    }
}
