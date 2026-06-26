package com.luckfox.imagebuilder.controller;

import com.luckfox.imagebuilder.dto.ComponentTypeDto;
import com.luckfox.imagebuilder.repository.ComponentTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/component-types")
@RequiredArgsConstructor
public class ComponentTypeController {

    private final ComponentTypeRepository componentTypeRepository;

    @GetMapping
    public List<ComponentTypeDto> getAll() {
        return componentTypeRepository.findAll().stream()
                .map(ComponentTypeDto::from).collect(Collectors.toList());
    }
}
