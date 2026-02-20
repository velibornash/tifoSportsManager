// Toggle accordion
function toggleAccordion(header) {
    const accordion = header.parentElement;
    document.querySelectorAll('.accordion').forEach(acc => {
        if (acc !== accordion) acc.classList.remove('open');
    });
    accordion.classList.toggle('open');
}

// Zatvori sidebar
function closeSidebar(sidebarId) {
    document.getElementById(sidebarId)?.classList.remove('active');
}

// Generalni handler za klik na linkove unutar sidebar-a
function handleSidebarLinkClick(e, sidebarId) {
    e.stopPropagation();

    // Zatvori accordion-e osim trenutnog
    if (!e.target.classList.contains('accordion-header')) {
        document.querySelectorAll('.accordion').forEach(acc => acc.classList.remove('open'));
    }

    // Zatvori sidebar na desktop-u
    if (window.innerWidth > 768) {
        closeSidebar(sidebarId);
    }
}

// Dodaj listener-e za SVAKI sidebar
const sidebars = ['clubSidebar', 'matchesSidebar', 'competitionsSidebar', 'communitySidebar', 'statsSidebar'];

sidebars.forEach(id => {
    const sidebar = document.getElementById(id);
    if (!sidebar) return;

    // Accordion content linkovi
    sidebar.querySelectorAll(".accordion-content > div").forEach(div => {
        div.addEventListener('click', async e => {
            handleSidebarLinkClick(e, id);
            const onclick = div.getAttribute('onclick');
            if (onclick?.includes('loadPage')) {
                const page = onclick.match(/loadPage\('([^']+)'\)/)?.[1];
                if (page) await loadPage(page);
            }
        });
    });

    // Direktni linkovi van accordion-a
    sidebar.querySelectorAll(".sidebar-content > div:not(.accordion)").forEach(div => {
        div.addEventListener('click', async e => {
            handleSidebarLinkClick(e, id);
            const onclick = div.getAttribute('onclick');
            if (onclick?.includes('loadPage')) {
                const page = onclick.match(/loadPage\('([^']+)'\)/)?.[1];
                if (page) await loadPage(page);
            }
        });
    });

    // Accordion header
    sidebar.querySelectorAll(".accordion-header").forEach(header => {
        header.addEventListener('click', e => {
            e.stopPropagation();
            toggleAccordion(header);
        });
    });
});