import I18n from '../modules/i18n.js';
import Plasma from '../modules/plasma.js';

const Hero = (() => {

    function render() {
        const section = document.createElement('section');
        section.className = 'hero section';
        section.id = 'hero';

        section.innerHTML = `
            <div class="container hero__grid">
                <div class="hero__content">
                    <p class="hero__greeting" data-i18n="hero.greeting">${I18n.t('hero.greeting')}</p>
                    <h1 class="hero__name" data-i18n="hero.name">${I18n.t('hero.name')}</h1>
                    <p class="hero__title" data-i18n="hero.title">${I18n.t('hero.title')}</p>
                    <p class="hero__tagline" data-i18n="hero.tagline">${I18n.t('hero.tagline')}</p>
                    <div class="hero__cta">
                        <a href="https://github.com/gabel1998" target="_blank" rel="noopener" class="btn btn--primary" data-i18n="hero.cta.github">${I18n.t('hero.cta.github')}</a>
                        <a href="https://www.linkedin.com/in/andreas-søgaard-gabel-758991133" target="_blank" rel="noopener" class="btn btn--primary" data-i18n="hero.cta.linkedin">${I18n.t('hero.cta.linkedin')}</a>
                        <a href="/static/cv-andreas-gabel.pdf" download class="btn btn--outline" data-i18n="hero.cta.cv">${I18n.t('hero.cta.cv')}</a>
                    </div>
                </div>
                <div class="hero__plasma">
                    <canvas id="plasma-canvas"></canvas>
                </div>
            </div>
        `;

        requestAnimationFrame(() => {
            const canvas = section.querySelector('#plasma-canvas');
            if (canvas) Plasma.init(canvas);
        });

        return section;
    }

    return { render };
})();

export default Hero;
