-- Environment table
CREATE TABLE IF NOT EXISTS environment (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_user_id BIGINT NOT NULL,
    modified_user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    short_name VARCHAR(128) DEFAULT NULL,
    color VARCHAR(32) DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE environment IS 'Database connection environment';
COMMENT ON COLUMN environment.id IS 'Primary key';
COMMENT ON COLUMN environment.gmt_create IS 'Create time';
COMMENT ON COLUMN environment.gmt_modified IS 'Modified time';
COMMENT ON COLUMN environment.create_user_id IS 'Creator user id';
COMMENT ON COLUMN environment.modified_user_id IS 'Modifier user id';
COMMENT ON COLUMN environment.name IS 'Environment name';
COMMENT ON COLUMN environment.short_name IS 'Environment abbreviation';
COMMENT ON COLUMN environment.color IS 'Color';

INSERT INTO environment (id, create_user_id, modified_user_id, name, short_name, color)
VALUES (1, 1, 1, 'Release Environment', 'RELEASE', 'GREEN');
INSERT INTO environment (id, create_user_id, modified_user_id, name, short_name, color)
VALUES (2, 1, 1, 'Test Environment', 'TEST', 'RED');

ALTER TABLE data_source ADD COLUMN environment_id BIGINT NOT NULL DEFAULT 2;
COMMENT ON COLUMN data_source.environment_id IS 'Environment id';

ALTER TABLE data_source ALTER COLUMN user_id SET DEFAULT 1;

ALTER TABLE data_source ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'SHARED';
COMMENT ON COLUMN data_source.kind IS 'Connection type';

UPDATE data_source SET user_id = 1;

ALTER TABLE inquery_user ADD COLUMN role_code VARCHAR(32) DEFAULT NULL;
COMMENT ON COLUMN inquery_user.role_code IS 'Role code';

ALTER TABLE inquery_user ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'VALID';
COMMENT ON COLUMN inquery_user.status IS 'User status';

ALTER TABLE inquery_user ADD COLUMN create_user_id BIGINT NOT NULL DEFAULT 1;
COMMENT ON COLUMN inquery_user.create_user_id IS 'Creator user id';

ALTER TABLE inquery_user ADD COLUMN modified_user_id BIGINT NOT NULL DEFAULT 1;
COMMENT ON COLUMN inquery_user.modified_user_id IS 'Modifier user id';

UPDATE inquery_user SET role_code = 'DESKTOP', user_name = '_desktop_default_user_name', password = '_desktop_default_user_name', nick_name = 'Desktop User' WHERE id = 1;

INSERT INTO inquery_user (user_name, password, nick_name, email, role_code) VALUES ('admin123', 'admin1234', 'Administrator', null, 'ADMIN');

CREATE UNIQUE INDEX uk_user_user_name ON inquery_user(user_name);

-- Team table
CREATE TABLE IF NOT EXISTS team (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_user_id BIGINT NOT NULL,
    modified_user_id BIGINT NOT NULL,
    code VARCHAR(128) NOT NULL,
    name VARCHAR(512) DEFAULT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'VALID',
    description TEXT DEFAULT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE team IS 'Team';
COMMENT ON COLUMN team.id IS 'Primary key';
COMMENT ON COLUMN team.gmt_create IS 'Create time';
COMMENT ON COLUMN team.gmt_modified IS 'Modified time';
COMMENT ON COLUMN team.create_user_id IS 'Creator user id';
COMMENT ON COLUMN team.modified_user_id IS 'Modifier user id';
COMMENT ON COLUMN team.code IS 'Team code';
COMMENT ON COLUMN team.name IS 'Team name';
COMMENT ON COLUMN team.status IS 'Team status';
COMMENT ON COLUMN team.description IS 'Team description';

CREATE UNIQUE INDEX uk_team_code ON team(code);

-- Team user mapping table
CREATE TABLE IF NOT EXISTS team_user (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_user_id BIGINT NOT NULL,
    modified_user_id BIGINT NOT NULL,
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE team_user IS 'User team mapping table';
COMMENT ON COLUMN team_user.id IS 'Primary key';
COMMENT ON COLUMN team_user.gmt_create IS 'Create time';
COMMENT ON COLUMN team_user.gmt_modified IS 'Modified time';
COMMENT ON COLUMN team_user.create_user_id IS 'Creator user id';
COMMENT ON COLUMN team_user.modified_user_id IS 'Modifier user id';
COMMENT ON COLUMN team_user.team_id IS 'Team id';
COMMENT ON COLUMN team_user.user_id IS 'User id';

CREATE INDEX idx_team_user_team_id ON team_user(team_id);
CREATE INDEX idx_team_user_user_id ON team_user(user_id);
CREATE UNIQUE INDEX uk_team_user ON team_user(team_id, user_id);

-- Data source access control table
CREATE TABLE IF NOT EXISTS data_source_access (
    id BIGSERIAL NOT NULL,
    gmt_create TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_user_id BIGINT NOT NULL,
    modified_user_id BIGINT NOT NULL,
    data_source_id BIGINT NOT NULL,
    access_object_type VARCHAR(32) NOT NULL,
    access_object_id BIGINT NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON TABLE data_source_access IS 'Data source access control';
COMMENT ON COLUMN data_source_access.id IS 'Primary key';
COMMENT ON COLUMN data_source_access.gmt_create IS 'Create time';
COMMENT ON COLUMN data_source_access.gmt_modified IS 'Modified time';
COMMENT ON COLUMN data_source_access.create_user_id IS 'Creator user id';
COMMENT ON COLUMN data_source_access.modified_user_id IS 'Modifier user id';
COMMENT ON COLUMN data_source_access.data_source_id IS 'Data source id';
COMMENT ON COLUMN data_source_access.access_object_type IS 'Access object type';
COMMENT ON COLUMN data_source_access.access_object_id IS 'Access object id (user or team based on type)';

CREATE INDEX idx_data_source_access_data_source_id ON data_source_access(data_source_id);
CREATE INDEX idx_data_source_access_access_object_id ON data_source_access(access_object_type, access_object_id);
CREATE UNIQUE INDEX uk_data_source_access ON data_source_access(data_source_id, access_object_type, access_object_id);

ALTER TABLE operation_saved ALTER COLUMN user_id SET DEFAULT 1;
UPDATE operation_saved SET user_id = 1;

ALTER TABLE operation_log ALTER COLUMN user_id SET DEFAULT 1;
UPDATE operation_log SET user_id = 1;

ALTER TABLE dashboard ALTER COLUMN user_id SET DEFAULT 1;
UPDATE dashboard SET user_id = 1;

ALTER TABLE chart ALTER COLUMN user_id SET DEFAULT 1;
UPDATE chart SET user_id = 1;
