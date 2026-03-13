import I18n from '../modules/i18n.js';

const Contact = (() => {

    function render() {
        const section = document.createElement('section');
        section.className = 'contact section section--alt';
        section.id = 'contact';

        section.innerHTML = `
            <div class="container">
                <h2 data-i18n="contact.heading">${I18n.t('contact.heading')}</h2>
                <p class="contact__text" data-i18n="contact.text">${I18n.t('contact.text')}</p>
                <div class="contact__links">
                    <a href="https://github.com/gabel1998" target="_blank" rel="noopener" class="btn btn--primary">GitHub</a>
                    <a href="https://linkedin.com" target="_blank" rel="noopener" class="btn btn--primary">LinkedIn</a>
                </div>
                <form class="contact__form" id="contact-form">
                    <div class="form__group">
                        <label for="contact-name" data-i18n="contact.form.name">${I18n.t('contact.form.name')}</label>
                        <input type="text" id="contact-name" required>
                    </div>
                    <div class="form__group">
                        <label for="contact-email" data-i18n="contact.form.email">${I18n.t('contact.form.email')}</label>
                        <input type="email" id="contact-email" required>
                    </div>
                    <div class="form__group">
                        <label for="contact-message" data-i18n="contact.form.message">${I18n.t('contact.form.message')}</label>
                        <textarea id="contact-message" required></textarea>
                    </div>
                    <button type="submit" class="btn btn--primary" data-i18n="contact.form.send">${I18n.t('contact.form.send')}</button>
                </form>
            </div>
        `;

        return section;
    }

    return { render };
})();

export default Contact;
