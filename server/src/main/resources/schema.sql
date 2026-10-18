CREATE TABLE IF NOT EXISTS act_users (
 username VARCHAR(32) PRIMARY KEY, password_hash VARCHAR(300) NOT NULL
);
CREATE TABLE IF NOT EXISTS activity_groups (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(80) NOT NULL, event_date VARCHAR(10) NOT NULL,
 owner_name VARCHAR(32) NOT NULL REFERENCES act_users(username), version BIGINT NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS members (
 group_id VARCHAR(36) NOT NULL REFERENCES activity_groups(id), username VARCHAR(32) NOT NULL REFERENCES act_users(username),
 role VARCHAR(12) NOT NULL CHECK(role IN ('owner','member')), PRIMARY KEY(group_id,username)
);
CREATE TABLE IF NOT EXISTS invitations (
 id VARCHAR(36) PRIMARY KEY, group_id VARCHAR(36) NOT NULL REFERENCES activity_groups(id),
 token_hash VARCHAR(64) NOT NULL UNIQUE, expires_at VARCHAR(40) NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE,
 claimed_by VARCHAR(32) REFERENCES act_users(username)
);
CREATE TABLE IF NOT EXISTS candidates (
 id VARCHAR(36) PRIMARY KEY, group_id VARCHAR(36) NOT NULL REFERENCES activity_groups(id),
 created_by VARCHAR(32) NOT NULL REFERENCES act_users(username), version BIGINT NOT NULL DEFAULT 1,
 payload CLOB NOT NULL, UNIQUE(group_id,id)
);
CREATE TABLE IF NOT EXISTS preferences (
 group_id VARCHAR(36) NOT NULL REFERENCES activity_groups(id), candidate_id VARCHAR(36) NOT NULL,
 username VARCHAR(32) NOT NULL, choice VARCHAR(8) NOT NULL CHECK(choice IN ('want','okay','avoid')),
 PRIMARY KEY(group_id,candidate_id,username),
 FOREIGN KEY(group_id,candidate_id) REFERENCES candidates(group_id,id),
 FOREIGN KEY(group_id,username) REFERENCES members(group_id,username)
);
CREATE TABLE IF NOT EXISTS decisions (
 group_id VARCHAR(36) PRIMARY KEY REFERENCES activity_groups(id), main_id VARCHAR(36) NOT NULL,
 backup_id VARCHAR(36) NOT NULL, payload CLOB NOT NULL, CHECK(main_id<>backup_id),
 FOREIGN KEY(group_id,main_id) REFERENCES candidates(group_id,id),
 FOREIGN KEY(group_id,backup_id) REFERENCES candidates(group_id,id)
);
CREATE TABLE IF NOT EXISTS publications (
 id VARCHAR(36) PRIMARY KEY, group_id VARCHAR(36) NOT NULL REFERENCES activity_groups(id),
 version BIGINT NOT NULL, published_at VARCHAR(40) NOT NULL, content CLOB NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE, UNIQUE(group_id,version)
);
