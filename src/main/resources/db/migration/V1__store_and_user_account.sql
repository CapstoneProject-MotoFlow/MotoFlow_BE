CREATE TABLE store (
    id BIGSERIAL PRIMARY KEY,
    store_code VARCHAR(20) NOT NULL UNIQUE,
    store_name VARCHAR(150) NOT NULL,
    address VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','ACTIVE','INACTIVE')),
    copied_from_store_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE warehouse (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL UNIQUE,
    warehouse_name VARCHAR(150) NOT NULL,
    address VARCHAR(255),
    phone VARCHAR(20),
    is_central BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE user_account (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    email VARCHAR(100),
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','OWNER','BRANCH_MANAGER','STAFF')),
    store_id BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED')),
    failed_login_count SMALLINT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMP,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL,
    CHECK ((role IN ('BRANCH_MANAGER','STAFF')) = (store_id IS NOT NULL))
);

CREATE TABLE user_permission (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    permission_code VARCHAR(30) NOT NULL CHECK (permission_code IN ('CATALOG_EDIT','RETAIL_PRICE_EDIT')),
    granted_by BIGINT NOT NULL,
    granted_at TIMESTAMP NOT NULL,
    UNIQUE (user_id, permission_code)
);

CREATE TABLE permission_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    change_type VARCHAR(30) NOT NULL CHECK (change_type IN ('ACCOUNT_CREATED','ROLE_CHANGED','STORE_CHANGED','PERMISSION_GRANTED','PERMISSION_REVOKED')),
    permission_code VARCHAR(30),
    old_value VARCHAR(100),
    new_value VARCHAR(100),
    reason VARCHAR(255),
    changed_by BIGINT NOT NULL,
    changed_at TIMESTAMP NOT NULL
);

ALTER TABLE store ADD CONSTRAINT fk_store_copied_from_store_id FOREIGN KEY (copied_from_store_id) REFERENCES store(id);
ALTER TABLE warehouse ADD CONSTRAINT fk_warehouse_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE user_account ADD CONSTRAINT fk_user_account_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE user_account ADD CONSTRAINT fk_user_account_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE user_permission ADD CONSTRAINT fk_user_permission_user_id FOREIGN KEY (user_id) REFERENCES user_account(id);
ALTER TABLE user_permission ADD CONSTRAINT fk_user_permission_granted_by FOREIGN KEY (granted_by) REFERENCES user_account(id);
ALTER TABLE permission_history ADD CONSTRAINT fk_permission_history_user_id FOREIGN KEY (user_id) REFERENCES user_account(id);
ALTER TABLE permission_history ADD CONSTRAINT fk_permission_history_changed_by FOREIGN KEY (changed_by) REFERENCES user_account(id);
