package com.luckfox.imagebuilder.config;

import com.luckfox.imagebuilder.entity.*;
import com.luckfox.imagebuilder.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Наполнение БД стартовыми данными: роли, типы компонентов, каталог компонентов
 * с зависимостями, администратор и несколько готовых образов в библиотеке.
 * Выполняется один раз — при пустой таблице компонентов.
 */
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final RoleRepository roleRepository;
    private final ComponentTypeRepository componentTypeRepository;
    private final ComponentRepository componentRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
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

        // Готовые образы в библиотеке.
        image("CV Starter", "Базовый образ для задач компьютерного зрения",
                "buildroot", "RV1103_Luckfox_Pico", "READY", Set.of(python, numpy, opencv));
        image("IoT Edge", "Образ с SSH и Python для периферийных задач",
                "buildroot", "RV1106_Luckfox_Pico_Pro_Max", "READY", Set.of(python, openssh, wiringpy));

        // Администратор.
        if (userRepository.findByEmail("admin@luckfox.io").isEmpty()) {
            User admin = new User();
            admin.setName("Admin");
            admin.setSurname("Luckfox");
            admin.setEmail("admin@luckfox.io");
            admin.setPassword(passwordEncoder.encode("Admin@123"));
            Set<Role> roles = new HashSet<>();
            roles.add(adminRole);
            roles.add(userRole);
            admin.setRoles(roles);
            userRepository.save(admin);
            log.info("DataSeeder: создан администратор admin@luckfox.io / Admin@123");
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

    private void image(String name, String desc, String distro, String board, String state,
                       Set<com.luckfox.imagebuilder.entity.Component> comps) {
        Image img = new Image();
        img.setName(name);
        img.setDescription(desc);
        img.setDistro(distro);
        img.setBoard(board);
        img.setState(state);
        img.setComponents(new HashSet<>(comps));
        img.setCost(comps.stream().mapToLong(com.luckfox.imagebuilder.entity.Component::getCost).sum());
        imageRepository.save(img);
    }
}
