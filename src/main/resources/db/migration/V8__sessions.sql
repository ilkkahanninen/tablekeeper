-- Login (AUTH_MODE=session): one row per logged-in browser. The cookie holds a random token; only its SHA-256 is
-- stored, so the table alone logs no one in.
create table sessions (
    token_hash  bytea primary key,
    user_id     uuid not null references users(id) on delete cascade,
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null
);
create index sessions_user_id on sessions (user_id);

-- A name is one account whatever its capitals: "Alice" and "alice" can't both sign up
create unique index users_username_lower on users (lower(username));
