// Toggle accordion
// Univerzalna funkcija za sve accordione (desktop + mobilni)
function toggleAccordion(header) {
    const content = header.nextElementSibling;
    const isOpen = content.style.maxHeight && content.style.maxHeight !== '0px';

    // Zatvori sve ostale accordione u istom sidebar-u ili globalno
    document.querySelectorAll('.accordion-content').forEach(c => {
        if (c !== content) c.style.maxHeight = '0px';
    });

    // Otvori kliknuti
    if (!isOpen) {
        content.style.maxHeight = content.scrollHeight + 'px';
    } else {
        content.style.maxHeight = '0px';
    }
}

// Koristi istu funkciju i za mobilni (možeš preimenovati ili zadržati alias)
const toggleMobileAccordion = toggleAccordion;

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

// Blokiraj skrol glavnog sadržaja kad je sidebar otvoren
function disableBodyScroll() {
    document.body.style.overflow = 'hidden';
    document.documentElement.style.overflow = 'hidden';
}

function enableBodyScroll() {
    document.body.style.overflow = '';
    document.documentElement.style.overflow = '';
}

// Ažuriraj toggleMobileMenu da blokira skrol
function toggleMobileMenu() {
    const sidebar = document.getElementById('mobileSidebar');
    const overlay = document.getElementById('mobileOverlay');

    const isActive = sidebar.classList.toggle('active');
    overlay.classList.toggle('active');

    if (isActive) {
        disableBodyScroll();
    } else {
        enableBodyScroll();
    }
}

// Zatvaranje preko overlay-a
function closeMobileMenu() {
    document.getElementById('mobileSidebar').classList.remove('active');
    document.getElementById('mobileOverlay').classList.remove('active');
    enableBodyScroll();
}
