# Tablekeeper

TTRPG prep and notes as a graph, printed to paper for the table.
First game: **Heart: The City Beneath**. Other games plug in as data-only modules.

## Run

```sh
docker compose up --build
# open http://localhost:8080
```

- Data lives in the `pgdata` volume.
- Backup: `docker compose exec db pg_dump -U tablekeeper tablekeeper > backups/$(date +%F).sql`
- From the IDE: `docker compose up -d db` (Postgres on `127.0.0.1:5432`), then run the shared **Tablekeeper** run configuration (`.run/`) in IntelliJ, or `./gradlew run`. Stop the `app` container first (`docker compose stop app`), since both use port 8080.
- Without Docker: start Postgres, then `./gradlew run` (env vars below) and `./gradlew test`.

| Env var | Default | |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tablekeeper` | |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `tablekeeper` | |
| `AUTH_MODE` | `none` | `none` = single implicit `local` user. `session` reserved for login (not built yet). |
| `MODULES_DIR` | `modules` | Game module folders |
| `TEMPLATE_CACHE` | `true` | `false` reloads templates on every request |
| `PORT` | `8080` | |

## First flow to try

1. Create a campaign (Heart).
2. Pick Landmark in *Add* in the top bar (there on every campaign page) and name it "Red Market": tier 1, domains Occult + Desolate. Fields save as you leave them; there's no Save button.
3. On Red Market, type "Silvia" in the *Link* box. Pick *NPC* in the create row under it, then *Create* to create and open her, or ↓ to create her and stay (with the box empty, it's named "New NPC"). Her *Location* now says Red Market ("— Pool —" means nowhere yet).
4. Type "Gut" in the *Link* box, create it as *Delve · reached via*. Its Domains show "L1 Red Market: Occult, Desolate".
5. Typing the name of something that already exists lists it with a button per relation (Any), e.g. *has here*, *relates to*. Pick a relation in the dropdown to narrow it.
6. Red Market → **Print**: props, notes, "has here: Silvia (N1)", facts with tick boxes.
7. The campaign page lists everything in one table per type (tier, domains, who's where…), with a filter box.

## Layout

```
modules/                  game modules: plain files, no rebuild needed
  core/module.json        shared types: place, pc, npc, item, faction, fact, session + edge types
  heart/module.json       Landmark, Minor location, Delve, Character, Resource, Equipment; hides generic place/pc/item
  heart/print/landmark.peb  Heart print card
src/main/kotlin/tablekeeper/
  App.kt                  wiring
  auth/                   user resolution (AUTH_MODE)
  core/                   CampaignService, GraphService (nodes/edges), Audience
  modules/                module loader, type inheritance, link rules, schema -> form
  print/                  print model + HTML -> PDF (openhtmltopdf)
  web/                    routes (htmx), Pebble loader
src/main/resources/
  db/migration/           Flyway SQL
  templates/              screen + generic print templates
  static/                 htmx 2.0.11 (vendored), app.css
