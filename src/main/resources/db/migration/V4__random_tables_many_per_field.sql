-- A field can have several random tables (the node page then offers a menu to pick one).
alter table random_table_fields drop constraint random_table_fields_pkey;
alter table random_table_fields add primary key (table_id, field);
create index random_table_fields_field_idx on random_table_fields(campaign_id, field);
