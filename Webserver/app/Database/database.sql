CREATE TABLE `server_settings` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `session_limit` INT NOT NULL DEFAULT 5 CHECK (`session_limit` > 0),
  `user_limit_per_session` INT NOT NULL DEFAULT 10 CHECK (`user_limit_per_session` >= 0),
  `message_character_limit` INT NOT NULL DEFAULT 500 CHECK (`message_character_limit` >= 0),
  `file_count_limit_per_session` INT NOT NULL DEFAULT 100 CHECK (`file_count_limit_per_session` >= -1),
  `file_size_bytes_limit` BIGINT NOT NULL DEFAULT 104857600 CHECK (`file_size_bytes_limit` >= 0),
  `client_missed_ping_limit` INT NOT NULL DEFAULT 2 CHECK (`client_missed_ping_limit` > 0),
  `events_ping_interval_seconds` INT NOT NULL DEFAULT 2 CHECK (`events_ping_interval_seconds` > 0),
  `events_poll_sleep_microseconds` INT NOT NULL DEFAULT 100000 CHECK (`events_poll_sleep_microseconds` >= 0),
  `public_stats_enabled` TINYINT(1) NOT NULL DEFAULT 1 CHECK (`public_stats_enabled` IN (0, 1))
) ENGINE=InnoDB;

INSERT INTO `server_settings` (`id`) VALUES (1);

CREATE TABLE `sessions` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `server_settings_id` BIGINT NOT NULL,
  `name` CHAR(64) NOT NULL,
  `chat_encrypted` TINYINT(1) NOT NULL DEFAULT 0 CHECK (`chat_encrypted` IN (0, 1)),
  `access_key` VARCHAR(255) NOT NULL,
  `session_nonce` VARCHAR(64) NULL,

  UNIQUE KEY `uq_sessions_server_settings_name` (`server_settings_id`, `name`),

  CONSTRAINT `fk_sessions_server_settings`
    FOREIGN KEY (`server_settings_id`)
    REFERENCES `server_settings` (`id`)
    ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `clients` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `session_id` BIGINT NOT NULL,
  `name` VARCHAR(255) NOT NULL,
  `token` CHAR(64) NOT NULL,
  `ip` VARCHAR(45) NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `missed_pings` INT NOT NULL DEFAULT 0 CHECK (`missed_pings` >= 0),
  `events_stream_open` TINYINT(1) NOT NULL DEFAULT 0 CHECK (`events_stream_open` IN (0, 1)),

  UNIQUE KEY `uq_client_token` (`token`),
  INDEX `idx_clients_session_missed_pings` (`session_id`, `missed_pings`),
  INDEX `idx_clients_session_stream_created_at` (`session_id`, `events_stream_open`, `created_at`),

  CONSTRAINT `fk_clients_sessions`
    FOREIGN KEY (`session_id`)
    REFERENCES `sessions` (`id`)
    ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `files` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `session_id` BIGINT NOT NULL,
  `public_id` CHAR(36) NOT NULL,
  `name` VARCHAR(255) NOT NULL,
  `byte_size` BIGINT NOT NULL CHECK (`byte_size` >= 0),

  UNIQUE KEY `uq_files_public_id` (`public_id`),
  INDEX `idx_files_session_id` (`session_id`),

  CONSTRAINT `fk_files_sessions`
    FOREIGN KEY (`session_id`)
    REFERENCES `sessions` (`id`)
    ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `messages` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `receiver_id` BIGINT NOT NULL,
  `sender_id` BIGINT NOT NULL,
  `type` VARCHAR(32) NOT NULL DEFAULT 'MESSAGE',
  `file_id` BIGINT NULL,
  `content` TEXT NOT NULL,
  `timestamp` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  INDEX `idx_messages_receiver_id_id` (`receiver_id`, `id`),
  INDEX `idx_messages_file_id` (`file_id`),

  CONSTRAINT `fk_messages_receiver_clients`
    FOREIGN KEY (`receiver_id`)
    REFERENCES `clients` (`id`)
    ON DELETE CASCADE,

  CONSTRAINT `fk_messages_files`
    FOREIGN KEY (`file_id`)
    REFERENCES `files` (`id`)
    ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE `file_client_permissions` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `file_id` BIGINT NOT NULL,
  `client_id` BIGINT NOT NULL,

  UNIQUE KEY `uq_file_client` (`file_id`, `client_id`),
  INDEX `idx_fcp_client_id` (`client_id`),

  CONSTRAINT `fk_fcp_file`
    FOREIGN KEY (`file_id`)
    REFERENCES `files` (`id`)
    ON DELETE CASCADE,

  CONSTRAINT `fk_fcp_client`
    FOREIGN KEY (`client_id`)
    REFERENCES `clients` (`id`)
    ON DELETE CASCADE
) ENGINE=InnoDB;
