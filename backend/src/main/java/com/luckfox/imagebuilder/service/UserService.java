package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.dto.PageResponse;
import com.luckfox.imagebuilder.dto.UserRequest;
import com.luckfox.imagebuilder.dto.UserResponse;
import com.luckfox.imagebuilder.entity.Role;
import com.luckfox.imagebuilder.entity.User;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.RoleRepository;
import com.luckfox.imagebuilder.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Управление пользователями (администратор). */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    /** Поиск по имени/фамилии/email + фильтр по роли, пагинация в SQL. */
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(String search, String role, int page, int size) {
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase() + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("surname")), like),
                        cb.like(cb.lower(root.get("email")), like)));
            }
            if (role != null && !role.isBlank()) {
                where.add(cb.equal(root.join("roles").get("roleName"), role));
                query.distinct(true);
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
        return PageResponse.from(
                userRepository.findAll(spec, PageResponse.request(page, size, Sort.by("id"))),
                UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return UserResponse.from(getById(id));
    }

    @Transactional
    public UserResponse create(UserRequest req) {
        if (req.getPassword() == null) {
            throw new BadRequestException("Пароль обязателен");
        }
        User user = new User();
        apply(user, req);
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserResponse update(Long id, UserRequest req, String currentEmail) {
        User user = getById(id);
        if (user.getEmail().equals(currentEmail) && !req.getRoles().contains("ADMIN")) {
            throw new BadRequestException("Нельзя снять роль ADMIN с самого себя");
        }
        apply(user, req);
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public void delete(Long id, String currentEmail) {
        User user = getById(id);
        if (user.getEmail().equals(currentEmail)) {
            throw new BadRequestException("Нельзя удалить самого себя");
        }
        // Заказанные образы остаются в библиотеке; удаляются только связи пользователя.
        userRepository.delete(user);
    }

    private User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Пользователь не найден: " + id));
    }

    private void apply(User user, UserRequest req) {
        String email = req.getEmail().trim();
        userRepository.findByEmail(email)
                .filter(other -> !other.getId().equals(user.getId()))
                .ifPresent(other -> {
                    throw new BadRequestException("Пользователь с таким email уже существует");
                });

        Set<Role> roles = new HashSet<>();
        for (String name : req.getRoles()) {
            roles.add(roleRepository.findByRoleName(name)
                    .orElseThrow(() -> new BadRequestException("Неизвестная роль: " + name)));
        }

        user.setName(req.getName());
        user.setSurname(req.getSurname());
        user.setEmail(email);
        user.setRoles(roles);
        if (req.getPassword() != null) {
            user.setPassword(passwordEncoder.encode(req.getPassword()));
        }
    }
}
