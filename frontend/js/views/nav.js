import I18n from '../modules/i18n.js';

const Nav = (() => {
    const sections = ['about', 'skills', 'projects', 'experience', 'contact'];

    function render() {
        const nav = document.createElement('nav');
        nav.className = 'nav nav--transparent';
        nav.id = 'nav';

        nav.innerHTML = `
            <div class="nav__inner">
                <a href="#hero" class="nav__logo">AG</a>
                <ul class="nav__links">
                    ${sections.map(s => `
                        <li><a href="#${s}" class="nav__link" data-section="${s}" data-i18n="nav.${s}">${I18n.t(`nav.${s}`)}</a></li>
                    `).join('')}
                    <li><button class="nav__lang" id="lang-toggle">${I18n.getLang() === 'en' ? 'DA' : 'EN'}</button></li>
                </ul>
                <button class="nav__hamburger" id="hamburger" aria-label="Menu">
                    <span></span><span></span><span></span>
                </button>
            </div>
        `;

        return nav;
    }

    function renderOverlay() {
        const overlay = document.createElement('div');
        overlay.className = 'nav__overlay';
        overlay.id = 'nav-overlay';

        overlay.innerHTML = `
            ${sections.map(s => `
                <a href="#${s}" class="nav__link" data-section="${s}" data-i18n="nav.${s}">${I18n.t(`nav.${s}`)}</a>
            `).join('')}
            <button class="nav__lang" id="lang-toggle-mobile">${I18n.getLang() === 'en' ? 'DA' : 'EN'}</button>
        `;

        return overlay;
    }

    function init() {
        const nav = document.getElementById('nav');
        const hamburger = document.getElementById('hamburger');
        const overlay = document.getElementById('nav-overlay');

        // Scroll: transparent → solid
        window.addEventListener('scroll', () => {
            if (window.scrollY > 50) {
                nav.classList.replace('nav--transparent', 'nav--solid');
            } else {
                nav.classList.replace('nav--solid', 'nav--transparent');
            }
        });

        // Active section highlighting
        const observer = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    document.querySelectorAll('.nav__link').forEach(link => {
                        link.classList.toggle('nav__link--active', link.dataset.section === entry.target.id);
                    });
                }
            });
        }, { rootMargin: '-40% 0px -60% 0px' });

        sections.forEach(id => {
            const el = document.getElementById(id);
            if (el) observer.observe(el);
        });

        // Hamburger toggle
        hamburger?.addEventListener('click', () => {
            overlay.classList.toggle('nav__overlay--open');
        });

        // Close overlay on link click
        overlay?.querySelectorAll('.nav__link').forEach(link => {
            link.addEventListener('click', () => {
                overlay.classList.remove('nav__overlay--open');
            });
        });

        // Language toggle
        document.querySelectorAll('#lang-toggle, #lang-toggle-mobile').forEach(btn => {
            btn.addEventListener('click', () => {
                const next = I18n.getLang() === 'en' ? 'da' : 'en';
                I18n.load(next);
            });
        });
    }

    function update() {
        document.querySelectorAll('[data-i18n]').forEach(el => {
            el.textContent = I18n.t(el.dataset.i18n);
        });

        document.querySelectorAll('#lang-toggle, #lang-toggle-mobile').forEach(btn => {
            btn.textContent = I18n.getLang() === 'en' ? 'DA' : 'EN';
        });
    }

    return { render, renderOverlay, init, update };
})();

export default Nav;
