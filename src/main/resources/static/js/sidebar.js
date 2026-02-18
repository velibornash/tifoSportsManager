// Toggle accordion
function toggleAccordion(header) {
    const accordion = header.parentElement;

    document.querySelectorAll('.accordion').forEach(acc => {
        if(acc !== accordion) acc.classList.remove('open');
    });

    accordion.classList.toggle('open');
}

// Klik na link unutar sidebar-a
function handleSidebarLinkClick(e) {
    e.stopPropagation();

    // Zatvori sve accordion-e osim onog u kojem je klik
    // (ovo može da se preskoči ako je klik na header)
    if (!e.target.classList.contains('accordion-header')) {
        closeAllAccordions();
    }

    // Zatvori sidebar ako je mobilni
    if(window.innerWidth <= 768) closeMobileMenu();
}

// Desktop accordion link klik
document.querySelectorAll('#clubSidebar .accordion-content > div').forEach(div => {
    div.addEventListener('click', async (e) => {
        e.stopPropagation();

        const match = div.getAttribute('onclick')?.match(/loadPage\('(\w+)'\)/);
        if(!match) return;

        await loadPage(match[1]);

        // Zatvori desktop sidebar kad se izabere podmeni
        if(window.innerWidth > 768) {
            document.getElementById('clubSidebar').classList.remove('active');
        }
    });
});

// Accordion header klik
document.querySelectorAll('#clubSidebar .accordion-header').forEach(header => {
    header.addEventListener('click', e => {
        e.stopPropagation();
        toggleAccordion(header);
    });
});

// Klik na ostatak sidebar linkova (npr. Training, Profile)
document.querySelectorAll('#clubSidebar > .sidebar-content > div:not(.accordion)').forEach(div => {
    div.addEventListener('click', async e => {
        e.stopPropagation();

        const match = div.getAttribute('onclick')?.match(/loadPage\('(\w+)'\)/);
        if(!match) return;

        await loadPage(match[1]);

        if(window.innerWidth > 768) {
            document.getElementById('clubSidebar').classList.remove('active');
        }
    });
});
function closeAllAccordions() {
    document.querySelectorAll('.accordion').forEach(acc => acc.classList.remove('open'));
}
