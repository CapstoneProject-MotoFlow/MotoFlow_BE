-- One row per signed-in device. The access token (JWT) carries the session id, so revoking
-- the row ends the session at once (logout, password change, NFR-S01).
-- Not in the logical design yet: add it to MotoFlow_Logical_3NF before Report 4.
CREATE TABLE auth_session (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES user_account(id),
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    last_refreshed_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    revoke_reason VARCHAR(30) CHECK (revoke_reason IN ('LOGOUT','PASSWORD_CHANGED','PASSWORD_RESET','ACCOUNT_LOCKED')),
    user_agent VARCHAR(255),
    ip_address VARCHAR(45)
);

CREATE INDEX ix_auth_session_user_id ON auth_session (user_id);
