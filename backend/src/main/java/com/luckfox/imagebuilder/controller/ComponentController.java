package com.luckfox.imagebuilder.controller;

import com.luckfox.imagebuilder.dto.ComponentDto;
import com.luckfox.imagebuilder.dto.ComponentRequest;
import com.luckfox.imagebuilder.dto.PageResponse;
import com.luckfox.imagebuilder.service.ComponentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/components")
@RequiredArgsConstructor
public class ComponentController {

    private final ComponentService componentService;

    @GetMapping
    public PageResponse<ComponentDto> getAllComponents(@RequestParam(required = false) String search,
                                                       @RequestParam(required = false) Long type,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return componentService.search(search, type, page, size);
    }

    @GetMapping("/{id}")
    public ComponentDto getById(@PathVariable Long id) {
        return componentService.getDtoById(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ComponentDto> create(@Valid @RequestBody ComponentRequest request) {
        return ResponseEntity.ok(componentService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ComponentDto> update(@PathVariable Long id,
                                               @Valid @RequestBody ComponentRequest request) {
        return ResponseEntity.ok(componentService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        componentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
