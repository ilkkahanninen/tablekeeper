// Textareas grow to show all their text: when the page loads, while typing, when text is filled in by script
// (Random, the [[ picker; those fire "change") and when htmx swaps new ones in. Hidden ones can't be measured.
const grow = (t) => {
  if (t.hidden || t.offsetParent === null) return;
  const y = window.scrollY;   // collapsing it for a moment mustn't make the page jump
  t.style.height = "auto";   // back to the rows= height, so it can also shrink
  t.style.height = `${t.scrollHeight + t.offsetHeight - t.clientHeight}px`;   // content + borders
  window.scrollTo(window.scrollX, y);
};
const growAll = (root) => root.querySelectorAll("textarea").forEach(grow);
for (const type of ["input", "change"]) document.addEventListener(type, (e) => { if (e.target instanceof HTMLTextAreaElement) grow(e.target); });
document.addEventListener("htmx:afterSettle", (e) => growAll(e.detail.elt));
let resizeTimer;
window.addEventListener("resize", () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(() => growAll(document), 100); });
growAll(document);   // app.js is deferred: the page is parsed by now

// Show/hide form fields declared with "x-hideIf" / "x-showIf" as the fields they depend on change.
// The server renders the initial state and drops hidden fields on save; this only keeps the form in step.
document.addEventListener("change", (e) => {
  const form = e.target.closest("form.node-form");
  if (!form) return;
  // Does any field named in the condition currently have one of its listed values?
  const matches = (condition) => Object.entries(condition).some(([field, values]) =>
    [...form.querySelectorAll(`[name="prop.${field}"]`)]
      .filter((i) => i.type !== "checkbox" || i.checked)
      .some((i) => values.includes(i.value)));
  form.querySelectorAll("[data-conditions]").forEach((el) => {
    const { hideIf, showIf } = JSON.parse(el.dataset.conditions);
    el.hidden = matches(hideIf) || (Object.keys(showIf).length > 0 && !matches(showIf));
  });
});

// Campaign home filter: hide rows not matching the typed text, and sections left empty.
document.addEventListener("input", (e) => {
  if (!e.target.matches(".overview-filter")) return;
  const q = e.target.value.trim().toLowerCase();
  document.querySelectorAll(".overview-section").forEach((section) => {
    let shown = 0;
    section.querySelectorAll(".overview-row").forEach((row) => {
      row.hidden = q !== "" && !row.textContent.toLowerCase().includes(q);
      if (!row.hidden) shown++;
    });
    section.hidden = shown === 0;
  });
});

// Autosave: flash the field that was just stored (an empty status means no errors).
document.addEventListener("htmx:afterRequest", (e) => {
  const d = e.detail;
  if (d.elt.matches("#node-form, .title-form") && d.successful && d.xhr.responseText.trim() === "") {
    const field = d.requestConfig?.triggeringEvent?.target;
    if (field instanceof HTMLElement && field !== d.elt) {
      field.classList.add("just-saved");
      setTimeout(() => field.classList.remove("just-saved"), 700);
    }
  }
});

// "Create without opening" in the Link box: clear the search, ready for the next one. On beforeSwap, not afterRequest:
// the response replaces the create row (out-of-band), so the button is gone and its later events never reach document.
document.addEventListener("htmx:beforeSwap", (e) => {
  if (!e.detail.requestConfig?.elt?.matches("[data-clear-search]") || e.detail.xhr.status >= 400) return;   // elt = the button
  const q = document.getElementById("link-q");
  if (q) { q.value = ""; q.dispatchEvent(new Event("input", { bubbles: true })); }
});

// Random table button: fill its field with a roll from the server (a different value when possible); the field's change autosaves.
// On a "list" field (data-append, e.g. a delve's events) the roll is added as a new item, after a blank line.
// With several tables the button is a menu (details); picking a table rolls on it and closes the menu.
document.addEventListener("click", (e) => {
  // Clicking outside an open menu closes it
  document.querySelectorAll("details.random-menu[open]").forEach((menu) => { if (!menu.contains(e.target)) menu.open = false; });
  const button = e.target.closest("button.random");
  if (!button) return;
  const field = button.closest("form").querySelector(`[name="${button.dataset.for}"]`);
  const menu = button.closest("details.random-menu");
  if (menu) menu.open = false;
  // The roll (incl. mixing lines) happens on the server, the same code that names new nodes
  fetch(`${button.dataset.roll}?avoid=${encodeURIComponent(field.value)}`)
    .then((r) => (r.ok ? r.text() : Promise.reject(r.status)))
    .then((value) => {
      field.value = "append" in button.dataset && field.value.trim() !== "" ? `${field.value.trimEnd()}\n\n${value}` : value;
      field.dispatchEvent(new Event("change", { bubbles: true }));
    });
});

