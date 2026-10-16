CREATE TABLE study_member_access_history (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, user_id VARCHAR(36) NOT NULL,
 actor_id VARCHAR(36) NOT NULL, revoked BOOLEAN NOT NULL, reason VARCHAR(2000) NOT NULL,
 created_at VARCHAR(40) NOT NULL, PRIMARY KEY(group_id,id)
);
ALTER TABLE study_tasks ADD COLUMN draft_evidence VARCHAR(2000) NOT NULL DEFAULT '';
