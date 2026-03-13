import I18n from '../modules/i18n.js';

const Experience = (() => {

    function render() {
        const section = document.createElement('section');
        section.className = 'experience section reveal';
        section.id = 'experience';

        const items = I18n.t('experience.items');
        const timeline = Array.isArray(items) ? items.map(item => `
            <div class="timeline__item">
                <div class="timeline__dot"></div>
                <div class="timeline__period">${item.period}</div>
                <div class="timeline__title">${item.title}</div>
                <div class="timeline__org">${item.org}</div>
                <p class="timeline__desc">${item.description}</p>
            </div>
        `).join('') : '';

        section.innerHTML = `
            <div class="container">
                <h2 data-i18n="experience.heading">${I18n.t('experience.heading')}</h2>
                <div class="timeline">${timeline}</div>
            </div>
        `;

        return section;
    }

    return { render };
})();

export default Experience;
