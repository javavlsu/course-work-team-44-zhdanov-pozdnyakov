package com.luckfox.imagebuilder.repository;

import com.luckfox.imagebuilder.entity.ComponentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ComponentTypeRepository extends JpaRepository<ComponentType, Long> {
    Optional<ComponentType> findByTypeIgnoreCase(String type);
}
