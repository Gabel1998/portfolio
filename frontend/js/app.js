import I18n from './modules/i18n.js';
import Nav from './views/nav.js';
import Hero from './views/hero.js';

const App = (() => {

    async function init() {
        await I18n.load(I18n.getLang());

        const app = document.getElementById('app');

        app.appendChild(Nav.render());
        app.appendChild(Nav.renderOverlay());
        app.appendChild(Hero.render());

        Nav.init();

        // Re-render text on language change
        document.addEventListener('langchange', () => {
            Nav.update();
        });
    }

    return { init };
})();

document.addEventListener('DOMContentLoaded', () => App.init());
