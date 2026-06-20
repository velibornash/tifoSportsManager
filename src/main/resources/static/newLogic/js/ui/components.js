export function backButtonHtml(label = "Back", fallback = "dashboard", extraClass = "") {
    return `<button class="back-to-dashboard ${extraClass}" data-nav-back="${fallback}">${label}</button>`;
}

export function emptyStateHtml(title, message) {
    return `
    <div class="manager-card" style="text-align:center; padding:40px;">
        <h2>${title}</h2>
        <p style="color:#9aa0a6;">${message}</p>
    </div>`;
}
