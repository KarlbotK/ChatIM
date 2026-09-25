ALTER TABLE `message`
    ADD KEY `idx_message_session_cursor` (`session_id`, `created_time`, `message_id`);
