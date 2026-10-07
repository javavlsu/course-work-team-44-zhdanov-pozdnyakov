package com.luckfox.imagebuilder.config;

import com.luckfox.imagebuilder.dto.BuildRequest;
import com.luckfox.imagebuilder.entity.*;
import com.luckfox.imagebuilder.service.*;
import com.luckfox.imagebuilder.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Наполнение БД стартовыми данными: роли, типы компонентов, каталог компонентов
 * с зависимостями, администратор и несколько образов в библиотеке.
 * Выполняется один раз — при пустой таблице компонентов.
 *
 * Образы заказываются от имени администратора через {@link ImageService#orderBuild}
 * — тот же конвейер, что и в редакторе образов: разрешение зависимостей, очередь,
 * сборка и готовый .img для скачивания.
 */
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String ADMIN_EMAIL = "admin@luckfox.io";

    private final RoleRepository roleRepository;
    private final ComponentTypeRepository componentTypeRepository;
    private final ComponentRepository componentRepository;
    private final UserRepository userRepository;
    private final ImageService imageService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (componentRepository.count() > 0) {
            log.info("DataSeeder: данные уже есть, пропуск");
            return;
        }
        log.info("DataSeeder: наполнение стартовыми данными");

        Role userRole = role("USER");
        Role adminRole = role("ADMIN");

        ComponentType pkg = type("Пакет");
        ComponentType lib = type("Библиотека");

        // Каталог компонентов с зависимостями.
        com.luckfox.imagebuilder.entity.Component python = comp("python3", pkg, "Интерпретатор Python 3", 50, Set.of());
        com.luckfox.imagebuilder.entity.Component libc = comp("glibc", lib, "Стандартная библиотека C", 20, Set.of());
        com.luckfox.imagebuilder.entity.Component numpy = comp("numpy", lib, "Численные вычисления", 30, Set.of(python));
        com.luckfox.imagebuilder.entity.Component opencv = comp("opencv-python", lib,
                "Компьютерное зрение (precompiled RVV)", 80, Set.of(python, numpy));
        com.luckfox.imagebuilder.entity.Component wiringpy = comp("wiringpy", lib, "GPIO-доступ из Python", 25, Set.of(python));
        com.luckfox.imagebuilder.entity.Component ffmpeg = comp("ffmpeg", pkg, "Обработка видео/аудио", 60, Set.of(libc));
        com.luckfox.imagebuilder.entity.Component openssh = comp("openssh", pkg, "SSH-сервер и клиент", 40, Set.of(libc));

        // Тестовые компоненты — чтобы каталог занимал несколько страниц.
        for (int i = 0; i < 60; i++) {
            comp("Dummy" + i, i % 2 == 0 ? pkg : lib, "Тестовый компонент", 10, Set.of());
        }

        // Администратор создаётся до образов: стартовые образы заказываются от его имени.
        if (userRepository.findByEmail(ADMIN_EMAIL).isEmpty()) {
            User admin = new User();
            admin.setName("Admin");
            admin.setSurname("Luckfox");
            admin.setEmail(ADMIN_EMAIL);
            admin.setPassword(passwordEncoder.encode("Admin@123"));
            Set<Role> roles = new HashSet<>();
            roles.add(adminRole);
            roles.add(userRole);
            admin.setRoles(roles);
            userRepository.save(admin);
            log.info("DataSeeder: создан администратор {} / Admin@123", ADMIN_EMAIL);
        }

        // Образы в библиотеке — через обычный заказ сборки.
        image("CV Starter", "Базовый образ для задач компьютерного зрения",
                "RV1103_Luckfox_Pico", List.of(opencv));
        image("IoT Edge", "Образ с SSH и Python для периферийных задач",
                "RV1106_Luckfox_Pico_Pro_Max", List.of(openssh, wiringpy));
        image("Media Box", "Обработка видео и удалённый доступ",
                "RV1103_Luckfox_Pico_Plus", List.of(ffmpeg, openssh));

        for (int i = 0; i < 12; i++) {
            image("Dummy" + i, "Тестовый образ", "RV1103_Luckfox_Pico", List.of(numpy));
        }
    }

    private Role role(String name) {
        return roleRepository.findByRoleName(name).orElseGet(() -> {
            Role r = new Role();
            r.setRoleName(name);
            return roleRepository.save(r);
        });
    }

    private ComponentType type(String name) {
        return componentTypeRepository.findByTypeIgnoreCase(name).orElseGet(() -> {
            ComponentType t = new ComponentType();
            t.setType(name);
            return componentTypeRepository.save(t);
        });
    }

    private com.luckfox.imagebuilder.entity.Component comp(
            String name, ComponentType type, String desc, long cost,
            Set<com.luckfox.imagebuilder.entity.Component> deps) {
        com.luckfox.imagebuilder.entity.Component c = new com.luckfox.imagebuilder.entity.Component();
        c.setName(name);
        c.setType(type);
        c.setDescription(desc);
        c.setCost(cost);
        c.setDependencies(new HashSet<>(deps));
        return componentRepository.save(c);
    }

    private void image(String name, String desc, String board,
                       List<com.luckfox.imagebuilder.entity.Component> comps) {
        imageService.orderBuild(BuildRequest.builder()
                .name(name)
                .description(desc)
                .distro("buildroot")
                .board(board)
                .componentIds(comps.stream().map(com.luckfox.imagebuilder.entity.Component::getId).toList())
                .build(), ADMIN_EMAIL);
    }
}
