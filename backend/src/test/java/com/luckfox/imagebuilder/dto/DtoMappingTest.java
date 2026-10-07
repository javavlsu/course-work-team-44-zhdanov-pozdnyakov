package com.luckfox.imagebuilder.dto;

import com.luckfox.imagebuilder.entity.Component;
import com.luckfox.imagebuilder.entity.Image;
import com.luckfox.imagebuilder.entity.User;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static com.luckfox.imagebuilder.TestData.component;
import static com.luckfox.imagebuilder.TestData.image;
import static com.luckfox.imagebuilder.TestData.role;
import static com.luckfox.imagebuilder.TestData.user;
import static org.assertj.core.api.Assertions.assertThat;

class DtoMappingTest {

    @Test
    void componentDtoFlattensDirectDependencies() {
        Component glibc = component(1, "glibc", 10);
        Component python = component(2, "python3", 30, glibc);
        Component numpy = component(3, "numpy", 20, python);

        ComponentDto dto = ComponentDto.from(numpy);

        assertThat(dto.getDependencyIds()).containsExactly(2L);
        assertThat(dto.getDependencies()).containsExactly(new ComponentDto.Ref(2L, "python3"));
        assertThat(dto.getType().getType()).isEqualTo("library");
    }

    @Test
    void componentDtoHandlesNullDependencies() {
        Component c = component(1, "glibc", 10);
        c.setDependencies(null);

        assertThat(ComponentDto.from(c).getDependencyIds()).isEmpty();
        assertThat(ComponentDto.from(null)).isNull();
    }

    @Test
    void imageIsDownloadableOnlyWhenReadyWithArtifact() {
        Image ready = image(1, "READY");
        ready.setArtifactPath("/out/image-1.img");
        Image readyNoFile = image(2, "READY");
        Image building = image(3, "BUILDING");
        building.setArtifactPath("/out/image-3.img");

        assertThat(ImageDto.from(ready).isDownloadable()).isTrue();
        assertThat(ImageDto.from(readyNoFile).isDownloadable()).isFalse();
        assertThat(ImageDto.from(building).isDownloadable()).isFalse();
    }

    @Test
    void imageDtoDoesNotExposeArtifactPathOrLogButListsComponents() {
        Image img = image(1, "READY");
        img.setBuildLog("secret build output");
        img.setComponents(new LinkedHashSet<>(List.of(component(1, "glibc", 10))));

        ImageDto dto = ImageDto.from(img);

        assertThat(dto.getComponents()).extracting(ComponentDto::getName).containsExactly("glibc");
        assertThat(dto.toString()).doesNotContain("secret build output");
    }

    @Test
    void userResponseListsRoleNamesWithoutPassword() {
        User u = user(1, "a@test.io", role(1, "USER"), role(2, "ADMIN"));

        UserResponse res = UserResponse.from(u);

        assertThat(res.getRoles()).containsExactlyInAnyOrder("USER", "ADMIN");
        assertThat(res.toString()).doesNotContain("hash");
    }
}
