// Toggle accordion
function toggleAccordion(header) {
    const accordion = header.parentElement;

    document.querySelectorAll('.accordion').forEach(acc => {
        if(acc !== accordion) acc.classList.remove('open');
    });

    accordion.classList.toggle('open');
}

// Funkcija za zatvaranje sidebar-a
function closeSidebar(sidebarId) {
    document.getElementById(sidebarId).classList.remove('active');
}

// Generalna funkcija za klik na link unutar sidebar-a
function handleSidebarLinkClick(e, sidebarId) {
    e.stopPropagation();

    if (!e.target.classList.contains('accordion-header')) {
        closeAllAccordions();
    }

    // Zatvori sidebar ako je desktop
    if(window.innerWidth > 768) {
        closeSidebar(sidebarId);
    }
}

// Dodaj event listener za SVAKI sidebar
const sidebars = ['clubSidebar', 'matchesSidebar', 'competitionsSidebar', 'communitySidebar', 'statsSidebar'];

sidebars.forEach(id => {
    const sidebar = document.getElementById(id);

    if (sidebar) {
        // Za accordion-content div-ove
        sidebar.querySelectorAll(".accordion-content > div").forEach(div => {
            div.addEventListener('click', async e => {
                handleSidebarLinkClick(e, id);
                const match = div.getAttribute('onclick')?.match(/loadPage\('(\w+)'\)/);
                if(match) await loadPage(match[1]);
            });
        });

        // Za direktne div-ove van accordion-a
        sidebar.querySelectorAll(".sidebar-content > div:not(.accordion)").forEach(div => {
            div.addEventListener('click', async e => {
                handleSidebarLinkClick(e, id);
                const match = div.getAttribute('onclick')?.match(/loadPage\('(\w+)'\)/);
                if(match) await loadPage(match[1]);
            });
        });

        // Za accordion-header
        sidebar.querySelectorAll(".accordion-header").forEach(header => {
            header.addEventListener('click', e => {
                e.stopPropagation();
                toggleAccordion(header);
            });
        });
    }
});

function closeAllAccordions() {
    document.querySelectorAll('.accordion').forEach(acc => acc.classList.remove('open'));
}

// Mobile menu (ostaje isto)
function toggleMobileMenu() {
    const sidebar = document.getElementById('mobileSidebar');
    const overlay = document.getElementById('mobileOverlay');

    sidebar.classList.toggle('active');
    overlay.classList.toggle('active');
}

function closeMobileMenu() {
    document.getElementById('mobileSidebar').classList.remove('active');
    document.getElementById('mobileOverlay').classList.remove('active');
}