import I18n from './modules/i18n.js';
import Reveal from './modules/reveal.js';
import Nav from './views/nav.js';
import Hero from './views/hero.js';
import About from './views/about.js';
import Skills from './views/skills.js';
import Projects from './views/projects.js';
import Experience from './views/experience.js';
import Contact from './views/contact.js';
import Footer from './views/footer.js';

const App = (() => {

    async function init() {
        await Promise.all([
            I18n.load(I18n.getLang()),
            Projects.loadProjects()
        ]);

        const app = document.getElementById('app');

        app.appendChild(Nav.render());
        app.appendChild(Nav.renderOverlay());
        app.appendChild(Hero.render());
        app.appendChild(About.render());
        app.appendChild(Skills.render());
        app.appendChild(Projects.render());
        app.appendChild(Experience.render());
        app.appendChild(Contact.render());
        app.appendChild(Footer.render());

        Nav.init();
        Reveal.init();

        document.addEventListener('langchange', () => {
            Nav.update();
            rebuildSections();
        });
    }

    function rebuildSections() {
        const app = document.getElementById('app');

        // Remove everything except nav and overlay (first 2 children)
        while (app.children.length > 2) {
            app.removeChild(app.lastChild);
        }

        app.appendChild(Hero.render());
        app.appendChild(About.render());
        app.appendChild(Skills.render());
        app.appendChild(Projects.render());
        app.appendChild(Experience.render());
        app.appendChild(Contact.render());
        app.appendChild(Footer.render());

        Reveal.init();
    }

    return { init };
})();

document.addEventListener('DOMContentLoaded', () => App.init());
