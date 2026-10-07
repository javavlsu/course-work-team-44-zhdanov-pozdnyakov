package com.luckfox.imagebuilder.service;

import com.luckfox.imagebuilder.dto.ComponentDto;
import com.luckfox.imagebuilder.dto.ComponentRequest;
import com.luckfox.imagebuilder.entity.Component;
import com.luckfox.imagebuilder.exception.BadRequestException;
import com.luckfox.imagebuilder.exception.ResourceNotFoundException;
import com.luckfox.imagebuilder.repository.ComponentRepository;
import com.luckfox.imagebuilder.repository.ComponentTypeRepository;
import com.luckfox.imagebuilder.repository.ImageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static com.luckfox.imagebuilder.TestData.component;
import static com.luckfox.imagebuilder.TestData.type;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ComponentServiceTest {

    @Mock
    private ComponentRepository componentRepository;
    @Mock
    private ComponentTypeRepository componentTypeRepository;
    @Mock
    private ImageRepository imageRepository;
    @InjectMocks
    private ComponentService componentService;

    private static ComponentRequest request(Long... deps) {
        return ComponentRequest.builder().name("numpy").description("массивы").typeId(2L).cost(20L)
                .dependencyIds(List.of(deps)).build();
    }

    @Test
    void createSetsFieldsTypeAndDependencies() {
        Component python = component(1, "python3", 30);
        when(componentTypeRepository.findById(2L)).thenReturn(Optional.of(type(2, "python")));
        when(componentRepository.findById(1L)).thenReturn(Optional.of(python));
        when(componentRepository.save(any(Component.class))).thenAnswer(inv -> inv.getArgument(0));

        ComponentDto dto = componentService.create(request(1L));

        assertThat(dto.getName()).isEqualTo("numpy");
        assertThat(dto.getCost()).isEqualTo(20);
        assertThat(dto.getType().getType()).isEqualTo("python");
        assertThat(dto.getDependencyIds()).containsExactly(1L);
        assertThat(dto.getDependencies()).containsExactly(new ComponentDto.Ref(1L, "python3"));
    }

    @Test
    void createWithUnknownTypeFails() {
        when(componentTypeRepository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> componentService.create(request())).isInstanceOf(ResourceNotFoundException.class);
        verify(componentRepository, never()).save(any());
    }

    @Test
    void createWithUnknownDependencyFails() {
        when(componentTypeRepository.findById(2L)).thenReturn(Optional.of(type(2, "python")));
        when(componentRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> componentService.create(request(9L))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void componentCannotDependOnItself() {
        Component numpy = component(3, "numpy", 20);
        when(componentRepository.findById(3L)).thenReturn(Optional.of(numpy));
        when(componentTypeRepository.findById(2L)).thenReturn(Optional.of(type(2, "python")));

        assertThatThrownBy(() -> componentService.update(3L, request(3L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("сам от себя");
    }

    @Test
    void transitiveCycleIsRejectedOnUpdate() {
        // opencv → numpy → python3; попытка сделать python3 зависимым от opencv замыкает цикл
        Component python = component(1, "python3", 30);
        Component numpy = component(2, "numpy", 20, python);
        Component opencv = component(3, "opencv", 80, numpy);
        when(componentRepository.findById(1L)).thenReturn(Optional.of(python));
        when(componentRepository.findById(3L)).thenReturn(Optional.of(opencv));
        when(componentTypeRepository.findById(2L)).thenReturn(Optional.of(type(2, "python")));

        assertThatThrownBy(() -> componentService.update(1L, request(3L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Циклическая");
        verify(componentRepository, never()).save(any());
    }

    @Test
    void deleteOfComponentUsedInImagesIsRejected() {
        Component glibc = component(1, "glibc", 10);
        when(componentRepository.findById(1L)).thenReturn(Optional.of(glibc));
        when(imageRepository.existsByComponentsContains(glibc)).thenReturn(true);

        assertThatThrownBy(() -> componentService.delete(1L)).isInstanceOf(BadRequestException.class);
        verify(componentRepository, never()).delete(any(Component.class));
    }

    @Test
    void deleteOfComponentOthersDependOnIsRejected() {
        Component glibc = component(1, "glibc", 10);
        when(componentRepository.findById(1L)).thenReturn(Optional.of(glibc));
        when(componentRepository.existsByDependenciesContains(glibc)).thenReturn(true);

        assertThatThrownBy(() -> componentService.delete(1L)).isInstanceOf(BadRequestException.class);
        verify(componentRepository, never()).delete(any(Component.class));
    }

    @Test
    void deleteOfUnusedComponentSucceeds() {
        Component glibc = component(1, "glibc", 10);
        when(componentRepository.findById(1L)).thenReturn(Optional.of(glibc));

        componentService.delete(1L);

        verify(componentRepository).delete(glibc);
    }
}
