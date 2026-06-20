document.addEventListener("DOMContentLoaded", () => {
    loadDashboard();

    const sidebar = document.getElementById("clubSidebar");

    // --- Desktop sidebar klikovi ---
    sidebar.querySelectorAll(".accordion-content > div, .sidebar-content > div:not(.accordion)").forEach(d => {
        d.addEventListener("click", async (e) => {
            e.stopPropagation(); // ne propagira do document click

            const onclickAttr = d.getAttribute("onclick");
            if(!onclickAttr) return;

            const match = onclickAttr.match(/loadPage\('(\w+)'\)/);
            if(!match) return;

            const page = match[1];
            console.log("Desktop sidebar clicked:", page);

            await loadPage(page);

            // Zatvori sidebar samo ako je desktop (nije mobilni)
            if(window.innerWidth > 768) {
                sidebar.classList.remove("active");
            }
        });
    });

    // --- Accordion header ---
    sidebar.querySelectorAll(".accordion-header").forEach(header => {
        header.addEventListener("click", (e) => {
            e.stopPropagation();
            toggleAccordion(header);
        });
    });
});
