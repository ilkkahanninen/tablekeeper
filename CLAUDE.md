# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Tablekeeper: TTRPG prep and notes stored as a graph (nodes + edges), printed to paper (PDF cards/handouts) for the table. Kotlin 2.1 / JVM 21, Ktor 3 (Netty) + htmx, Pebble templates, Postgres 16 via jOOQ + Flyway, openhtmltopdf for PDFs. See README.md for the user-facing walkthrough and the module authoring example.

## Commands

```sh
docker compose up --build                 # app + Postgres, http://localhost:8080
docker compose up -d db                   # just Postgres, published on 127.0.0.1:5432 (the app's default DATABASE_URL)
./gradlew run                             # app on the host (IntelliJ: shared "Tablekeeper" run config in .run/); stop the app container first, both use :8080
./gradlew test                            # all tests
./gradlew test --tests 'tablekeeper.modules.GameModulesTest'                       # one class
./gradlew test --tests 'tablekeeper.modules.GameModulesTest.landmark is a place*'  # one test (backtick names, use a wildcard)
```

Tests load the real `./modules` directory (passed as the `modulesDir` system property in `build.gradle.kts`) and need no database. Under docker compose, `./modules` is mounted and `TEMPLATE_CACHE=false`, so print templates reload live; `module.json` changes need an app restart.

## UI rules

