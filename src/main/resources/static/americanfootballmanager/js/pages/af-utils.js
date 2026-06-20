function cmFormatGoalDiff(d) { if (d == null) return '0'; return d > 0 ? '+' + d : String(d); }

function cmFormatDate(d) { if (!d) return ''; const dt = new Date(d); return dt.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }); }

function cmEscapeHtml(s) { if (s == null) return ''; const d = document.createElement('div'); d.textContent = String(s); return d.innerHTML; }

function cmBuildEmptyState(msg) { return '<div style="text-align:center;padding:40px;color:#99a6bb;font-size:0.9rem;">' + (msg || 'No data') + '</div>'; }

function cmRatingColor(val, max) { if (val == null) return '#99a6bb'; const pct = Math.min(val / max, 1); if (pct >= 0.8) return '#58a612'; if (pct >= 0.6) return '#8BC34A'; if (pct >= 0.4) return '#f5a623'; return '#e74c3c'; }

window.cmFormatGoalDiff = cmFormatGoalDiff;
window.cmFormatDate = cmFormatDate;
window.cmEscapeHtml = cmEscapeHtml;
window.cmBuildEmptyState = cmBuildEmptyState;
window.cmRatingColor = cmRatingColor;
