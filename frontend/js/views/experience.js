import I18n from '../modules/i18n.js';

const Experience = (() => {

    const visibleCount = 2;

    function renderItem(item) {
        return `
            <div class="timeline__item">
                <div class="timeline__dot"></div>
                <div class="timeline__period">${item.period}</div>
                <div class="timeline__title">${item.title}</div>
                <div class="timeline__org">${item.org}</div>
                <p class="timeline__desc">${item.description}</p>
            </div>
        `;
    }

    function render() {
        const section = document.createElement('section');
        section.className = 'experience section section--right reveal';
        section.id = 'experience';

        const items = I18n.t('experience.items');
        if (!Array.isArray(items)) return section;

        const visible = items.slice(0, visibleCount);
        const hidden = items.slice(visibleCount);

        section.innerHTML = `
            <div class="container">
                <h2 data-i18n="experience.heading">${I18n.t('experience.heading')}</h2>
                <div class="timeline">
                    ${visible.map(renderItem).join('')}
                    ${hidden.length ? `
                        <div class="timeline__extra" id="timeline-extra" style="display: none;">
                            ${hidden.map(renderItem).join('')}
                        </div>
                        <button class="timeline__toggle btn btn--outline" id="timeline-toggle" data-i18n="experience.showMore">
                            ${I18n.t('experience.showMore')}
                        </button>
                    ` : ''}
                </div>
            </div>
        `;

        const toggle = section.querySelector('#timeline-toggle');
        if (toggle) {
            toggle.addEventListener('click', () => {
                const extra = section.querySelector('#timeline-extra');
                const isHidden = extra.style.display === 'none';
                extra.style.display = isHidden ? 'block' : 'none';
                toggle.textContent = isHidden
                    ? I18n.t('experience.showLess')
                    : I18n.t('experience.showMore');
            });
        }

        return section;
    }

    return { render };
})();

export default Experience;
