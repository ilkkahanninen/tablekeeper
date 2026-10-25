-- Users exist from day 1 so enabling login later needs no data migration.
-- In AUTH_MODE=none every request runs as the 'local' user.
create table users (
    id          uuid primary key default gen_random_uuid(),
    username    text not null unique,
    password_hash text,                       -- unused until AUTH_MODE=session
    created_at  timestamptz not null default now()
);

create table campaigns (
    id          uuid primary key default gen_random_uuid(),
    owner_id    uuid not null references users(id) on delete cascade,
    name        text not null,
    game_module text not null,                -- e.g. 'heart'
    created_at  timestamptz not null default now()
);
create index campaigns_owner_idx on campaigns(owner_id);

-- Graph core. Everything game-specific lives in `type` + `props`;
-- the module's JSON Schema validates props in the app.
create table nodes (
    id          uuid primary key default gen_random_uuid(),
    campaign_id uuid not null references campaigns(id) on delete cascade,
    type        text not null,                -- e.g. 'heart.landmark'
    code        text not null,                -- short print reference, e.g. 'L3'
    title       text not null,
    body        text not null default '',
    props       jsonb not null default '{}'::jsonb,
    tags        text[] not null default '{}',
    visibility  text not null default 'gm' check (visibility in ('gm', 'players', 'revealed')),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    unique (campaign_id, code)
);
create index nodes_campaign_type_idx on nodes(campaign_id, type);
create index nodes_props_idx on nodes using gin (props);

create table edges (
    id          uuid primary key default gen_random_uuid(),
    campaign_id uuid not null references campaigns(id) on delete cascade,
    from_id     uuid not null references nodes(id) on delete cascade,
    to_id       uuid not null references nodes(id) on delete cascade,
    type        text not null,                -- e.g. 'located_at'
    props       jsonb not null default '{}'::jsonb,
    created_at  timestamptz not null default now(),
    unique (from_id, to_id, type),
    check (from_id <> to_id)
);
create index edges_from_idx on edges(from_id);
create index edges_to_idx on edges(to_id);