```

## Data model

- `nodes`: `type` (e.g. `heart.landmark`), `code` (short print reference: `L3`), `title`, `body`, `props` (JSONB), `visibility` (`gm` / `players` / `revealed`). Visibility and player handouts are hidden from the UI for now: new nodes are `gm`, saving keeps the stored value, and the handout still renders at `…/print.pdf?audience=players`.
- `edges`: `from_id` → `to_id`, `type` (e.g. `located_at`), `props` (JSONB)
- Every query is scoped by campaign, and every campaign by `owner_id`, so turning on login later needs no data migration.

## Adding a game (example: Frontier Scum)

`modules/frontier-scum/module.json`:

```json
{
  "id": "frontier-scum",
  "name": "Frontier Scum",
  "extends": ["core"],
  "nodeTypes": [
    { "id": "fs.town", "extends": "core.place", "label": "Town", "codePrefix": "T",
      "quickCreate": [ { "type": "core.npc", "rel": "located_at:in" } ] },
    { "id": "fs.relic", "extends": "core.item", "label": "Relic piece", "codePrefix": "R",
      "props": { "type": "object", "properties": {
        "layer": { "enum": ["mundane", "cosmic"], "title": "Layer" } } } }
  ],
  "edgeTypes": [
    { "id": "part_of", "label": "is part of", "inverseLabel": "pieces", "from": ["fs.relic"], "to": ["fs.relic"] }
  ]
}
```

Restart the app. You get:

- forms generated from the schema
- link rules inherited from core (an NPC can be *located at* a Town, because Town extends `core.place`)
- the generic print card (add `"printTemplate"` to customise it)
- a create choice in the *Link* box on Town pages that creates an NPC already located there. `rel` is `<edge type>:out` (this node → new one) or `:in` (new one → this node); bad entries stop the app at startup.

To show values gathered from linked nodes instead of storing them, use `linkedProps`. Heart Delves have no domains of their own; they show each connected landmark's domains, one line per landmark, since the domain shifts from one landmark's to the other's as characters travel the delve:

```json
"linkedProps": [ { "prop": "domains", "rel": "connects:out", "label": "Domains" } ]
```

The values appear read-only on the page and on print cards, grouped by linked node.

To list linked nodes' own links on a card, use `printNested`. Heart landmarks list each minor location's contents under it:

```json
"printNested": [{ "rel": "part_of:in", "show": "located_at:in" }]
```

To show where a node belongs in dropdowns and search results, use `context`, naming a link whose far end goes in brackets. Heart's minor locations appear as "M1 Butcher's Row (Red Market)":

```json
"context": "part_of:out"
```

An edge type with `"groupByType": true` (core `located_at`) lists its links on the `to` side by type: a landmark shows *Characters*, *NPCs*, *Equipment* and *Resources* instead of one *has here* list, on its page, cards and packets.

## Campaign home

One table per type, in the order of the module's `overviewOrder` (types not listed follow). Columns come from each type's `overview`: a prop name shows that prop, and `"@rel"` shows the titles linked that way. Every link slot (e.g. Location) is added as a column automatically. A type with a `context` link is listed under the node at its far end, e.g. minor locations under their landmark.

```json
"overviewOrder": ["heart.landmark", "heart.delve", "heart.pc", …]
"overview": ["tier", "stress", "domains"]        // heart.landmark
"overview": ["@connects:out", "resistance"]      // heart.delve
```

`"overviewMuted": "dead"` greys out rows where that boolean prop is ticked, as dead NPCs are.

An unknown prop, relation or type stops the app at startup.

## Random tables

*Tables* in the top bar holds your own lists, separate from the game module. A table is a list with one item per line; blank lines are ignored. Tick the fields it fills, e.g. *NPC: Title* or *NPC: Voice*. Those fields then get a **Random** button on node pages, which fills in a random item and saves. New nodes are named from their type's title tables: a random one of them is rolled, both from *Add* in the top bar and from the *Link* box when its search box is empty. A type with no title table gets "New NPC" and so on. Tick **Mix first and last words** to combine lines: a roll joins one line's first name with another line's surname, so 20 "First Last" lines give 400 names. One-word lines are used whole. A field can have several tables, e.g. elven, dwarf and street names on *NPC: Title*. Its button then reads **Random ▾** and lists the tables; picking one rolls on it. Titles and text fields can be linked; dropdowns, checkboxes and numbers can't.

## Session packet

One brief landscape A4 document for the next session, with the text in three columns. Add a **Session** (S1, S2…). On its page, tick the landmarks likely to come up and the characters taking part, then **Print**. For Heart the packet has:
- **page 1:** the session's title, date and notes, and the whole map with the selected landmarks filled red
- **Characters:** Inspiration for the ticked characters only
- **Landmarks:** each selected landmark, by tier, with its props, notes, haunts, who and what is there (NPC voice and appearance, resources' die and tags), minor locations with their contents, and facts with tick boxes
- **Delves:** only those between selected landmarks
- **NPCs:** those at a selected landmark or in one of its minor locations, with voice, appearance, notes and what they carry
- **Factions:** those at a selected landmark or in one of its minor locations, and those any NPC in the packet is a member of

Module settings (Heart sets them on `core.session` via `patchTypes`):

```json
"checklists": [{ "rel": "features:out", "types": ["heart.landmark"] }, { "rel": "features:out", "types": ["heart.pc"] }],
"packet": { "rel": "features:out", "between": [{ "type": "heart.delve", "rel": "connects:out" }],
            "around": [{ "type": "core.npc", "rel": "located_at:out" }], "columns": 3 },
