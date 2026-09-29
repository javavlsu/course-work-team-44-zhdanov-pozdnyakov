package com.luckfox.imagebuilder.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Создание/изменение пользователя администратором. Пароль обязателен только
 * при создании; при изменении null означает «оставить прежний».
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserRequest {
    @NotBlank(message = "Имя обязательно")
    private String name;

    private String surname;

    @NotBlank(message = "Email обязателен")
    @Email(message = "Некорректный email")
    private String email;

    @Size(min = 8, message = "Пароль должен быть не короче 8 символов")
    @Pattern(
        regexp = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!]).*$",
        message = "Пароль должен содержать цифру, строчную и заглавную буквы и спецсимвол (@#$%^&+=!)"
    )
    private String password;

    @NotEmpty(message = "Нужна хотя бы одна роль")
    private Set<String> roles;
}
