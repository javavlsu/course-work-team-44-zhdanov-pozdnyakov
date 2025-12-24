CREATE TABLE `Image`(
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `description` TEXT NULL,
    `cost` BIGINT NOT NULL,
    `state` VARCHAR(255) NOT NULL
);
CREATE TABLE `Component`(
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(255) NOT NULL,
    `type` BIGINT UNSIGNED NOT NULL,
    `description` TEXT NULL,
    `cost` BIGINT NOT NULL
);
CREATE TABLE `User`(
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(255) NOT NULL,
    `surename` VARCHAR(255) NULL,
    `email` VARCHAR(255) NOT NULL,
    `password` VARCHAR(255) NOT NULL
);
CREATE TABLE `component_type`(
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `Type` VARCHAR(255) NOT NULL
);
CREATE TABLE `dependency`(
    `parent_id` BIGINT UNSIGNED NOT NULL,
    `dependent_id` BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(`parent_id`,`dependent_id`)
);

CREATE TABLE `Role`(
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `Role_name` VARCHAR(255) NOT NULL
);
CREATE TABLE `image_component_dependency`(
    `image_id` BIGINT UNSIGNED NOT NULL,
    `component_id` BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(`image_id`,`component_id`)
);

CREATE TABLE `user_role`(
    `user_id` BIGINT UNSIGNED NOT NULL,
    `role_id` BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(`user_id`,`role_id`)
);

CREATE TABLE `role_premission`(
    `role_id` BIGINT UNSIGNED NOT NULL,
    `premission_id` BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(`role_id`,`premission_id`)
);

CREATE TABLE `Premission`(
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `action` VARCHAR(255) NOT NULL
);
CREATE TABLE `user_image_order`(
    `user_id` BIGINT UNSIGNED NOT NULL,
    `image_id` BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY(`user_id`,`image_id`)
);
ALTER TABLE
    `image_component_dependency` ADD CONSTRAINT `image_component_dependency_image_id_foreign` FOREIGN KEY(`image_id`) REFERENCES `Image`(`id`);
ALTER TABLE
    `dependency` ADD CONSTRAINT `dependency_dependent_id_foreign` FOREIGN KEY(`dependent_id`) REFERENCES `Component`(`id`);
ALTER TABLE
    `Component` ADD CONSTRAINT `component_type_foreign` FOREIGN KEY(`type`) REFERENCES `component_type`(`id`);
ALTER TABLE
    `user_image_order` ADD CONSTRAINT `user_image_order_user_id_foreign` FOREIGN KEY(`user_id`) REFERENCES `User`(`id`);
ALTER TABLE
    `user_image_order` ADD CONSTRAINT `user_image_order_image_id_foreign` FOREIGN KEY(`image_id`) REFERENCES `Image`(`id`);
ALTER TABLE
    `role_premission` ADD CONSTRAINT `role_premission_premission_id_foreign` FOREIGN KEY(`premission_id`) REFERENCES `Premission`(`id`);
ALTER TABLE
    `role_premission` ADD CONSTRAINT `role_premission_role_id_foreign` FOREIGN KEY(`role_id`) REFERENCES `Role`(`id`);
ALTER TABLE
    `user_role` ADD CONSTRAINT `user_role_user_id_foreign` FOREIGN KEY(`user_id`) REFERENCES `User`(`id`);
ALTER TABLE
    `dependency` ADD CONSTRAINT `dependency_parent_id_foreign` FOREIGN KEY(`parent_id`) REFERENCES `Component`(`id`);
ALTER TABLE
    `image_component_dependency` ADD CONSTRAINT `image_component_dependency_component_id_foreign` FOREIGN KEY(`component_id`) REFERENCES `Component`(`id`);
ALTER TABLE
    `user_role` ADD CONSTRAINT `user_role_role_id_foreign` FOREIGN KEY(`role_id`) REFERENCES `Role`(`id`);