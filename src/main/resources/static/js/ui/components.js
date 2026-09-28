export function backButtonHtml(label = "Back", fallback = "dashboard", extraClass = "") {
    return `<button class="back-to-dashboard ${extraClass}" data-nav-back="${fallback}">${label}</button>`;
}
