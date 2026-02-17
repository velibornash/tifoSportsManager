document.addEventListener("DOMContentLoaded", () => {
    loadDashboard();

    // --- Desktop sidebar klikovi ---
    const leafDivs = document.querySelectorAll("#clubSidebar .accordion-content > div, #clubSidebar > .sidebar-content > div:not(.accordion)");
    leafDivs.forEach(d => {
        d.addEventListener("click", async (e) => {
            e.stopPropagation(); // sprečava parent klikove
            const page = d.getAttribute("onclick")?.match(/loadPage\('(\w+)'\)/)?.[1];
            if(!page) return;

            console.log("Desktop sidebar clicked:", page);
            await loadPage(page);

            // Ako je First Team ili Juniors, možemo zatvoriti sidebar automatski
            document.getElementById("clubSidebar").classList.remove("active");
        });
    });
});
