package com.luckfox.imagebuilder.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Фоновая оркестрация сборки образа. Вызывает обёртку scripts/luckfox_build.sh,
 * которая выполняет реальную сборку SDK либо симуляцию (см. скрипт).
 */
@Service
@RequiredArgsConstructor
public class BuildService {

    private static final Logger log = LoggerFactory.getLogger(BuildService.class);

    private final BuildStateService stateService;
    private final NotificationService notificationService;

    @Value("${luckfox.build.script:../scripts/luckfox_build.sh}")
    private String scriptPath;

    @Value("${luckfox.build.output-dir:../output/images}")
    private String outputDir;

    @Value("${luckfox.build.timeout-seconds:1800}")
    private long timeoutSeconds;

    @Async("buildExecutor")
    public void build(Long imageId) {
        BuildStateService.BuildInputs in;
        try {
            in = stateService.loadInputs(imageId);
        } catch (Exception e) {
            log.error("Не удалось загрузить данные образа #{}: {}", imageId, e.getMessage());
            return;
        }

        stateService.markBuilding(imageId);
        log.info("Старт сборки образа #{} '{}'", imageId, in.name());

        try {
            Path script = Paths.get(scriptPath).toAbsolutePath().normalize();
            Path outDir = Paths.get(outputDir).toAbsolutePath().normalize();
            Files.createDirectories(outDir);
            Path outFile = outDir.resolve("image-" + imageId + ".img");

            List<String> cmd = new ArrayList<>(List.of(
                    "bash", script.toString(),
                    "--id", String.valueOf(imageId),
                    "--name", safe(in.name()),
                    "--distro", safe(in.distro()),
                    "--board", safe(in.board()),
                    "--components", String.join(",", in.components()),
                    "--out", outFile.toString()
            ));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder logBuf = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logBuf.append(line).append('\n');
                    log.info("[build #{}] {}", imageId, line);
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                stateService.markFailed(imageId, logBuf + "\nОшибка: превышен таймаут сборки");
                log.error("Сборка образа #{} прервана по таймауту", imageId);
                return;
            }

            int exit = process.exitValue();
            if (exit == 0 && Files.exists(outFile)) {
                stateService.markReady(imageId, outFile.toString(), logBuf.toString());
                log.info("Сборка образа #{} завершена: {}", imageId, outFile);
                notificationService.notifyImageReady(imageId, in.name(), outFile);
            } else {
                stateService.markFailed(imageId, logBuf + "\nКод возврата: " + exit);
                log.error("Сборка образа #{} провалена (код {})", imageId, exit);
            }
        } catch (Exception e) {
            stateService.markFailed(imageId, "Исключение при сборке: " + e.getMessage());
            log.error("Сборка образа #{} упала с исключением", imageId, e);
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
