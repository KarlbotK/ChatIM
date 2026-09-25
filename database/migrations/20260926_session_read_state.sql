ALTER TABLE `user_session`
    ADD COLUMN `last_read_message_id` BIGINT NULL AFTER `status`,
    ADD COLUMN `pinned` TINYINT(1) NOT NULL DEFAULT 0 AFTER `last_read_message_id`,
    ADD COLUMN `muted` TINYINT(1) NOT NULL DEFAULT 0 AFTER `pinned`,
    ADD COLUMN `hidden` TINYINT(1) NOT NULL DEFAULT 0 AFTER `muted`,
    ADD KEY `idx_user_session_list` (`user_id`, `status`, `hidden`, `pinned`, `updated_time`, `session_id`);
