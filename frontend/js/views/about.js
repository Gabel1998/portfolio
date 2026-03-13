import I18n from '../modules/i18n.js';

const About = (() => {

    function render() {
        const section = document.createElement('section');
        section.className = 'about section section--alt reveal';
        section.id = 'about';

        section.innerHTML = `
            <div class="container">
                <div class="about__inner">
                    <div class="about__image-wrapper">
                        <img src="/images/profile.jpg" alt="Andreas Gabel" class="about__image">
                    </div>
                    <div class="about__text">
                        <h2 data-i18n="about.heading">${I18n.t('about.heading')}</h2>
                        <p data-i18n="about.p1">${I18n.t('about.p1')}</p>
                        <p data-i18n="about.p2">${I18n.t('about.p2')}</p>
                        <p data-i18n="about.p3">${I18n.t('about.p3')}</p>
                    </div>
                </div>
            </div>
        `;

        return section;
    }

    return { render };
})();

export default About;
