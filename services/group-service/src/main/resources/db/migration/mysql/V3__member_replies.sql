ALTER TABLE comments ADD COLUMN reply_to_user_id VARCHAR(36);
ALTER TABLE comments ADD COLUMN parent_comment_id VARCHAR(36);
CREATE TABLE member_messages (
 id VARCHAR(36) PRIMARY KEY, sender_id VARCHAR(36) NOT NULL, recipient_id VARCHAR(36) NOT NULL,
 body TEXT NOT NULL, created_at VARCHAR(40) NOT NULL, is_read BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(sender_id) REFERENCES members(user_id), FOREIGN KEY(recipient_id) REFERENCES members(user_id)
);
CREATE INDEX idx_member_messages_recipient ON member_messages(recipient_id,created_at);
