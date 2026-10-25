-- A random table can mix first and last words across its lines ("Alys Pascoe" + "Beatrice Flint" -> "Alys Flint").
alter table random_tables add column mix boolean not null default false;
