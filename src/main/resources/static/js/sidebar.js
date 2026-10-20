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

// There is deliberately no link binding here any more.
//
// The desktop #clubSidebar was removed: `.sidebar` is fixed at left:-260px and nothing ever
// applied `.active` to it, so the whole Club tree rendered off-screen and desktop navigation is
// now the top bar plus the in-page action strip from buildClubActionsHtml.
//
// What used to be bound is worth remembering, because it is the reason nothing is:
//  - Every .accordion-header in #mobileSidebar carries an inline `onclick="toggleMobileAccordion(this)"`,
//    and toggleAccordion is not idempotent - it reads the open state and writes the opposite. Binding it
//    a second time made every header click toggle twice - open, then closed again in the same tick - so
//    the panels were inert. `max-height: 0px` is what a working accordion that has just closed looks
//    like, which is why this survived as long as it did: from the outside, a collapsed panel and a dead
//    panel are the same picture.
//  - The entries carried inline `onclick="loadPage(...)"` as well, so a JS listener ran loadPage a
//    second time and every navigation was fired twice.
// The inline handler is the single path for both, so it stays the only one.

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
window.disableBodyScroll = disableBodyScroll;
window.enableBodyScroll = enableBodyScroll;
