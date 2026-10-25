-- User-defined random tables: one item per line. Campaign data, not game-module data.
create table random_tables (
    id          uuid primary key default gen_random_uuid(),
    campaign_id uuid not null references campaigns(id) on delete cascade,
    name        text not null,
    items       text not null default '',
    created_at  timestamptz not null default now()
);
create index random_tables_campaign_idx on random_tables(campaign_id);

-- Fields a table fills via a Random button, e.g. 'core.npc:title'. One table per field.
create table random_table_fields (
    campaign_id uuid not null references campaigns(id) on delete cascade,
    table_id    uuid not null references random_tables(id) on delete cascade,
    field       text not null,
    primary key (campaign_id, field)
);
