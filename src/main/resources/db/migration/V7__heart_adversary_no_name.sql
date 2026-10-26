-- Heart adversaries lost their "name" field: the title is the name. A name given to an adversary still titled
-- "New …" (as created) becomes its title; any other name is kept at the start of its notes.
update nodes
set title = case when title like 'New %' then props->>'name' else title end,
    body = case when title like 'New %' or props->>'name' = title then body
                else concat_ws(E'\n\n', 'Name: ' || (props->>'name'), nullif(body, '')) end,
    props = props - 'name'
where type = 'heart.adversary'
  and coalesce(props->>'name', '') <> '';

update nodes set props = props - 'name' where type = 'heart.adversary' and props ? 'name';
