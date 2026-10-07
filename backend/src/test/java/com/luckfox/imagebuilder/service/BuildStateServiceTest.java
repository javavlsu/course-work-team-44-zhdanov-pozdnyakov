package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.entity.Image;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.ImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static com.luckfox.imagebuilder.TestData.component;
import static com.luckfox.imagebuilder.TestData.image;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildStateServiceTest {

    @Mock
    private ImageRepository imageRepository;
    @InjectMocks
    private BuildStateService service;

    @Test
    void loadInputsReturnsSortedComponentNames() {
        Image img = image(5, "QUEUED");
        img.setDistro("buildroot");
        img.setBoard("RV1103");
        img.setComponents(new LinkedHashSet<>(List.of(component(2, "python3", 1), component(1, "glibc", 1))));
        when(imageRepository.findById(5L)).thenReturn(Optional.of(img));

        BuildStateService.BuildInputs in = service.loadInputs(5L);

        assertThat(in.components()).containsExactly("glibc", "python3");
        assertThat(in.distro()).isEqualTo("buildroot");
        assertThat(in.board()).isEqualTo("RV1103");
    }

    @Test
    void markReadyStoresArtifactAndLog() {
        Image img = image(5, "BUILDING");
        when(imageRepository.findById(5L)).thenReturn(Optional.of(img));

        service.markReady(5L, "/out/image-5.img", "ok");

        assertThat(img.getState()).isEqualTo("READY");
        assertThat(img.getArtifactPath()).isEqualTo("/out/image-5.img");
        assertThat(img.getBuildLog()).isEqualTo("ok");
        verify(imageRepository).save(img);
    }

    @Test
    void markBuildingKeepsPreviousLog() {
        Image img = image(5, "QUEUED");
        img.setBuildLog("previous");
        when(imageRepository.findById(5L)).thenReturn(Optional.of(img));

        service.markBuilding(5L);

        assertThat(img.getState()).isEqualTo("BUILDING");
        assertThat(img.getBuildLog()).isEqualTo("previous");
    }

    @Test
    void markFailedOnMissingImageThrows() {
        when(imageRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markFailed(5L, "log")).isInstanceOf(ResourceNotFoundException.class);
    }
}
