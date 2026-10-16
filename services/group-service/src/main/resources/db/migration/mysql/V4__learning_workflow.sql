CREATE TABLE study_plan_versions (
 group_id VARCHAR(80) NOT NULL, plan_id VARCHAR(36) NOT NULL, version INT NOT NULL,
 author_id VARCHAR(36) NOT NULL, title VARCHAR(120) NOT NULL, stage VARCHAR(120) NOT NULL,
 deadline VARCHAR(10) NOT NULL, change_note VARCHAR(2000) NOT NULL, tasks_json TEXT NOT NULL,
 created_at VARCHAR(40) NOT NULL, PRIMARY KEY(group_id,plan_id,version)
);
CREATE TABLE study_enrollments (
 group_id VARCHAR(80) NOT NULL, user_id VARCHAR(36) NOT NULL, plan_id VARCHAR(36) NOT NULL,
 version INT NOT NULL, minutes INT NOT NULL, confirmed_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(group_id,user_id,plan_id)
);
CREATE TABLE study_tasks (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, user_id VARCHAR(36) NOT NULL,
 plan_id VARCHAR(36) NOT NULL, plan_version INT NOT NULL, template_id VARCHAR(36) NOT NULL,
 title VARCHAR(120) NOT NULL, problem_key VARCHAR(80) NOT NULL, criterion VARCHAR(2000) NOT NULL,
 deadline VARCHAR(10) NOT NULL, original_deadline VARCHAR(10) NOT NULL, status VARCHAR(30) NOT NULL,
 draft TEXT NOT NULL, minutes INT NOT NULL DEFAULT 0, revision BIGINT NOT NULL DEFAULT 0,
 created_at VARCHAR(40) NOT NULL, completed_at VARCHAR(40),
 PRIMARY KEY(group_id,id), UNIQUE(group_id,user_id,plan_id,template_id)
);
CREATE TABLE study_submissions (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, task_id VARCHAR(36) NOT NULL,
 user_id VARCHAR(36) NOT NULL, body TEXT NOT NULL, minutes INT NOT NULL,
 problem_key VARCHAR(80) NOT NULL, evidence_note VARCHAR(2000) NOT NULL,
 oj_status VARCHAR(30) NOT NULL, created_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(group_id,id)
);
CREATE TABLE study_events (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, task_id VARCHAR(36) NOT NULL,
 user_id VARCHAR(36) NOT NULL, kind VARCHAR(40) NOT NULL, reason VARCHAR(2000) NOT NULL,
 detail VARCHAR(2000) NOT NULL, created_at VARCHAR(40) NOT NULL, PRIMARY KEY(group_id,id)
);
CREATE TABLE study_checkpoint_attempts (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, task_id VARCHAR(36) NOT NULL,
 user_id VARCHAR(36) NOT NULL, submission_id VARCHAR(36) NOT NULL,
 answer TEXT NOT NULL, variant VARCHAR(2000) NOT NULL, status VARCHAR(30) NOT NULL,
 feedback VARCHAR(4000) NOT NULL, appeal VARCHAR(2000) NOT NULL,
 created_at VARCHAR(40) NOT NULL, reviewed_at VARCHAR(40), PRIMARY KEY(group_id,id)
);
CREATE TABLE study_reviews (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, task_id VARCHAR(36) NOT NULL,
 user_id VARCHAR(36) NOT NULL, reason VARCHAR(2000) NOT NULL, due_day VARCHAR(10) NOT NULL,
 status VARCHAR(30) NOT NULL, shared BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(group_id,id), UNIQUE(group_id,user_id,task_id)
);
CREATE TABLE study_review_history (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, review_id VARCHAR(36) NOT NULL,
 user_id VARCHAR(36) NOT NULL, reflection VARCHAR(4000) NOT NULL,
 next_day VARCHAR(10) NOT NULL, created_at VARCHAR(40) NOT NULL, PRIMARY KEY(group_id,id)
);
CREATE TABLE study_help (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, task_id VARCHAR(36) NOT NULL,
 user_id VARCHAR(36) NOT NULL, discussion_id VARCHAR(36) NOT NULL,
 resolution VARCHAR(4000) NOT NULL, status VARCHAR(30) NOT NULL,
 created_at VARCHAR(40) NOT NULL, PRIMARY KEY(group_id,id)
);
CREATE TABLE study_retrospectives (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, user_id VARCHAR(36) NOT NULL,
 body VARCHAR(4000) NOT NULL, next_plan VARCHAR(4000) NOT NULL, confirmed BOOLEAN NOT NULL,
 created_at VARCHAR(40) NOT NULL, PRIMARY KEY(group_id,id)
);
CREATE TABLE study_resources (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, author_id VARCHAR(36) NOT NULL,
 title VARCHAR(120) NOT NULL, kind VARCHAR(30) NOT NULL, body TEXT NOT NULL,
 created_at VARCHAR(40) NOT NULL, PRIMARY KEY(group_id,id)
);
CREATE INDEX idx_study_tasks_owner ON study_tasks(group_id,user_id,status);
CREATE INDEX idx_study_submissions_task ON study_submissions(group_id,user_id,task_id);
CREATE INDEX idx_study_reviews_owner ON study_reviews(group_id,user_id,status);
CREATE TABLE study_oj_evidence (
 group_id VARCHAR(80) NOT NULL, submission_id VARCHAR(36) NOT NULL,
 handle VARCHAR(80) NOT NULL, binding_version BIGINT NOT NULL,
 evidence_id VARCHAR(80) NOT NULL, checked_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(group_id,submission_id)
);
