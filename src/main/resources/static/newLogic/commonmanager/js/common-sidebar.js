function cmToggleAccordion(header) {
    const content = header.nextElementSibling;
    const isOpen = content.style.maxHeight && content.style.maxHeight !== '0px';
    document.querySelectorAll('.accordion-content').forEach(c => {
        if (c !== content) c.style.maxHeight = '0px';
    });
    if (!isOpen) {
        content.style.maxHeight = content.scrollHeight + 'px';
    } else {
        content.style.maxHeight = '0px';
    }
}

function cmToggleMobileMenu() {
    const sidebar = document.getElementById('mobileSidebar');
    const overlay = document.getElementById('mobileOverlay');
    if (!sidebar) return;
    const isOpen = sidebar.classList.contains('active');
    if (isOpen) {
        sidebar.classList.remove('active');
        overlay?.classList.remove('active');
        document.body.style.overflow = '';
    } else {
        sidebar.classList.add('active');
        overlay?.classList.add('active');
        document.body.style.overflow = 'hidden';
    }
}

function cmCloseMobileMenu() {
    const sidebar = document.getElementById('mobileSidebar');
    const overlay = document.getElementById('mobileOverlay');
    sidebar?.classList.remove('active');
    overlay?.classList.remove('active');
    document.body.style.overflow = '';
}

function cmToggleSidebar(id) {
    const sb = document.getElementById(id);
    if (sb) sb.classList.toggle('active');
}

document.addEventListener('click', function(e) {
    const header = e.target.closest('.accordion-header');
    if (header) {
        e.stopPropagation();
        cmToggleAccordion(header);
    }
    const sidebarLink = e.target.closest('.sidebar-content > div:not(.accordion), .accordion-content > div');
    if (sidebarLink) {
        const onclick = sidebarLink.getAttribute('onclick');
        if (onclick) {
            const match = onclick.match(/loadPage\('([^']+)'\)/);
            if (match && window.innerWidth > 768) {
                cmCloseMobileMenu();
            }
        }
    }
});

window.cmToggleAccordion = cmToggleAccordion;
window.cmToggleMobileMenu = cmToggleMobileMenu;
window.cmCloseMobileMenu = cmCloseMobileMenu;
window.cmToggleSidebar = cmToggleSidebar;
