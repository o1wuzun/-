CREATE TABLE oj_bindings (
 user_id VARCHAR(36) PRIMARY KEY, provider VARCHAR(30) NOT NULL, handle VARCHAR(80) NOT NULL, version BIGINT NOT NULL,
 FOREIGN KEY(user_id) REFERENCES accounts(id)
);
CREATE TABLE learning_points (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, title VARCHAR(100) NOT NULL,
 problem_key VARCHAR(40) NOT NULL, criterion VARCHAR(500) NOT NULL, created_at VARCHAR(40) NOT NULL,
 FOREIGN KEY(user_id) REFERENCES accounts(id)
);
CREATE TABLE daily_checkins (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL, learning_day VARCHAR(10) NOT NULL,
 point_id VARCHAR(36) NOT NULL, minutes INT NOT NULL, reflection VARCHAR(2000) NOT NULL,
 verification VARCHAR(30) NOT NULL, evidence_id VARCHAR(80), evidence_handle VARCHAR(80), binding_version BIGINT,
 settled BOOLEAN NOT NULL DEFAULT FALSE, score INT NOT NULL DEFAULT 0, created_at VARCHAR(40) NOT NULL,
 UNIQUE(user_id,learning_day),
 FOREIGN KEY(user_id) REFERENCES accounts(id), FOREIGN KEY(point_id) REFERENCES learning_points(id)
);
CREATE TABLE learning_awards (
 user_id VARCHAR(36) NOT NULL, problem_key VARCHAR(40) NOT NULL, checkin_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(user_id,problem_key),
 FOREIGN KEY(user_id) REFERENCES accounts(id), FOREIGN KEY(checkin_id) REFERENCES daily_checkins(id)
);
CREATE INDEX idx_learning_points_user ON learning_points(user_id,created_at);
CREATE TABLE learning_plans (
 user_id VARCHAR(36) NOT NULL, learning_day VARCHAR(10) NOT NULL,
 body TEXT NOT NULL, created_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(user_id,learning_day), FOREIGN KEY(user_id) REFERENCES accounts(id)
);
