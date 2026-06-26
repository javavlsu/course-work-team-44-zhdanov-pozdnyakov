package com.luckfox.imagebuilder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Запрос на сборку образа из редактора.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BuildRequest {

    @NotBlank(message = "Название образа обязательно")
    private String name;

    private String description;

    @NotBlank(message = "Базовый дистрибутив обязателен")
    private String distro;

    private String board;

    @NotEmpty(message = "Нужно выбрать хотя бы один компонент")
    private List<Long> componentIds;
}
