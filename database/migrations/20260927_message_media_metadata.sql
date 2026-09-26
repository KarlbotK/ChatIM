ALTER TABLE `message`
    ADD COLUMN `media_object_name` VARCHAR(512) NULL AFTER `content`,
    ADD COLUMN `media_content_type` VARCHAR(100) NULL AFTER `media_object_name`,
    ADD COLUMN `media_width` INT NULL AFTER `media_content_type`,
    ADD COLUMN `media_height` INT NULL AFTER `media_width`,
    ADD COLUMN `media_size` BIGINT NULL AFTER `media_height`,
    ADD COLUMN `thumbnail_object_name` VARCHAR(512) NULL AFTER `media_size`,
    ADD COLUMN `media_original_name` VARCHAR(255) NULL AFTER `thumbnail_object_name`;
