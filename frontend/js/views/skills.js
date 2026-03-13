import I18n from '../modules/i18n.js';

const Skills = (() => {

    const skillData = {
        backend:  ['Java', 'Spring Boot', 'Spring Data JPA', 'Python', 'Flask', 'Ruby', 'REST APIs'],
        devops:   ['Docker', 'Docker Compose', 'GitHub Actions', 'Nginx', 'Linux', 'Bash', 'SSH', 'DigitalOcean'],
        frontend: ['HTML', 'CSS', 'JavaScript', 'Thymeleaf'],
        ai:       ['Keras / TensorFlow', 'n8n', 'Ollama', 'Pandas', 'Neo4j', 'RAG'],
        tools:    ['Git', 'GitHub', 'MySQL', 'SQLite', 'Power Platform', 'Maven', 'Selenium']
    };

    function render() {
        const section = document.createElement('section');
        section.className = 'skills section section--left reveal';
        section.id = 'skills';

        const categories = Object.entries(skillData).map(([key, techs]) => `
            <div class="skills__category skills__category--${key}">
                <h3 data-i18n="skills.categories.${key}">${I18n.t(`skills.categories.${key}`)}</h3>
                <div class="skills__tags">
                    ${techs.map(t => `<span class="skills__tag">${t}</span>`).join('')}
                </div>
            </div>
        `).join('');

        section.innerHTML = `
            <div class="container">
                <h2 data-i18n="skills.heading">${I18n.t('skills.heading')}</h2>
                <div class="skills__grid">${categories}</div>
            </div>
        `;

        return section;
    }

    return { render };
})();

export default Skills;
