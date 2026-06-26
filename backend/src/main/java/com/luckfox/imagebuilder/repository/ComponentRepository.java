package com.luckfox.imagebuilder.repository;

import com.luckfox.imagebuilder.entity.Component;
import com.luckfox.imagebuilder.entity.ComponentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComponentRepository extends JpaRepository<Component, Long> {
    List<Component> findByNameContainingIgnoreCase(String name);
    List<Component> findByType(ComponentType type);
}
