-- Heart landmark tiers became named values ("Tier 0".."Tier 3", "Rogue", "Fracture", "The Eight Heavens").
-- Converts tiers saved as numbers 0..3.
update nodes
set props = jsonb_set(props, '{tier}', to_jsonb('Tier ' || (props->>'tier')))
where type = 'heart.landmark'
  and jsonb_typeof(props->'tier') = 'number'
  and props->>'tier' in ('0', '1', '2', '3');
