# Image Builder Backend

Backend часть системы на Spring Boot 3.

## Технологии
*   Java 17
*   Spring Boot 3 (Web, Data JPA, Security)
*   MySQL Connector
*   Lombok

## Запуск
1.  Убедитесь, что MySQL запущен.
2.  Настройте `src/main/resources/application.properties`.
3.  Запустите через Maven:
    ```bash
    mvn spring-boot:run
    ```

## API Endpoints
*   `GET /api/images` — Список всех образов.
*   `POST /api/images/build` — Запуск сборки нового образа.
*   `GET /api/components` — Список доступных компонентов.
*   `POST /api/components` — Создание нового компонента (Admin).

## Структура
*   `entity/` — JPA сущности, маппинг на таблицы БД.
*   `repository/` — Spring Data JPA репозитории.
*   `controller/` — REST контроллеры.
*   `config/` — Конфигурация приложения (CORS и т.д.).
