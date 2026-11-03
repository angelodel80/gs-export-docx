import { JobApi, AsyncJobsViewModel, phaseLabels } from './jobs.mjs';
let storage = null;
try { storage = window.sessionStorage; } catch { /* optional */ }
const vm = new AsyncJobsViewModel(new JobApi(), { storage });
const form = document.querySelector('#export-form');
const file = document.querySelector('#package');
const delivery = document.querySelector('#delivery');
const submit = document.querySelector('#submit');
const spinner = document.querySelector('#submit-spinner');
const status = document.querySelector('#status');
const jobs = document.querySelector('#jobs');

function resultLink(job) {
  if (!job.resultUrl) return null;
  try {
    const url = new URL(job.resultUrl, window.location.origin);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null;
    if (job.delivery === 'DOWNLOAD' && url.origin !== window.location.origin) return null;
    const link = document.createElement('a'); link.href = url.href;
    link.textContent = job.delivery === 'EXIST_URL' ? 'Apri DOCX su eXist-db' : 'Scarica DOCX';
    if (job.delivery === 'EXIST_URL') { link.target = '_blank'; link.rel = 'noopener noreferrer'; }
    return link;
  } catch { return null; }
}

vm.subscribe(list => {
  const active = list.filter(j => ['UPLOADING', 'ACCEPTED', 'TRANSFORMING'].includes(j.state));
  spinner.hidden = active.length === 0;
  submit.setAttribute('aria-busy', String(active.length > 0));
  // Existing jobs never disable selection/submission of another package.
  jobs.replaceChildren();
  for (const job of list) {
    const row = document.createElement('article'); row.className = 'job';
    const title = document.createElement('strong'); title.textContent = job.name || job.filename || job.id;
    const phase = document.createElement('p'); phase.textContent = phaseLabels[job.state] || job.state;
    if (['UPLOADING', 'ACCEPTED', 'TRANSFORMING'].includes(job.state)) {
      const icon = document.createElement('span'); icon.className = 'spinner'; icon.setAttribute('aria-hidden', 'true'); phase.prepend(icon, ' ');
    }
    row.append(title, phase);
    if (job.id) { const id = document.createElement('small'); id.textContent = `Esportazione: ${job.id}`; row.append(id); }
    for (const message of [job.warning, job.error, job.connectionWarning]) if (message) {
      const text = document.createElement('p'); text.textContent = message; row.append(text);
    }
    if (job.state === 'READY') { const link = resultLink(job); if (link) row.append(link); }
    if (job.expiresAt) { const expiry = document.createElement('small'); expiry.textContent = `Stato e copia locale disponibili fino al ${new Date(job.expiresAt).toLocaleString('it-IT')}`; row.append(expiry); }
    jobs.append(row);
  }
  if (list.length) status.textContent = active.length ? `${active.length} esportazioni in attesa o in elaborazione. Puoi usare gli altri controlli.` : 'Elaborazioni terminate. I risultati sono elencati sotto.';
});
form.addEventListener('submit', async event => {
  event.preventDefault();
  try { await vm.submit(file.files[0], delivery.value); }
  catch (error) { status.textContent = error.message; }
});
submit.disabled = true;
vm.initialize().then(config => {
  document.querySelector('option[value="EXIST_URL"]').disabled = !config.deliveries.includes('EXIST_URL');
  document.querySelector('#file-help').textContent = `ZIP fino a ${Math.floor(config.maxUploadBytes / 1024 / 1024)} MiB. Trasformazione in background; risultati locali disponibili per ${Math.floor(config.retentionSeconds / 60)} minuti.`;
  submit.disabled = false;
}).catch(error => { status.textContent = `Impossibile inizializzare le esportazioni: ${error.message}. Ricarica la pagina.`; });
window.addEventListener('pagehide', () => vm.dispose());
window.addEventListener('pageshow', event => { if (event.persisted) window.location.reload(); });
