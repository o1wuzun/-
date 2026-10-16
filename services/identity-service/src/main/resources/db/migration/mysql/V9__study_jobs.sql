CREATE TABLE study_jobs (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, group_id VARCHAR(80) NOT NULL,
 task_id VARCHAR(36) NOT NULL, reference_id VARCHAR(36) NOT NULL, kind VARCHAR(20) NOT NULL,
 status VARCHAR(30) NOT NULL, result TEXT NOT NULL, appeal VARCHAR(2000) NOT NULL,
 created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_study_jobs_scope ON study_jobs(user_id,group_id,status);
