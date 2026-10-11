ALTER TABLE group_requests ADD COLUMN deployment_error VARCHAR(300);
UPDATE group_requests SET deployment_status='NOT_DEPLOYED',deployed_at=NULL WHERE deployment_status='DEPLOYED';
CREATE TABLE deployed_groups (
 id VARCHAR(80) PRIMARY KEY, request_id VARCHAR(36) NOT NULL UNIQUE, owner_id VARCHAR(36) NOT NULL,
 name VARCHAR(80) NOT NULL, description VARCHAR(500) NOT NULL, visibility VARCHAR(10) NOT NULL,
 port INT NOT NULL UNIQUE, join_key VARCHAR(100) NOT NULL, status VARCHAR(20) NOT NULL
);
