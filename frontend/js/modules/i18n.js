const I18n = (() => {
    let translations = {};
    let currentLang = localStorage.getItem('lang') || 'en';

    async function load(lang) {
        const res = await fetch(`/data/i18n/${lang}.json`);
        translations = await res.json();
        currentLang = lang;
        localStorage.setItem('lang', lang);
        document.documentElement.lang = lang;
        document.dispatchEvent(new CustomEvent('langchange', { detail: { lang } }));
    }

    function t(key) {
        return key.split('.').reduce((obj, k) => obj?.[k], translations) || key;
    }

    function getLang() {
        return currentLang;
    }

    return { load, t, getLang };
})();

export default I18n;
