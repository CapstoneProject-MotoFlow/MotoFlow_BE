-- Admin can sign a user out of one device; the session keeps why it ended.
ALTER TABLE auth_session DROP CONSTRAINT auth_session_revoke_reason_check;
ALTER TABLE auth_session ADD CONSTRAINT auth_session_revoke_reason_check
    CHECK (revoke_reason IN ('LOGOUT','PASSWORD_CHANGED','PASSWORD_RESET','ACCOUNT_LOCKED','REFRESH_TOKEN_REUSED',
                             'SIGNED_OUT_BY_ADMIN'));
