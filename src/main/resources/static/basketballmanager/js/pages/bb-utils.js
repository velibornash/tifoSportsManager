// bb-utils.js — Shared sort utilities and helpers for basketball manager pages

// ─── Live stats sort state persistence ───
let _bbLiveSortState = {
    home: { colIdx: 1, dir: 'desc' },
    away: { colIdx: 1, dir: 'desc' }
};

function bbSaveSortState(tableId, colIdx, dir) {
    const side = tableId.includes('home') ? 'home' : 'away';
    _bbLiveSortState[side] = { colIdx, dir };
}

function bbGetSortState(tableId) {
    const side = tableId.includes('home') ? 'home' : 'away';
    return _bbLiveSortState[side] || { colIdx: 1, dir: 'desc' };
}

function bbReapplySort(tableEl) {
    if (!tableEl) return;
    const tableId = tableEl.id;
    const state = bbGetSortState(tableId);
    bbSortTableByColumn(tableEl, state.colIdx, state.dir);
}

function bbMakeSortable(tableEl) {
    if (!tableEl) return;
    const thead = tableEl.querySelector('thead');
    if (!thead) return;
    const ths = thead.querySelectorAll('th');
    ths.forEach(th => {
        if (!th.hasAttribute('data-sort')) return;
        th.style.cursor = 'pointer';
        th.addEventListener('click', () => {
            const colIdx = Array.from(ths).indexOf(th);
            const type = th.getAttribute('data-sort') || 'string';
            const tbody = tableEl.querySelector('tbody');
            if (!tbody) return;
            const rows = Array.from(tbody.querySelectorAll('tr'));
            const totalRow = rows.find(r => r.cells[0]?.textContent.trim() === 'TOTAL');
            const sortableRows = totalRow ? rows.filter(r => r !== totalRow) : rows;
            const currentDir = th.getAttribute('data-dir') || '';
            const dir = currentDir === 'asc' ? 'desc' : 'asc';

            ths.forEach(h => h.removeAttribute('data-dir'));
            th.setAttribute('data-dir', dir);
            ths.forEach(h => { h.innerHTML = h.innerHTML.replace(/ [▲▼]$/, ''); });
            th.innerHTML += dir === 'asc' ? ' ▲' : ' ▼';

            sortableRows.sort((a, b) => {
                const ca = a.cells[colIdx]?.textContent.trim() || '';
                const cb = b.cells[colIdx]?.textContent.trim() || '';
                let va, vb;
                if (type === 'number') {
                    va = parseFloat(ca) || 0;
                    vb = parseFloat(cb) || 0;
                } else if (type === 'pct') {
                    va = parseFloat(ca) || 0;
                    vb = parseFloat(cb) || 0;
                } else {
                    va = ca.toLowerCase();
                    vb = cb.toLowerCase();
                }
                return dir === 'asc'
                    ? (va > vb ? 1 : va < vb ? -1 : 0)
                    : (va < vb ? 1 : va > vb ? -1 : 0);
            });
            [...sortableRows, ...(totalRow ? [totalRow] : [])].forEach(r => tbody.appendChild(r));
            bbSaveSortState(tableEl.id, colIdx, dir);
        });
    });
}

function bbSortTableByColumn(tableEl, colIdx, dir = 'desc') {
    if (!tableEl) return;
    const thead = tableEl.querySelector('thead');
    if (!thead) return;
    const ths = thead.querySelectorAll('th');
    if (colIdx >= ths.length) return;
    const th = ths[colIdx];
    const type = th.getAttribute('data-sort') || 'string';
    const tbody = tableEl.querySelector('tbody');
    if (!tbody) return;
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const totalRow = rows.find(r => r.cells[0]?.textContent.trim() === 'TOTAL');
    const sortableRows = totalRow ? rows.filter(r => r !== totalRow) : rows;

    ths.forEach(h => h.removeAttribute('data-dir'));
    th.setAttribute('data-dir', dir);
    ths.forEach(h => { h.innerHTML = h.innerHTML.replace(/ [▲▼]$/, ''); });
    th.innerHTML += dir === 'asc' ? ' ▲' : ' ▼';

    sortableRows.sort((a, b) => {
        const ca = a.cells[colIdx]?.textContent.trim() || '';
        const cb = b.cells[colIdx]?.textContent.trim() || '';
        let va, vb;
        if (type === 'number') {
            va = parseFloat(ca) || 0;
            vb = parseFloat(cb) || 0;
        } else if (type === 'pct') {
            va = parseFloat(ca) || 0;
            vb = parseFloat(cb) || 0;
        } else {
            va = ca.toLowerCase();
            vb = cb.toLowerCase();
        }
        return dir === 'asc'
            ? (va > vb ? 1 : va < vb ? -1 : 0)
            : (va < vb ? 1 : va > vb ? -1 : 0);
    });
    [...sortableRows, ...(totalRow ? [totalRow] : [])].forEach(r => tbody.appendChild(r));
}

function bbSwitchMatchTab(tab, el) {
    document.querySelectorAll('.match-tab').forEach(t => t.style.display = 'none');
    document.querySelectorAll('.tab-btn').forEach(t => t.classList.remove('active'));
    document.getElementById('match-' + tab + '-tab').style.display = 'block';
    el.classList.add('active');
}

window.bbSaveSortState = bbSaveSortState;
window.bbGetSortState = bbGetSortState;
window.bbReapplySort = bbReapplySort;
window.bbMakeSortable = bbMakeSortable;
window.bbSortTableByColumn = bbSortTableByColumn;
window.bbSwitchMatchTab = bbSwitchMatchTab;
