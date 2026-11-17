-- Workspace lifecycle, personal workspaces and member ownership.

ALTER TABLE workspace
    ADD COLUMN description TEXT,
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN personal BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE workspace
    ADD CONSTRAINT ck_workspace_status CHECK (status IN ('ACTIVE', 'ARCHIVED'));

ALTER TABLE workspace_member
    ADD COLUMN id UUID,
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE workspace_member
SET id = gen_random_uuid()
WHERE id IS NULL;

ALTER TABLE workspace_member
    ALTER COLUMN id SET NOT NULL;

UPDATE workspace_member
SET joined_at = created_at,
    updated_at = created_at;

-- Preserve the legacy owner as an OWNER membership before removing owner_user_id.
INSERT INTO workspace_member (id, workspace_id, user_id, role, status, joined_at, updated_at, created_at)
SELECT gen_random_uuid(), w.id, w.owner_user_id, 'OWNER', 'ACTIVE', w.created_at, w.updated_at, w.created_at
FROM workspace w
WHERE NOT EXISTS (
    SELECT 1
    FROM workspace_member m
    WHERE m.workspace_id = w.id
      AND m.user_id = w.owner_user_id
);

UPDATE workspace_member m
SET role = 'OWNER'
FROM workspace w
WHERE m.workspace_id = w.id
  AND m.user_id = w.owner_user_id
  AND m.role <> 'OWNER';

ALTER TABLE workspace_member
    DROP CONSTRAINT pk_workspace_member,
    ADD CONSTRAINT pk_workspace_member PRIMARY KEY (id),
    ADD CONSTRAINT uk_workspace_member UNIQUE (workspace_id, user_id),
    ADD CONSTRAINT ck_workspace_member_status CHECK (status IN ('ACTIVE'));

CREATE UNIQUE INDEX uk_workspace_member_owner
    ON workspace_member (workspace_id)
    WHERE role = 'OWNER';

ALTER TABLE workspace
    DROP COLUMN owner_user_id;
