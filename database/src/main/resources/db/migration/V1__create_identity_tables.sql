-- Identity: users, workspaces and workspace membership.

CREATE TABLE user_account (
    id           UUID        NOT NULL,
    email        TEXT        NOT NULL,
    display_name TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_account PRIMARY KEY (id),
    CONSTRAINT ck_user_account_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE UNIQUE INDEX uk_user_account_email ON user_account (lower(email));

CREATE TABLE workspace (
    id            UUID        NOT NULL,
    name          TEXT        NOT NULL,
    owner_user_id UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_workspace PRIMARY KEY (id),
    CONSTRAINT fk_workspace_owner FOREIGN KEY (owner_user_id) REFERENCES user_account (id)
);

CREATE INDEX idx_workspace_owner ON workspace (owner_user_id);

CREATE TABLE workspace_member (
    workspace_id UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    role         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_workspace_member PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT fk_workspace_member_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_member_user FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT ck_workspace_member_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

CREATE INDEX idx_workspace_member_user ON workspace_member (user_id);
