CREATE TABLE study_card_packs (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, owner_id VARCHAR(36) NOT NULL,
 name VARCHAR(80) NOT NULL, description VARCHAR(1000) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at VARCHAR(40) NOT NULL, updated_at VARCHAR(40) NOT NULL, PRIMARY KEY(group_id,id)
);
CREATE TABLE study_cards (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, pack_id VARCHAR(36) NOT NULL, owner_id VARCHAR(36) NOT NULL,
 title VARCHAR(120) NOT NULL, problem_key VARCHAR(80) NOT NULL, prompt VARCHAR(4000) NOT NULL,
 answer_hint VARCHAR(4000) NOT NULL, difficulty VARCHAR(20) NOT NULL, created_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(group_id,id), INDEX idx_study_cards_pack(group_id,pack_id,owner_id)
);
CREATE TABLE study_card_draws (
 group_id VARCHAR(80) NOT NULL, id VARCHAR(36) NOT NULL, pack_id VARCHAR(36) NOT NULL, card_id VARCHAR(36) NOT NULL,
 user_id VARCHAR(36) NOT NULL, draw_day VARCHAR(10) NOT NULL, created_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(group_id,id), UNIQUE KEY uq_study_card_draw(group_id,pack_id,user_id,draw_day)
);
