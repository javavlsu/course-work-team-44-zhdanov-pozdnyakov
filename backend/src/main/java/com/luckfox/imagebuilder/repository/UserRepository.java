package com.luckfox.imagebuilder.repository;

import com.luckfox.imagebuilder.entity.Image;
import com.luckfox.imagebuilder.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    // Роли одним JOIN: запрос выполняется JWT-фильтром на каждый авторизованный запрос.
    @EntityGraph(attributePaths = "roles")
    Optional<User> findByEmail(String email);
    List<User> findByOrderedImagesContains(Image image);
}
