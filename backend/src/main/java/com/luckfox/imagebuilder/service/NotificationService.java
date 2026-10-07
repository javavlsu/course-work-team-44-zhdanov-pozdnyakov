package com.luckfox.imagebuilder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Уведомление о готовности образа. По предметной области пользователю на e-mail
 * приходит собранный .img. В прототипе доставка логируется и предоставляется
 * ссылка на скачивание (/api/images/{id}/download); подключение реального SMTP
 * выполняется добавлением spring-boot-starter-mail без изменения остального кода.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public void notifyImageReady(Long imageId, String imageName, Path artifact) {
        log.info("УВЕДОМЛЕНИЕ: образ #{} '{}' готов. Артефакт: {}. " +
                        "Доступна загрузка: GET /api/images/{}/download",
                imageId, imageName, artifact, imageId);
    }
}
