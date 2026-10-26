-- Heart adversaries: "names" and "descriptors" became "name" and "descriptor", and "resources" and "equipment" were
-- dropped (they're linked Resource and Equipment nodes). Moves values to the new keys; text in the dropped fields is
-- appended to the notes, so nothing typed is lost.
update nodes
set body = concat_ws(E'\n\n', nullif(body, ''),
                     case when coalesce(props->>'resources', '') <> '' then 'Resources: ' || (props->>'resources') end,
                     case when coalesce(props->>'equipment', '') <> '' then 'Equipment: ' || (props->>'equipment') end),
    props = (props - 'names' - 'descriptors' - 'resources' - 'equipment')
            || case when props ? 'names' then jsonb_build_object('name', props->'names') else '{}'::jsonb end
            || case when props ? 'descriptors' then jsonb_build_object('descriptor', props->'descriptors') else '{}'::jsonb end
where type = 'heart.adversary'
  and (props ?| array['names', 'descriptors', 'resources', 'equipment']);