"printTemplate": "heart/print/session.peb"
```

- `checklists` puts checkbox lists on the page; ticking links via `rel`.
- `packet` gives the print template:
  - `map`: the whole map, selected places highlighted
  - `views`: the module views over the selected nodes, e.g. Inspiration
  - `sections`: print models of the other selected nodes, plus:
    - the `between` ones: nodes whose every `rel` link ends at a selected node
    - the `around` ones: nodes with a `rel` link to a selected node, or to one inside it via its `context`, e.g. a minor location
    - the `related` ones: nodes with a `rel` link to anything already in the packet, e.g. factions of its NPCs
- With `columns` > 1, the print template is rendered twice:
  - `part = "cover"` (the map page), as is
  - `part = "columns"`, the text, laid out as one column on narrow pages; the app places these side by side on landscape sheets
- The landmark card and the packet share `heart/print/_place.peb`; the Inspiration page and the packet share `heart/views/_inspiration.peb`.
- `"x-trueLabel": "dead"` on a checkbox makes a ticked box read "dead" instead of "yes". On print cards and packets it's shown as a tag next to the name rather than a row.

## Module views

A module can ship extra campaign pages. Heart's *Inspiration* page shows the GM what the party wants from play: beats grouped by calling, and the rule book's class texts for each class in play. The class texts are `x-enumDescriptions` on the class field, so they also show as hover hints on the character form.

```json
"views": [{ "id": "inspiration", "label": "Inspiration", "template": "heart/views/inspiration.peb",
            "types": ["heart.pc"], "groupBy": ["calling", "class"] }]
