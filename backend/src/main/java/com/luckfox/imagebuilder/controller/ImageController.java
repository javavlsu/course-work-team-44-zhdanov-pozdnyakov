package com.luckfox.imagebuilder.controller;

import com.luckfox.imagebuilder.dto.BuildRequest;
import com.luckfox.imagebuilder.dto.ImageDto;
import com.luckfox.imagebuilder.dto.PageResponse;
import com.luckfox.imagebuilder.entity.Image;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.service.ImageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    @GetMapping
    public PageResponse<ImageDto> getAllImages(@RequestParam(required = false) String search,
                                               @RequestParam(required = false) String state,
                                               @RequestParam(required = false) String board,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "12") int size) {
        return imageService.search(search, state, board, page, size);
    }

    @GetMapping("/{id}")
    public ImageDto getImageById(@PathVariable Long id) {
        return imageService.getDtoById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ImageDto> update(@PathVariable Long id, @Valid @RequestBody BuildRequest request) {
        return ResponseEntity.accepted().body(imageService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        imageService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/build")
    public ResponseEntity<ImageDto> buildImage(@Valid @RequestBody BuildRequest request,
                                               Authentication authentication) {
        return ResponseEntity.accepted().body(imageService.orderBuild(request, authentication.getName()));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Image image = imageService.getById(id);
        if (!"READY".equalsIgnoreCase(image.getState()) || image.getArtifactPath() == null) {
            throw new BadRequestException("Образ ещё не готов к загрузке (статус: " + image.getState() + ")");
        }
        Path path = Paths.get(image.getArtifactPath());
        if (!Files.exists(path)) {
            throw new BadRequestException("Файл артефакта не найден на сервере");
        }
        String filename = (image.getName() == null ? "image-" + id : image.getName()) + ".img";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(new FileSystemResource(path));
    }
}
