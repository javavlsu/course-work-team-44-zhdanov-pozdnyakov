package com.luckfox.imagebuilder.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Оркестрация сборки с подставным скриптом вместо scripts/luckfox_build.sh. */
@EnabledOnOs({OS.LINUX, OS.MAC})
@ExtendWith(MockitoExtension.class)
class BuildServiceTest {

    @Mock
    private BuildStateService stateService;
    @Mock
    private NotificationService notificationService;
    @InjectMocks
    private BuildService buildService;

    @TempDir
    Path dir;

    private final BuildStateService.BuildInputs inputs =
            new BuildStateService.BuildInputs(5L, "my-image", "buildroot", "RV1103", List.of("glibc", "python3"));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(buildService, "outputDir", dir.resolve("out").toString());
        ReflectionTestUtils.setField(buildService, "timeoutSeconds", 10L);
    }

    /** Скрипт получает аргументы как luckfox_build.sh; тело — сценарий поведения. */
    private void script(String body) throws Exception {
        Path s = Files.writeString(dir.resolve("build.sh"), """
                while [ $# -gt 0 ]; do case "$1" in --out) OUT="$2"; shift 2;; *) shift;; esac; done
                """ + body + "\n");
        ReflectionTestUtils.setField(buildService, "scriptPath", s.toString());
    }

    @Test
    void successfulBuildMarksReadyWithArtifactAndNotifies() throws Exception {
        when(stateService.loadInputs(5L)).thenReturn(inputs);
        script("echo \"components=$*\"; echo img > \"$OUT\"");

        buildService.build(5L);

        Path artifact = dir.resolve("out").resolve("image-5.img");
        verify(stateService).markBuilding(5L);
        verify(stateService).markReady(eq(5L), eq(artifact.toString()), anyString());
        verify(notificationService).notifyImageReady(5L, "my-image", artifact);
        verify(stateService, never()).markFailed(any(), any());
    }

    @Test
    void componentsArePassedToScript() throws Exception {
        when(stateService.loadInputs(5L)).thenReturn(inputs);
        Path args = dir.resolve("args.txt");
        Files.writeString(dir.resolve("build.sh"), "echo \"$@\" > " + args + "; exit 1\n");
        ReflectionTestUtils.setField(buildService, "scriptPath", dir.resolve("build.sh").toString());

        buildService.build(5L);

        org.assertj.core.api.Assertions.assertThat(Files.readString(args))
                .contains("--id 5", "--name my-image", "--distro buildroot", "--board RV1103",
                        "--components glibc,python3");
    }

    @Test
    void nonZeroExitMarksFailedWithExitCode() throws Exception {
        when(stateService.loadInputs(5L)).thenReturn(inputs);
        script("echo 'sdk error'; exit 3");

        buildService.build(5L);

        verify(stateService).markFailed(eq(5L), argThat(log -> log.contains("sdk error") && log.contains("Код возврата: 3")));
        verify(stateService, never()).markReady(any(), any(), any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void zeroExitWithoutArtifactIsFailure() throws Exception {
        when(stateService.loadInputs(5L)).thenReturn(inputs);
        script("exit 0");

        buildService.build(5L);

        verify(stateService).markFailed(eq(5L), contains("Код возврата: 0"));
    }

    @Test
    void hangingBuildIsKilledByTimeout() throws Exception {
        ReflectionTestUtils.setField(buildService, "timeoutSeconds", 1L);
        when(stateService.loadInputs(5L)).thenReturn(inputs);
        // Вывод закрыт, процесс висит — ожидание упирается в таймаут.
        script("exec >/dev/null 2>&1; sleep 30");

        buildService.build(5L);

        verify(stateService).markFailed(eq(5L), contains("таймаут"));
    }

    @Test
    void missingImageStopsBeforeBuilding() {
        when(stateService.loadInputs(5L)).thenThrow(new RuntimeException("not found"));

        buildService.build(5L);

        verify(stateService, never()).markBuilding(any());
        verify(stateService, never()).markFailed(any(), any());
    }
}
