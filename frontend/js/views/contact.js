import I18n from '../modules/i18n.js';

const Contact = (() => {

    function render() {
        const section = document.createElement('section');
        section.className = 'contact section section--alt section--left reveal';
        section.id = 'contact';

        section.innerHTML = `
            <div class="container">
                <h2 data-i18n="contact.heading">${I18n.t('contact.heading')}</h2>
                <p class="contact__text" data-i18n="contact.text">${I18n.t('contact.text')}</p>
                <div class="contact__links">
                    <a href="mailto:Andreassgabel@hotmail.com?subject=Hello%20Andreas" class="btn btn--primary" data-i18n="contact.email">${I18n.t('contact.email')}</a>
                    <a href="https://github.com/gabel1998" target="_blank" rel="noopener" class="btn btn--primary">GitHub</a>
                    <a href="https://www.linkedin.com/in/andreas-søgaard-gabel-758991133" target="_blank" rel="noopener" class="btn btn--primary">LinkedIn</a>
                </div>
            </div>
        `;

        return section;
    }

    return { render };
})();

export default Contact;
