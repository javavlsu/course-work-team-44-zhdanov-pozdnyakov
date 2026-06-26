package com.luckfox.imagebuilder.repository;

import com.luckfox.imagebuilder.entity.Image;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImageRepository extends JpaRepository<Image, Long> {
    List<Image> findByState(String state);
}
