import I18n from '../modules/i18n.js';

const Projects = (() => {

    let projectData = [];

    async function loadProjects() {
        const res = await fetch('/data/projects.json');
        projectData = await res.json();
    }

    function render() {
        const section = document.createElement('section');
        section.className = 'projects section section--alt reveal';
        section.id = 'projects';

        const lang = I18n.getLang();

        const cards = projectData.map(project => {
            const desc = lang === 'da' && project.descriptionDa
                ? project.descriptionDa
                : project.description;

            return `
                <div class="project-card">
                    <h3 class="project-card__title">${project.title}</h3>
                    <p class="project-card__desc">${desc}</p>
                    <div class="project-card__tech">
                        ${project.tech.map(t => `<span class="project-card__tech-tag">${t}</span>`).join('')}
                    </div>
                    <div class="project-card__links">
                        ${project.showroomPath ? `<a href="${project.showroomPath}" class="project-card__link" data-i18n="projects.liveDemo">${I18n.t('projects.liveDemo')}</a>` : ''}
                        ${project.github ? `<a href="${project.github}" target="_blank" rel="noopener" class="project-card__link" data-i18n="projects.viewCode">${I18n.t('projects.viewCode')}</a>` : ''}
                    </div>
                </div>
            `;
        }).join('');

        section.innerHTML = `
            <div class="container">
                <h2 data-i18n="projects.heading">${I18n.t('projects.heading')}</h2>
                <div class="projects__grid">${cards}</div>
            </div>
        `;

        return section;
    }

    return { loadProjects, render };
})();

export default Projects;