```

The page lives at `/c/{id}/v/{view id}` and is linked in the top bar. Its template gets these variables:
- `nodes`: the campaign's nodes of `types`. Each has `node` and `fields`, the props as form fields by name, giving `.text`, `.value`, `.labels` and `.hints`.
- `groups[prop]`: for each `groupBy` prop, the nodes grouped by value in option order, with "Not set" last. Each group has `value`, `label`, `hint` and `nodes`.

An unknown type or `groupBy` prop stops the app at startup.

## Map

Each campaign has a map (*Map* in the top bar, or `/c/{id}/map`), plus a printable A4 version at `/c/{id}/map.pdf`. The layout is automatic and repeatable:
- Landmarks are laid out first, linked directly through their delves. Each connected group is laid out separately.
- Delves (`mapBetween`) then go between the landmarks they connect, and minor locations (`context`) fan out above their landmark.
- The same links always give the same map. Module settings choose what's drawn:

```json
"mapShape": "circle"   // core.place (so Heart landmarks); "diamond" for delves, "dot" for minor locations
"mapDepth": "tier"     // heart.landmark: higher tiers drawn lower, closer to the Heart
"mapBetween": "connects:out"   // heart.delve: drawn between the landmarks it connects (so its depth is their average)
```

`mapDepth` names an integer prop, or an enum prop whose `"x-depth"` maps values to depths. Heart's tier is `{"Tier 0": 0, …, "Tier 3": 3}`; Rogue, Fracture and The Eight Heavens have no depth, so they're placed by their links. With `mapDepth`, places are fixed at a height set by that prop, and faint guides ("TIER 1", "TIER 2"…) mark the levels. Places without a value, like minor locations, follow their links.

Places are node types with a `mapShape`. Any link between two places is drawn as a line, dashed when it touches a dot.

To change settings on a type your game inherits, rather than defines, use `patchTypes` at the module's top level. Each setting given there replaces the inherited one. Heart uses it to give core NPCs create choices for carried resources and equipment:

```json
"patchTypes": [{ "id": "core.npc", "quickCreate": [{ "type": "heart.resource", "rel": "carries:out" }, …] }]
```

Patching a type that doesn't exist stops the app at startup.

To control how a type is listed on other nodes' cards, use `printSummary`. Heart's Equipment is listed under its own *Equipment* heading on character and NPC cards (and anywhere else it's linked), with its category, die and tags:

```json
"printSummary": { "group": "Equipment", "parts": ["{category} {quality.value}", "{tags}"] }
```

This prints as "Blunderbuss (E1): Kill d8 · Brutal, Reload". `{prop}` is the display text and `{prop.value}` the stored value (`d8` rather than "d8 Professional"). Empty values drop out, and a part whose values are all empty is dropped whole, so `"uses: {limitedUses}"` prints "uses: 3" or nothing. Parts are joined with " · ".

Node types can cap their links with `linkLimits`, keyed like `rel` and inherited per key. Once full, that relation's buttons in the *Link* box are disabled (hover shows why), and it stops being offered as a create choice:

```json
"linkLimits": { "connects:out": 2 }     // heart.delve: connects at most two landmarks
```

For "exactly one of these" links, use `linkSlots`. A slot is a dropdown on the node's page holding at most one link across its rels, with an empty "— Pool —" choice:

```json
"linkSlots": [{ "label": "Location", "rels": ["located_at:out"] }]                 // core.npc, core.pc
"linkSlots": [{ "label": "Location / carried by", "rels": ["located_at:out", "carries:in"] }]   // core.item: at a place or carried
```

Slot rels leave the *Link* box but still show under *Links*. Linking from the other end replaces the slot's current link. For example, adding a resource under a landmark's *has here*, or to an NPC's *carries*, moves it there. The carrier's side isn't a slot, so an NPC can carry many items.

Schema → form widgets: `integer` → number, `enum` → select, `array` of `enum` → checkboxes, `boolean` → checkbox, `"x-widget": "textarea"` → textarea, other `string` → text. Add `"x-enumDescriptions": {"Fragile": "…"}` to an `enum` or `array` of `enum` field to show a hover hint per option, as Heart's resource Tags do. Add `"x-hideIf": {"category": ["Miscellaneous"]}` to hide a field while another field has one of those values, as Heart's equipment Quality does. `"x-showIf": {"tags": ["Limited X"]}` is the reverse: the field shows only while one of those values is chosen, as equipment Limited uses does. `"x-wide": true` gives a field a full-width row of its own, as NPC Voice and Appearance have. `"x-row": "Beat 1"` puts consecutive fields with the same value on one line labelled "Beat 1", on the page and on cards, as Heart's character beats do. A hidden field's value is cleared on save and isn't printed. `"x-enumLabels": {"d8": "d8 Professional"}` shows an option differently from its stored value, on screen and on cards.

## Known gaps / next steps

- **Compiles, barely tested.** `./gradlew build` passes (Kotlin 2.1, JDK 21), including the 5 `GameModulesTest` unit tests. The SQL and module schemas were tested against Postgres 16 and a JSON Schema validator. Nothing that needs a database has tests yet, and the app (routes, Pebble templates, PDF output) has not been run end to end.
- `[[wikilinks]]` in notes → edges, and markdown rendering.
- `AUTH_MODE=session`: login page, Ktor Sessions, Argon2, CSRF tokens.
- PDF fonts: the built-in PDF fonts cover Latin-1 (ä, ö, å fine) but not symbols like →. Embed a TTF via `PdfRendererBuilder.useFont` for wider coverage.
- Graph view (Cytoscape) for prep.
