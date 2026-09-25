ALTER TABLE `user_session`
    ADD KEY `idx_session_member_page` (`session_id`, `status`, `role`, `created_time`, `user_id`);
