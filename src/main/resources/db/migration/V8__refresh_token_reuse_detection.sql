-- Refresh token reuse detection: the token replaced by the last rotation is kept, so presenting
-- it again (a copied token) revokes the whole session.
ALTER TABLE auth_session ADD COLUMN previous_refresh_token_hash VARCHAR(64);
CREATE INDEX ix_auth_session_previous_refresh_token_hash ON auth_session (previous_refresh_token_hash);

ALTER TABLE auth_session DROP CONSTRAINT auth_session_revoke_reason_check;
ALTER TABLE auth_session ADD CONSTRAINT auth_session_revoke_reason_check
    CHECK (revoke_reason IN ('LOGOUT','PASSWORD_CHANGED','PASSWORD_RESET','ACCOUNT_LOCKED','REFRESH_TOKEN_REUSED'));
