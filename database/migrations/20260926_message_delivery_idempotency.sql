ALTER TABLE `message`
    ADD COLUMN `client_message_id` VARCHAR(128) NULL AFTER `message_id`,
    ADD UNIQUE KEY `uk_message_sender_client` (`sender_id`, `client_message_id`);