- Cut taglines, helper text and secondary labels (subtitles, "No X yet." messages, placeholders, type labels next to codes). If a screen needs them to make sense, fix the layout instead: table headers, grouping, ordering.
- On screen a node is shown by its type icon (`node/_icon.peb`, needs `module` in the model; tip "Landmark L1"), never by its code; dropdown options show just the name. Prints keep codes. New icons: copy the Lucide SVG body into `static/icons.svg` and add the name to `ICONS`.
- Two button levels, one size (`static/app.css`): primary (a plain `<button>`, or `.primary` on a menu's summary) only for the action that completes a small form you just filled in (Add, Create) and the top bar's Create ▾; `class="secondary"` for everything else. `×` icons (`.unlink`) and dropdown rows (`.menu` / `.menu-item`) aren't buttons. No per-feature button styles.
- Make the decision that improves UX, DX and AX together, without breaking anything. Prefer module data and shared partials over one-off markup.
- Node pages autosave: `_form.peb` posts on `change` with `autosave=1`, and only `#save-status` is swapped. Never swap the form there, or focus and typed text are lost. The title is first on the page in its own form (`node/_title.peb`, posting `only=` so nothing else changes); the main form doesn't post a title, and the save keeps the stored one.

## Architecture

**Game modules are data, not code.** `modules/<id>/module.json` declares node types, edge types and props JSON Schemas; `GameModules.load` (`modules/GameModules.kt`) resolves each module's `extends` chain (base first, self last) into a `ResolvedModule` (`modules/ResolvedModule.kt`, the queries everything else uses). The JSON shape is in `modules/ModuleDef.kt`; `modules/ModuleChecks.kt` validates every setting at startup, so add a check there with each new setting. `patchTypes` entries are laid over the type as JSON, so every `NodeTypeDef` setting is patchable without extra code. Key behaviours that span the app:
- Node types inherit via `extends` (`heart.landmark` → `core.place`). `isA` walks this chain and is used everywhere instead of type equality: link rules, props schema lookup, print template lookup, and fact detection (`isA(type, "core.fact")`).
- `hide` removes inherited types from "New…" menus only; they still resolve.
- Edge types define `from`/`to` type lists plus `label`/`inverseLabel`. `linkOptions` exposes each edge in both directions (OUT uses `label`, IN uses `inverseLabel`), so "located_at" on a landmark appears as "has here".
- Props schemas drive both validation (networknt, draft 7) and form generation (`PropsForm.kt`; widget mapping is in README).

Prefer adding game-specific behaviour to module JSON/templates over adding Kotlin special cases. Dice-rated props store the die itself (`d4`…`d12`) as the enum value; any name for a die (e.g. "Professional") goes in `x-enumLabels` ("d8 Professional").

**Storage** (`db/migration/V1__init.sql`): a generic graph. `nodes` hold `type`, a per-campaign short `code` (prefix from the node type + next number, allocated under a Postgres advisory lock in `GraphService.createNode`), `props` JSONB and `visibility` (`gm`/`players`/`revealed`); `edges` link nodes with a `type` string. jOOQ is used only as a plain-SQL executor (no codegen); queries are hand-written strings in `CampaignService`/`GraphService`. Schema changes go in new Flyway files `V<n>__*.sql`, run automatically at startup.

**Ownership scoping:** every campaign has an `owner_id`; every graph query takes a `campaignId` that the caller must first resolve via `CampaignService.get(user, id)` (see `call.game()` in `web/Routes.kt`, which also gives the campaign's module). Keep this invariant for new queries. `AUTH_MODE=none` resolves every request to a single `local` user (`auth/Auth.kt`); `session` mode is not built yet.

**Print pipeline** (`print/PrintService.kt`): `model()` builds the template model and does all audience filtering (`core/Audience.kt`) *before* any template runs, so a template can't leak GM-only nodes onto a player handout. Visibility and player handouts are intentionally hidden from the UI for now (no form field, no handout link) but kept in the data model and backend; the save route keeps the stored visibility when none is posted. Linked fact nodes are split out into `facts`; other links are grouped by their directional edge label. The template is the type's `printTemplate` (inherited via `extends`) or `print/generic.peb`; HTML is passed through jsoup to a W3C DOM and rendered by openhtmltopdf.

**Map** (`map/`): `MapService.view` builds the campaign map from module `mapShape`/`mapDepth` settings (linked above the campaign page's title, not in the top bar); `MapService.nodeView` builds a node's own map from its type's `map` setting (a MÖRK BORG dungeon's rooms, cropped to them; linked next to Print on its page, and `nodeMap` in its print model). `MapLayout` is a deterministic, server-side force layout. It runs on the server so the same SVG (`templates/map/_svg.peb`) serves the screen page and the PDF; openhtmltopdf renders inline SVG through Batik.

**Session packet** (`PrintService.packet`, `print/Packet.kt`): print models are typed (`PrintModel`, passed to templates via `asMap()`); when a node's type has a module `packet` setting (Heart: `core.session`), its print model also gets `map`, `views` (built by `views/ModuleViews.kt`, the same code as the `/v/{view}` pages) and `sections` (print models of the selected nodes and the `between` ones). Selection is the session's `checklists` (links via `features`). The Heart template is `modules/heart/print/session.peb`, which reuses `_place.peb` and `_inspiration.peb`. Multi-column text (`packet.columns`) is done by imposition (`print/Imposition.kt`): the template's `columns` part is rendered once per section (variable `only`, the template then leaves out that section's h2) as one column on narrow pages, and PDFBox places those pages side by side on landscape sheets, each section from a new column, drawing every section's heading in a band across its columns on each sheet (so a continued section is headed again). Don't switch to CSS `column-count`: openhtmltopdf 1.0.10 squeezes blocks to one word per line and moves nested first children into other columns.

**Debrief** (module `debrief`, `web/Debrief.kt`, `NodePage.debrief`, `node/_debrief.peb`): a session page lists its packet's nodes (`Packet.select`, the same selection as the print) with some fields, link slots and linked nodes editable in place. Every control posts to the *other* node's existing routes: a node save with `only=<field>` (repeatable) merges just those props over the stored ones (`PropsForm.parse`'s `only`/`stored`), `clear=1` empties them (Done); slot and quick-create posts take `quiet=1`, which answers with nothing instead of swapping the session page's parts with the other node's. `refresh=1` sends the `debrief-changed` event that reloads the section from `/n/{id}/debrief`.

**Notes** (`core/Notes.kt`): a node's `body` and its long-text props ("textarea", "list") are stored as typed and rendered by `Notes.html` (`Notes.inline` for fact text and print summaries) into escaped HTML with paragraphs, `- ` lists, `**bold**`, `*italic*` and `[[mentions]]`, which `Mentions` resolves by code, title or display title (any case). Templates print it with `| raw` (`notes` and `fieldViews` in the node page model, `notes`, `PropLine.html`/`items` and summaries in print models), so never put user text in there unescaped. Mentions aren't edges: "Mentioned in" is a query (`GraphService.mentioning`), and a rename rewrites `[[old]]` in all notes (`renameMentions`). On the page, every long text uses `node/_long_text.peb` (formatted view, then a textarea on click); its `#view-<key>` is swapped out-of-band after a full-form autosave; the edit toggle and the `[[` picker (`/n/{id}/mentions`) are in `static/app.js`.

**Random tables** (`core/RandomTableService.kt`, migration V3) are campaign data the user types, not module data. Fields are keyed `"<type id>:<field>"` (`title` or a prop name); a field can have several tables (`node/_random.peb` then shows a menu). All rolls happen on the server in `RandomTables.roll`: new node titles (`Deps.newTitle` in `web/Routes.kt`) and the Random buttons, which fetch `/tables/{id}/roll`. `static/app.js` then fills the field and fires `change`, so autosave stores it. Don't duplicate the roll logic in JS.

**Templates** (`templates/Templates.kt`): one Pebble engine (auto-escaping on) for screen and print. Names resolve against the classpath (`src/main/resources/templates/`) first, then the modules directory, so module templates are referenced like `heart/print/landmark.peb`. Screen UI is server-rendered with htmx partials (`templates/node/_*.peb`); htmx is vendored in `static/`.

**Wiring:** `App.kt` builds everything by hand (no DI) and passes a `Deps` bag to `Application.routes`. `Deps` also holds the request helpers (`call.game()`, `call.html()`, `call.redirect()`, `call.linksChanged()`), and the route files (`web/CampaignRoutes.kt`, `NodeRoutes.kt`, `TableRoutes.kt`) run inside `with(d)` so they read as plain calls. Keep handlers thin: node-page view models are in `web/NodePage.kt`, link rules (`linkLimits`, slot replacement) in `modules/Linker.kt`, campaign-home tables in `web/Overview.kt`. Domain errors (`core/Errors.kt`: `NotFound`, `BadRequest`, `HiddenFromAudience`; plus `auth`'s `NotAuthenticated`) are exceptions mapped to HTTP status in `StatusPages`.

**Package layers:** `core` (graph, campaigns, random tables; depends on nothing else here) ← `modules` ← `views` / `map` / `print` / `templates` ← `web` ← `App`. Pages and prints that walk the whole graph load it once with `GraphService.snapshot()` (`core/CampaignGraph`, pure) and follow links with `linkedNodes`; a node's `context` parent is `ResolvedModule.parent(graph, node)`.

## Known gaps

From README: `AUTH_MODE=session`, player handouts (hidden from the UI), embedded TTF for PDF symbols beyond Latin-1 (e.g. →), Unit tests cover module loading, map layout, campaign-page tables, packet selection, module views, random tables, the debrief selection and notes rendering. Routes, SQL and templates have no automated tests: check them by running the app against a throwaway campaign (e.g. `PORT=8099 ./gradlew run`) and deleting it afterwards.
