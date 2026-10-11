
CREATE TABLE notifications (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, request_id VARCHAR(36) NOT NULL UNIQUE,
 title VARCHAR(200) NOT NULL, body VARCHAR(2000) NOT NULL, created_at VARCHAR(40) NOT NULL, is_read BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(user_id) REFERENCES accounts(id), FOREIGN KEY(request_id) REFERENCES group_requests(id)
);
CREATE INDEX idx_notifications_user ON notifications(user_id,created_at);
CREATE TABLE mail_jobs (
 id VARCHAR(36) PRIMARY KEY, notification_id VARCHAR(36) NOT NULL UNIQUE,
 recipient VARCHAR(200) NOT NULL, subject VARCHAR(200) NOT NULL, body VARCHAR(2000) NOT NULL,
 status VARCHAR(30) NOT NULL, attempts INT NOT NULL DEFAULT 0, publish_attempts INT NOT NULL DEFAULT 0,
 next_attempt_at BIGINT NOT NULL DEFAULT 0, last_error VARCHAR(500), created_at VARCHAR(40) NOT NULL, sent_at VARCHAR(40),
 FOREIGN KEY(notification_id) REFERENCES notifications(id)
);
CREATE INDEX idx_mail_jobs_due ON mail_jobs(status,next_attempt_at);
