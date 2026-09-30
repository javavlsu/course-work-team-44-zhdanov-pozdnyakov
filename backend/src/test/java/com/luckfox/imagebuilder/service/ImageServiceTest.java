package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.dto.BuildRequest;
import com.luckfox.imagebuilder.dto.ImageDto;
import com.luckfox.imagebuilder.entity.Component;
import com.luckfox.imagebuilder.entity.Image;
import com.luckfox.imagebuilder.entity.User;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.ComponentRepository;
import com.luckfox.imagebuilder.repository.ImageRepository;
import com.luckfox.imagebuilder.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.luckfox.imagebuilder.TestData.component;
import static com.luckfox.imagebuilder.TestData.image;
import static com.luckfox.imagebuilder.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageServiceTest {

    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ComponentRepository componentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BuildService buildService;
    @InjectMocks
    private ImageService imageService;

    // glibc ← python3 ← numpy; opencv → numpy (общая зависимость)
    private final Component glibc = component(1, "glibc", 10);
    private final Component python = component(2, "python3", 30, glibc);
    private final Component numpy = component(3, "numpy", 20, python);
    private final Component opencv = component(4, "opencv", 80, numpy);
    private final User alice = user(7, "alice@test.io");

    @BeforeEach
    void setUp() {
        for (Component c : List.of(glibc, python, numpy, opencv)) {
            lenient().when(componentRepository.findById(c.getId())).thenReturn(Optional.of(c));
        }
        lenient().when(imageRepository.save(any(Image.class))).thenAnswer(inv -> {
            Image i = inv.getArgument(0);
            if (i.getId() == null) i.setId(100L);
            return i;
        });
    }

    private static BuildRequest request(Long... componentIds) {
        return BuildRequest.builder().name("my-image").distro("buildroot").componentIds(List.of(componentIds)).build();
    }

    @Test
    void orderResolvesTransitiveDependenciesAndSumsCostOnce() {
        when(userRepository.findByEmail("alice@test.io")).thenReturn(Optional.of(alice));

        ImageDto dto = imageService.orderBuild(request(4L, 3L), "alice@test.io");

        assertThat(dto.getComponents()).extracting("name")
                .containsExactlyInAnyOrder("opencv", "numpy", "python3", "glibc");
        assertThat(dto.getCost()).isEqualTo(80 + 20 + 30 + 10);
        assertThat(dto.getState()).isEqualTo("QUEUED");
        assertThat(dto.isDownloadable()).isFalse();
    }

    @Test
    void orderUsesDefaultBoardWhenNotGiven() {
        when(userRepository.findByEmail("alice@test.io")).thenReturn(Optional.of(alice));

        ImageDto dto = imageService.orderBuild(request(1L), "alice@test.io");

        assertThat(dto.getBoard()).isEqualTo("RV1103_Luckfox_Pico");
    }

    @Test
    void orderLinksImageToCustomerAndSchedulesBuild() {
        when(userRepository.findByEmail("alice@test.io")).thenReturn(Optional.of(alice));

        imageService.orderBuild(request(1L), "alice@test.io");

        assertThat(alice.getOrderedImages()).extracting(Image::getId).containsExactly(100L);
        verify(userRepository).save(alice);
        // Вне транзакции «после коммита» наступает сразу — сборка ставится в очередь.
        verify(buildService).build(100L);
    }

    @Test
    void orderWithUnknownComponentFails() {
        when(userRepository.findByEmail("alice@test.io")).thenReturn(Optional.of(alice));
        when(componentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> imageService.orderBuild(request(99L), "alice@test.io"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(buildService, never()).build(any());
    }

    @Test
    void orderByUnknownUserFails() {
        when(userRepository.findByEmail("ghost@test.io")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> imageService.orderBuild(request(1L), "ghost@test.io"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cyclicDependencyGraphIsRejected() {
        Component a = component(10, "a", 1);
        Component b = component(11, "b", 1, a);
        a.setDependencies(new HashSet<>(Set.of(b)));
        when(userRepository.findByEmail("alice@test.io")).thenReturn(Optional.of(alice));
        when(componentRepository.findById(10L)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> imageService.orderBuild(request(10L), "alice@test.io"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("циклическая");
        verify(imageRepository, never()).save(any());
    }

    @Test
    void updateOfQueuedOrBuildingImageIsRejected() {
        for (String state : List.of("QUEUED", "BUILDING")) {
            when(imageRepository.findById(5L)).thenReturn(Optional.of(image(5, state)));

            assertThatThrownBy(() -> imageService.update(5L, request(1L)))
                    .isInstanceOf(BadRequestException.class);
        }
        verify(buildService, never()).build(any());
    }

    @Test
    void updateRequeuesImageAndDropsOldArtifact(@TempDir Path dir) throws Exception {
        Path artifact = Files.writeString(dir.resolve("image-5.img"), "old");
        Image ready = image(5, "READY");
        ready.setArtifactPath(artifact.toString());
        ready.setBuildLog("old log");
        when(imageRepository.findById(5L)).thenReturn(Optional.of(ready));

        ImageDto dto = imageService.update(5L, request(2L));

        assertThat(dto.getState()).isEqualTo("QUEUED");
        assertThat(dto.getCost()).isEqualTo(40);
        assertThat(ready.getArtifactPath()).isNull();
        assertThat(ready.getBuildLog()).isNull();
        assertThat(artifact).doesNotExist();
        verify(buildService).build(5L);
    }

    @Test
    void deleteOfBuildingImageIsRejected() {
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image(5, "BUILDING")));

        assertThatThrownBy(() -> imageService.delete(5L)).isInstanceOf(BadRequestException.class);
        verify(imageRepository, never()).delete(any(Image.class));
    }

    @Test
    void deleteUnlinksCustomersAndRemovesArtifact(@TempDir Path dir) throws Exception {
        Path artifact = Files.writeString(dir.resolve("image-5.img"), "data");
        Image ready = image(5, "READY");
        ready.setArtifactPath(artifact.toString());
        alice.setOrderedImages(new HashSet<>(Set.of(ready)));
        when(imageRepository.findById(5L)).thenReturn(Optional.of(ready));
        when(userRepository.findByOrderedImagesContains(ready)).thenReturn(List.of(alice));

        imageService.delete(5L);

        assertThat(alice.getOrderedImages()).isEmpty();
        verify(imageRepository).delete(ready);
        assertThat(artifact).doesNotExist();
    }

    @Test
    void getByIdOfMissingImageFails() {
        when(imageRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> imageService.getDtoById(404L)).isInstanceOf(ResourceNotFoundException.class);
    }
}
