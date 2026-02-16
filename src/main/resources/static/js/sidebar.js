function toggleSidebar(id) {

    document.querySelectorAll('.sidebar').forEach(sb => {
        if (sb.id !== id) {
            sb.classList.remove('active');
        }
    });

    const sidebar = document.getElementById(id);
    sidebar.classList.toggle('active');
}

function toggleAccordion(header) {

    const accordion = header.parentElement;

    document.querySelectorAll('.accordion').forEach(acc => {
        if (acc !== accordion) {
            acc.classList.remove('open');
        }
    });

    accordion.classList.toggle('open');
}
