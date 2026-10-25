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
  if (d.elt.id === "node-form" && d.successful && d.xhr.responseText.trim() === "") {
    const field = d.requestConfig?.triggeringEvent?.target;
    if (field instanceof HTMLElement && field !== d.elt) {
      field.classList.add("just-saved");
      setTimeout(() => field.classList.remove("just-saved"), 700);
    }
  }
  // "Create without opening" in the Link box: clear the search, ready for the next one
  if (d.elt.matches("[data-clear-search]") && d.successful) {
    const q = document.getElementById("link-q");
    if (q) { q.value = ""; q.dispatchEvent(new Event("input", { bubbles: true })); }
  }
});

// One line's first words and another line's last word: "Alys Pascoe" + "Beatrice Flint" -> "Alys Flint".
// A one-word line is used whole as the first part, and adds nothing as the last.
function mixWords(a, b) {
  const first = a.split(/\s+/), second = b.split(/\s+/);
  const head = first.length > 1 ? first.slice(0, -1).join(" ") : a;
  return second.length > 1 ? `${head} ${second[second.length - 1]}` : head;
}

// A random line, or with mix a combination of two (different) lines; avoids repeating `current` when it can.
function roll(items, mix, current) {
  const pick = () => items[Math.floor(Math.random() * items.length)];
  const once = () => {
    if (!mix || items.length < 2) return pick();
    const a = pick();
    let b = pick();
    while (b === a) b = pick();
    return mixWords(a, b);
  };
  let value = once();
  for (let i = 0; i < 10 && value === current && items.length > 1; i++) value = once();
  return value;
}

// Random table button: fill its field with a random item (a different one when possible); the field's change autosaves.
// With several tables the button is a menu (details); picking a table rolls on it and closes the menu.
document.addEventListener("click", (e) => {
  // Clicking outside an open menu closes it
  document.querySelectorAll("details.random-menu[open]").forEach((menu) => { if (!menu.contains(e.target)) menu.open = false; });
  const button = e.target.closest("button.random");
  if (!button) return;
  const field = button.closest("form").querySelector(`[name="${button.dataset.for}"]`);
  field.value = roll(JSON.parse(button.dataset.items), button.dataset.mix === "true", field.value);
  field.dispatchEvent(new Event("change", { bubbles: true }));
  const menu = button.closest("details.random-menu");
  if (menu) menu.open = false;
});
