ALTER TABLE `session`
    ADD COLUMN `avatar_object_name` VARCHAR(512) NULL AFTER `avatar`,
    ADD COLUMN `announcement` VARCHAR(500) NULL AFTER `avatar_object_name`;
