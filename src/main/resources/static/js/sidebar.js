// sidebar.js
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
function closeSidebar(sidebarId) {document.getElementById(sidebarId)?.classList.remove('active');}
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
const sidebars = ['clubSidebar'];
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

    // Accordion headers are deliberately NOT bound here.
    //
    // Every .accordion-header in both sidebars already carries an inline `onclick="toggleAccordion(this)"`
    // (or toggleMobileAccordion), and toggleAccordion is not idempotent: it reads the open state and
    // then writes the opposite. Binding it a second time meant every desktop header click toggled
    // twice - open, then closed again in the same tick - so all three groups were inert. `after=0px`
    // is what a working accordion that has just closed looks like, which is why this survived as long
    // as it did: from the outside, a collapsed panel and a dead panel are the same picture.
    //
    // The inline handler is kept as the single path because it is the only one that covers
    // #mobileSidebar, which is not in the `sidebars` list below.
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
function toggleMobileMenu() {
    const sidebar = document.getElementById('mobileSidebar');
    const overlay = document.getElementById('mobileOverlay');

    // Proveri trenutno stanje
    const isOpen = sidebar.classList.contains('active');

    if (isOpen) {
        // Ako je otvoren → zatvori
        sidebar.classList.remove('active');
        overlay.classList.remove('active');
        enableBodyScroll();
    } else {
        // Ako je zatvoren → otvori
        sidebar.classList.add('active');
        overlay.classList.add('active');
        disableBodyScroll();
    }
}
function closeMobileMenu() {
    document.getElementById('mobileSidebar').classList.remove('active');
    document.getElementById('mobileOverlay').classList.remove('active');
    enableBodyScroll();
}


window.toggleAccordion = toggleAccordion;
window.toggleMobileAccordion = toggleMobileAccordion;
window.closeMobileMenu = closeMobileMenu;
window.toggleMobileMenu = toggleMobileMenu;
window.closeSidebar = closeSidebar;
window.handleSidebarLinkClick = handleSidebarLinkClick;
window.disableBodyScroll = disableBodyScroll;
window.enableBodyScroll = enableBodyScroll;
