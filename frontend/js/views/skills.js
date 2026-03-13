import I18n from '../modules/i18n.js';

const Skills = (() => {

    const skillData = {
        backend:  ['Java', 'Spring Boot', 'Python', 'Flask'],
        devops:   ['Docker', 'Docker Compose', 'GitHub Actions', 'Nginx', 'Linux', 'Bash'],
        frontend: ['HTML', 'CSS', 'JavaScript', 'Thymeleaf', 'D3.js'],
        data:     ['Python', 'Keras / TensorFlow', 'n8n', 'Ollama', 'Neo4j'],
        tools:    ['Git', 'GitHub', 'DigitalOcean', 'Power Platform', 'MySQL', 'PostgreSQL']
    };

    function render() {
        const section = document.createElement('section');
        section.className = 'skills section';
        section.id = 'skills';

        const categories = Object.entries(skillData).map(([key, techs]) => `
            <div class="skills__category">
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