// Debrief: controls save to other nodes with no swap, so show a refused change (e.g. a full link limit) in its status.
document.addEventListener("htmx:responseError", (e) => {
  const status = e.detail.elt.closest("#debrief")?.querySelector("#debrief-status");
  if (!status) return;
  const item = document.createElement("li");
  item.textContent = e.detail.xhr.responseText;
  const list = document.createElement("ul");
  list.className = "errors";
  list.append(item);
  status.replaceChildren(list);
});

// Notes: the formatted view until clicked (links still work), then the text to edit; leaving non-empty text shows the
// view again, which the autosave refreshes (out-of-band #notes-view).
document.addEventListener("click", (e) => {
  const view = e.target.closest(".notes-view");
  if (!view || e.target.closest("a")) return;
  const input = view.closest(".notes-field").querySelector("textarea");
  view.hidden = true;
  input.hidden = false;
  grow(input);   // hidden until now, so it couldn't be measured
  input.focus();
});
document.addEventListener("focusout", (e) => {
  if (!e.target.matches(".notes-field textarea")) return;
  const field = e.target.closest(".notes-field");
  field.querySelector(".mention-menu").hidden = true;
  if (e.target.value.trim() !== "") {
    e.target.hidden = true;
    field.querySelector(".notes-view").hidden = false;
  }
});

// Typing [[ in notes offers matching nodes (found on the server); picking one inserts [[Title]].
const mentionQuery = (input) => input.value.slice(0, input.selectionStart).match(/\[\[([^[\]\n]{0,40})$/)?.[1] ?? null;
const mentionMenu = (input) => input.closest(".notes-field").querySelector(".mention-menu");
let mentionTimer;
document.addEventListener("input", (e) => {
  const input = e.target;
  if (!input.matches("textarea[data-mentions]")) return;
  const menu = mentionMenu(input);
  const q = mentionQuery(input);
  clearTimeout(mentionTimer);
  if (q === null) { menu.hidden = true; return; }
  mentionTimer = setTimeout(() => {
    fetch(`${input.dataset.mentions}?q=${encodeURIComponent(q)}`).then((r) => r.text()).then((html) => {
      menu.innerHTML = html;
      menu.querySelector(".mention-choice")?.classList.add("active");
      menu.hidden = menu.children.length === 0;
    });
  }, 120);
});
function insertMention(input, title) {
  const before = input.value.slice(0, input.selectionStart).replace(/\[\[[^[\]\n]*$/, `[[${title}]]`);
  const after = input.value.slice(input.selectionStart).replace(/^\]\]/, "");
  input.value = before + after;
  input.selectionStart = input.selectionEnd = before.length;
  mentionMenu(input).hidden = true;
  input.dispatchEvent(new Event("change", { bubbles: true }));   // autosave
}
document.addEventListener("keydown", (e) => {
  if (!e.target.matches("textarea[data-mentions]")) return;
  const menu = mentionMenu(e.target);
  if (menu.hidden) return;
  const choices = [...menu.querySelectorAll(".mention-choice")];
  const active = menu.querySelector(".mention-choice.active");
  if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    const step = e.key === "ArrowDown" ? 1 : -1;
    const next = choices[(choices.indexOf(active) + step + choices.length) % choices.length];
    active?.classList.remove("active");
    next.classList.add("active");
    next.scrollIntoView({ block: "nearest" });
  } else if ((e.key === "Enter" || e.key === "Tab") && active) {
    insertMention(e.target, active.dataset.title);
  } else if (e.key === "Escape") {
    menu.hidden = true;
  } else return;
  e.preventDefault();
});
// mousedown, not click: the textarea keeps focus (and stays in edit mode)
document.addEventListener("mousedown", (e) => {
  const choice = e.target.closest(".mention-choice");
  if (!choice) return;
  e.preventDefault();
  insertMention(choice.closest(".notes-field").querySelector("textarea"), choice.dataset.title);
});
