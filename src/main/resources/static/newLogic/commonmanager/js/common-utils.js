function cmEscapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function cmFormatBudget(value) {
    const n = Number(value || 0);
    if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
    if (n >= 1000) return (n / 1000).toFixed(0) + 'K';
    return n.toLocaleString();
}

function cmFormatGoalDiff(value) {
    const n = Number(value || 0);
    return n > 0 ? '+' + n : String(n);
}

function cmFormatPct(value) {
    const n = Number(value || 0);
    return n.toFixed(1) + '%';
}

function cmBuildEmptyState(message, subtext) {
    return `
        <div class="empty-badge-wrap">
            <span class="empty-badge">${cmEscapeHtml(message || 'No data')}</span>
        </div>
        ${subtext ? `<p style="text-align:center;color:#99a6bb;font-size:0.85rem;">${cmEscapeHtml(subtext)}</p>` : ''}`;
}

function cmFormatDate(dateStr) {
    if (!dateStr) return 'TBD';
    try {
        const d = new Date(dateStr.includes('T') ? dateStr : dateStr.replace(' ', 'T'));
        if (isNaN(d.getTime())) return dateStr;
        return d.toLocaleDateString('sr-RS') + ' ' + d.toLocaleTimeString('sr-RS', { hour: '2-digit', minute: '2-digit' });
    } catch { return dateStr; }
}

function cmSeasonLabel(year) {
    const y = Number(year);
    if (!isFinite(y)) return 'Current season';
    return `${y}/${String((y + 1) % 100).padStart(2, '0')}`;
}

function cmSkillBar(value, max = 20) {
    const pct = Math.min(100, (value / max) * 100);
    const color = pct >= 80 ? '#6fcf97' : pct >= 50 ? '#f2c94c' : '#eb5757';
    return `<span class="skill-bar"><span class="skill-bar-fill" style="width:${pct}%;background:${color}"></span></span><span class="skill-value">${value}</span>`;
}

function cmRatingColor(value, max = 20) {
    const pct = (value / max) * 100;
    if (pct >= 80) return '#6fcf97';
    if (pct >= 60) return '#f2c94c';
    if (pct >= 40) return '#e67e22';
    return '#eb5757';
}

function cmSortTable(table, colIndex, numeric = false) {
    const tbody = table.querySelector('tbody');
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const dir = table.dataset.sortDir === 'asc' ? -1 : 1;
    rows.sort((a, b) => {
        const aVal = a.cells[colIndex]?.textContent?.trim() || '0';
        const bVal = b.cells[colIndex]?.textContent?.trim() || '0';
        if (numeric) return (parseFloat(aVal) - parseFloat(bVal)) * dir;
        return aVal.localeCompare(bVal) * dir;
    });
    rows.forEach(r => tbody.appendChild(r));
    table.dataset.sortDir = dir === 1 ? 'desc' : 'asc';
}

window.cmEscapeHtml = cmEscapeHtml;
window.cmFormatBudget = cmFormatBudget;
window.cmFormatGoalDiff = cmFormatGoalDiff;
window.cmFormatPct = cmFormatPct;
window.cmBuildEmptyState = cmBuildEmptyState;
window.cmFormatDate = cmFormatDate;
window.cmSeasonLabel = cmSeasonLabel;
window.cmSkillBar = cmSkillBar;
window.cmRatingColor = cmRatingColor;
window.cmSortTable = cmSortTable;
