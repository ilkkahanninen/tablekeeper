# Tablekeeper

TTRPG prep and notes as a graph, printed to paper for the table.

You keep places, characters, NPCs, items, facts and sessions as linked notes. Before a session you tick what's likely to come up, and Tablekeeper prints one brief document to bring to the table: the map, what the players' characters want, and everything about the places they'll visit.

The first game is **Heart: The City Beneath**. Games are data-only modules (JSON plus print templates), so another game needs no code changes.

## Features

- **Campaign page:** one table per type (Sessions, Landmarks, Delves, Characters, NPCs, Fallouts, Equipment, Resources, Factions…), with key columns, a filter box and a **Create** button under each table. Minor locations sit under their landmark, sessions are in date order, and dead NPCs are greyed out.
- **Node pages:**
  - Fields save as you leave them.
  - **Notes** and every other long-text field (Haunts, Events, What happened, a fallout's description…) show formatted until you click them: `**bold**`, `*italic*`, `- ` lists, and `[[Red Market]]` (or `[[L3]]`) as a link. Typing `[[` offers matching nodes; renaming a node updates its mentions. A mentioned node lists who mentions it under **Mentioned in**. Prints show the same formatting, mentions in bold.
  - The **Link** box finds something to link, with a button per possible relation, or creates it.
  - Dropdowns like **Location** or **Location / carried by** handle "one of these" links.
  - **Links** lists everything connected, grouped by type (*NPCs*, *Resources*…).
- **Heart:**
  - **Landmarks:** named tiers, domains and default stress.
  - **Delves:** between two landmarks; tier and domains are shown from them; a default stress die of their own. Events are separated by a blank line (a random table adds one). Prints show resistance as numbered boxes to tick off, and each event with a tick box.
  - **Minor locations** inside landmarks.
  - **Characters:** class, calling and beats, shown as "Thonal (Jori)".
  - **NPCs:** voice, appearance and dead.
  - **Equipment:** category, quality die, tags and limited uses.
  - **Resources:** die and tags.
  - **Fallouts** on characters and NPCs.
  - Rule-book hints on hover.
- **Random tables:** your own lists, with one item per line, linked to fields like *NPC: Title*.
  - A **Random** button fills the field; with several tables, it shows a menu.
  - New nodes are named by a roll.
  - *Mix first and last words* turns 20 "First Last" names into 400.
- **Map:** an automatic, repeatable layout. Landmarks sit by tier, delves between them, and minor locations around them. Available on screen and as an A4 PDF.
- **Inspiration:** what the party wants from play. Beats by calling, the rule book's class texts, and each character's fallouts.
- **Session packet** and **debrief:** see below.
- **Print cards:** an A6 GM card for any node.

## Session packet

Add a **Session** and tick the landmarks likely to come up and the characters taking part. **Print** then gives one landscape A4 PDF with the text in three columns:

- **Page 1:** the session's title, date and notes, and the whole map with the selected landmarks highlighted. Beside it, under **Last time**, the previous session's "What happened".
- **Characters:** one block per character, with calling · class, notes, beats, fallouts and their class's inspiration points (once per class).
- **Landmarks:** in tier order, with props, notes, haunts, who and what is there, minor locations with their contents, and facts with tick boxes.
- **Delves:** those between selected landmarks.
- **NPCs:** those at the selected landmarks or their minor locations.
- **Factions:** those at the selected landmarks, or with an NPC in the packet as a member.

## Run

```sh
docker compose up --build
# open http://localhost:8080
```

- Data lives in the `pgdata` volume. To back it up:
  ```sh
  docker compose exec db pg_dump -U tablekeeper tablekeeper > backups/$(date +%F).sql
  ```
- **From IntelliJ:**
  1. Run `docker compose up -d db` (Postgres on `127.0.0.1:5432`).
  2. Stop the `app` container (`docker compose stop app`); both use port 8080.
  3. Run the shared **Tablekeeper** run configuration (`.run/`).
- **Without Docker:** start Postgres, then `./gradlew run`.
- Schema migrations (Flyway) run on startup.

| Env var | Default | |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tablekeeper` | |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `tablekeeper` | |
| `AUTH_MODE` | `none` | `none` = a single implicit `local` user. `session` is reserved for login (not built yet). |
| `MODULES_DIR` | `modules` | Game module folders |
| `TEMPLATE_CACHE` | `true` | `false` reloads templates on every request (module print templates included) |
| `PORT` | `8080` | |

After the session, write **What happened** on the session, and use the **Debrief** box below its form to bring the campaign up to date. Every change saves to its own node at once:

- **Characters** (the ticked ones): edit beats, press **Done** on a finished one to clear it for the next, and add, edit or remove (×) fallouts.
- **NPCs** at the session's landmarks: move them or mark them dead.
- **Equipment** and **resources** there, or carried by anyone in the packet: move them or hand them to someone.

## A quick tour

1. Create a campaign (Heart).
2. Pick *Landmark* and click **Add** in the top bar, then rename it "Red Market". Set tier *Tier 1* and domains Occult + Desolate.
3. On Red Market, type "Silvia" in the **Link** box, pick *NPC* in the create row and click **Create**. Her **Location** now says Red Market.
4. Create a *Delve* the same way. Its Tier and Domains rows show Red Market's.
5. Under **Tables**, add a list of names, tick *NPC: Title*, and use **Random** on an NPC.
6. Add a **Session**, tick Red Market and a character, and click **Print**.

## How it works

Kotlin 2.1 (JDK 21), Ktor + htmx, Pebble templates, Postgres 16 (jOOQ as plain SQL, Flyway), openhtmltopdf for PDFs.

- **Data:** a generic graph, scoped by campaign, and every campaign by its owner.
  - `nodes`: `type` (e.g. `heart.landmark`), a short `code` (`L3`), `title`, `body` (notes), `props` (JSONB, validated by the module's JSON Schema) and `visibility`.
  - `edges`: links between nodes, e.g. `located_at`.
  - Random tables are campaign data in their own tables.
- **Game modules** (`modules/<id>/module.json`): node types, edge types and props schemas, plus settings for pages, print, the map and session packets.
  - Types inherit through `extends` (a Heart Landmark is a `core.place`), and modules extend other modules.
  - Templates in a module folder (`modules/heart/print/*.peb`, `modules/heart/views/*.peb`) are used without a rebuild.
  - Bad settings stop the app at startup, with a message.
- **Print:** HTML to PDF. Multi-column pages are made by laying out one long narrow column and placing three of those pages per landscape sheet (`print/Imposition.kt`).
- **Map:** laid out on the server (`map/MapLayout.kt`), so the screen and the PDF draw the same SVG.

```
modules/                  game modules (plain files)
  core/module.json        shared types: place, pc, npc, item, faction, fact, session + edge types
  heart/                  Heart: module.json, print/ (cards, session packet), views/ (Inspiration)
src/main/kotlin/tablekeeper/
  core/                   campaigns, graph (nodes/edges, in-memory snapshot), random tables, errors
  modules/                module.json shape, resolved module, startup checks, link rules, schema -> form, text parts
  templates/              the Pebble engine (screen and print)
  views/                  module views (e.g. Inspiration)
  map/                    map layout and model
  print/                  print model, session packets, imposition
  web/                    routes by area (htmx), node page and campaign home view models
src/main/resources/       Flyway migrations, templates, static (htmx 2.0.11 vendored, app.css, app.js)
```

## Writing a game module

A minimal module that adds a type to `core`:

```json
{
  "id": "frontier-scum",
  "name": "Frontier Scum",
  "extends": ["core"],
  "nodeTypes": [
    { "id": "fs.town", "extends": "core.place", "label": "Town", "codePrefix": "T",
      "quickCreate": [ { "type": "core.npc", "rel": "located_at:in" } ] }
  ],
  "edgeTypes": []
}
```

Restart the app. Town gets a generated form, the link rules it inherits from places, a generic print card, and a create choice for NPCs located there.

Links are written as `rel`: `<edge type>:out` (this node → the other) or `:in` (the other → this node). `modules/heart/module.json` uses nearly every setting below and is the best example.

**Node type settings** (all optional; inherited through `extends`):

| Setting | What it does | Heart example |
|---|---|---|
| `props` | JSON Schema for fields; generates the form | landmark tier, domains… |
| `plural` | Heading label (default: label + "s") | `"Equipment"` |
| `icon` | Shown for the type's nodes on screen instead of their code (hover: "Landmark L1"); a name from `static/icons.svg` | landmark `castle`, NPC `drama` |
| `displayTitle` | How the name is shown everywhere but its own title field | `["{title}", "({player})"]` → "Thonal (Jori)" |
| `quickCreate` | Create choices in the **Link** box | landmark → Delve, NPC, Resource… |
| `linkSlots` | "One of these" links as a dropdown, with a "— Pool —" choice | Location / carried by |
| `linkLimits` | Cap links; refused when full | delve `connects:out` ≤ 2 |
| `linkedProps` | Read-only values from linked nodes | delve shows its landmarks' tier and domains |
| `context` | Where a node belongs, shown in brackets in pickers; nests it on the campaign page and map | minor location → its landmark |
| `overview`, `overviewSort`, `overviewMuted` | Campaign page columns (props or `@rel`), row order, greyed rows | sessions by `date`, dead NPCs |
| `printTemplate`, `printSummary`, `printNested` | Print card template; how it's listed on other cards (`group` + parts); nested lists | equipment "Kill d8 · Brutal" |
| `mapShape`, `mapDepth`, `mapBetween` | Map shape; depth from a prop; drawn between linked places | landmarks by tier, delves between |
| `checklists`, `packet` | Tick lists on the page; session packet contents (`between`, `around`, `related`, `columns`, `recap` for "Last time") | Heart session |
| `debrief` | Lists on the page to update the packet's nodes after play: `types`, `fields` edited in place, `done` (clear an `x-row`), `slots` (Location dropdowns, and nodes located inside the packet), `links` (`rel`, `fields`, `create`, `remove`) | session: beats, fallouts, dead, Location |

**Edge type settings:** `label` / `inverseLabel`, allowed `from` / `to` types, and `groupByType`, which lists the `to` side by type: *NPCs*, *Resources*…

**Module settings:**
- `hide`: inherited types not offered for creation.
- `patchTypes`: change settings on inherited types.
- `overviewOrder`: campaign page order.
- `views`: extra pages with a template, `types`, `groupBy` and `links`.

**Field options** (inside `props`), in addition to the widgets chosen by schema type (`integer` → number, `enum` → select, `array` of `enum` → checkboxes, `boolean` → checkbox, `string` → text):

| Option | Effect |
|---|---|
| `"x-widget": "textarea"` | Multi-line text |
| `"x-widget": "list"` | Items separated by a blank line, formatted like notes (bold, italic, `- ` lists, `[[mentions]]`); printed with a tick box each; **Random** adds an item |
| `"x-track": true` | On an integer: printed as that many numbered boxes to tick off (up to 40) |
| `"x-wide": true` | Full-width row |
| `"x-row": "Beat 1"` | Fields sharing a value sit on one labelled line, also on cards |
| `"x-enumLabels"` | Show an option differently from its stored value (`"d8": "d8 Professional"`) |
| `"x-enumDescriptions"` | Hover hint per option; printed on cards |
| `"x-enumNotes"` | Text per option for module views only, e.g. Heart's class inspiration (`groupBy` hint); not on forms or cards |
| `"x-hideIf"` / `"x-showIf"` | Hide or show a field depending on another field; hidden values are cleared |
| `"x-trueLabel"` | Text for a ticked checkbox (`"dead"`); printed as a tag next to the name |
| `"x-depth"` | Map depth per enum value (`"Tier 2": 2`) |

`printSummary` and `displayTitle` parts use `{prop}` for the display text and `{prop.value}` for the stored value. A part whose values are all empty is left out.

## Development

```sh
./gradlew build                                                     # compile + tests
./gradlew test --tests 'tablekeeper.modules.GameModulesTest'        # one class
```

Unit tests cover module loading and settings, the map layout, campaign-page tables, session packet selection, module views and random tables. Routes and database queries have no automated tests and are checked by running the app. `CLAUDE.md` has notes for AI coding agents.

## Known gaps

- **No login yet:** `AUTH_MODE=session` (login page, sessions, Argon2, CSRF) is still to come. Data is already per user, so turning it on needs no migration.
- **Player handouts:** the data model and the `…/print.pdf?audience=players` route exist, but node visibility is hidden from the UI for now.
- **PDF fonts:** the built-in fonts cover Latin-1 (ä, ö, å) but not symbols like →. Embed a TTF via `PdfRendererBuilder.useFont` for more.

## License

The code is released under the [MIT License](LICENSE). The type icons in `static/icons.svg` are from [Lucide](https://lucide.dev) (ISC License, included in the file).

**Heart: The City Beneath** is © Rowan, Rook and Decard. The game text in `modules/heart/` (class inspiration texts, equipment and resource tag descriptions, resource die descriptions and similar) comes from the rule book. It belongs to its publisher and is **not** covered by the MIT License. Tablekeeper is an unofficial fan tool, not affiliated with or endorsed by Rowan, Rook and Decard.
