document.addEventListener('DOMContentLoaded', () => {
    const demoBtns = document.querySelectorAll('#demoBtn');
    demoBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            window.location.href = '/newLogic/dashboard.html';
        });
    });
});
