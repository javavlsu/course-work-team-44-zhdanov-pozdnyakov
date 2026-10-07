-- Наполнение БД для объёмного теста (≈×100 к стартовым данным):
-- 2000 компонентов, 20000 образов (по 3 компонента), 5000 пользователей.
-- mysql luckfox_db < volume_seed.sql     (удаление: см. блок в конце)
SET SESSION cte_max_recursion_depth = 100000;

INSERT INTO Component (name, type, description, cost, version)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 2000)
SELECT CONCAT('vol-comp-', n), 1 + (n % 2), 'Компонент объёмного теста', 1 + (n * 37) % 200, 0 FROM seq;

INSERT INTO Image (name, description, distro, board, cost, state, version)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 20000)
SELECT CONCAT('vol-image-', n), 'Образ объёмного теста', 'buildroot',
       ELT(1 + n % 3, 'RV1103_Luckfox_Pico', 'RV1103_Luckfox_Pico_Plus', 'RV1106_Luckfox_Pico_Pro_Max'),
       10 + (n * 53) % 500, ELT(1 + n % 10, 'READY', 'READY', 'READY', 'READY', 'READY', 'READY', 'READY', 'FAILED', 'READY', 'READY'), 0
FROM seq;

-- по 3 разных компонента на образ из пула vol-comp и стартового каталога
INSERT IGNORE INTO image_component_dependency (image_id, component_id)
SELECT i.id, c.id FROM Image i
JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM Component) c
  ON c.rn IN (1 + (i.id * 7) % 2067, 1 + (i.id * 13 + 5) % 2067, 1 + (i.id * 29 + 11) % 2067)
WHERE i.name LIKE 'vol-image-%';

INSERT INTO `User` (email, name, surename, password)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 5000)
SELECT CONCAT('vol-user-', n, '@test.io'), CONCAT('Пользователь', n), 'Объёмный',
       '$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWX4Z0xg.JRkZq5WGt0NRZ3lD4bS' FROM seq;

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id FROM `User` u JOIN Role r ON r.Role_name = 'USER' WHERE u.email LIKE 'vol-user-%';

ANALYZE TABLE Component, Image, image_component_dependency, `User`;

-- Очистка:
-- DELETE icd FROM image_component_dependency icd JOIN Image i ON i.id = icd.image_id WHERE i.name LIKE 'vol-image-%';
-- DELETE FROM Image WHERE name LIKE 'vol-image-%';
-- DELETE icd FROM image_component_dependency icd JOIN Component c ON c.id = icd.component_id WHERE c.name LIKE 'vol-comp-%';
-- DELETE FROM Component WHERE name LIKE 'vol-comp-%';
-- DELETE ur FROM user_role ur JOIN `User` u ON u.id = ur.user_id WHERE u.email LIKE 'vol-user-%';
-- DELETE FROM `User` WHERE email LIKE 'vol-user-%';
