const Footer = (() => {

    function render() {
        const footer = document.createElement('footer');
        footer.className = 'footer';

        const year = new Date().getFullYear();

        footer.innerHTML = `
            <div class="container">
                <div class="footer__inner">
                    <span class="footer__copy">&copy; ${year} Andreas Gabel</span>
                    <div class="footer__links">
                        <a href="https://github.com/gabel1998" target="_blank" rel="noopener" class="footer__link">GitHub</a>
                        <a href="https://linkedin.com" target="_blank" rel="noopener" class="footer__link">LinkedIn</a>
                    </div>
                </div>
            </div>
        `;

        return footer;
    }

    return { render };
})();

export default Footer;
