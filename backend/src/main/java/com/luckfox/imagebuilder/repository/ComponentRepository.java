package com.luckfox.imagebuilder.repository;

import com.luckfox.imagebuilder.entity.Component;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface ComponentRepository extends JpaRepository<Component, Long>, JpaSpecificationExecutor<Component> {
    boolean existsByDependenciesContains(Component component);
}
