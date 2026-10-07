package com.luckfox.imagebuilder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Запрос на создание/изменение компонента администратором.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ComponentRequest {

    @NotBlank(message = "Название компонента обязательно")
    private String name;

    private String description;

    @NotNull(message = "Тип компонента обязателен")
    private Long typeId;

    @NotNull(message = "Стоимость обязательна")
    @PositiveOrZero(message = "Стоимость не может быть отрицательной")
    private Long cost;

    private List<Long> dependencyIds;
}
