export class JobApi {
  constructor(fetcher = (...args) => globalThis.fetch(...args), timeoutMs = 600_000) {
    this.fetcher = fetcher; this.timeoutMs = timeoutMs;
  }
  async request(url, options = {}, timeoutMs = this.timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await this.fetcher(url, { ...options, signal: controller.signal });
      if (!response.ok) {
        let detail = `Errore HTTP ${response.status}`;
        try { detail = (await response.json()).detail || detail; } catch { /* HTTP fallback */ }
        const error = new Error(detail); error.status = response.status; throw error;
      }
      return await response.json();
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('Tempo di attesa esaurito. La richiesta potrebbe essere già stata presa in carico.');
      throw error;
    } finally { clearTimeout(timer); }
  }
  config() { return this.request('/api/papyrus/jobs/config', {}, 15_000); }
  submit(file, delivery) {
    const body = new FormData(); body.append('file', file); body.append('delivery', delivery);
    return this.request('/api/papyrus/jobs', { method: 'POST', body });
  }
  status(id) { return this.request(`/api/papyrus/jobs/${encodeURIComponent(id)}`, {}, 15_000); }
}

export const phaseLabels = { UPLOADING: 'Invio del pacchetto', ACCEPTED: 'Presa in carico',
  TRANSFORMING: 'Trasformazione', READY: 'Risorsa pronta', FAILED: 'Esportazione non riuscita' };

export class AsyncJobsViewModel {
  constructor(api, { interval = 1500, schedule = setTimeout, unschedule = clearTimeout, storage = null } = {}) {
    this.api = api; this.interval = interval; this.schedule = schedule; this.unschedule = unschedule; this.storage = storage;
    this.jobs = new Map(); this.timers = new Map(); this.listeners = new Set(); this.sequence = 0; this.disposed = false;
    this.settings = { maxUploadBytes: 512 * 1024 * 1024, deliveries: ['DOWNLOAD'] };
  }
  subscribe(listener) { this.listeners.add(listener); listener(this.snapshot()); return () => this.listeners.delete(listener); }
  snapshot() { return [...this.jobs.values()]; }
  emit() {
    const snapshots = this.snapshot();
    for (const listener of this.listeners) listener(snapshots);
    try { this.storage?.setItem('gs-export-jobs', JSON.stringify(snapshots.filter(j => j.id).slice(-16))); } catch { /* optional */ }
  }
  async initialize() {
    this.settings = await this.api.config();
    try {
      const saved = JSON.parse(this.storage?.getItem('gs-export-jobs') || '[]');
      if (Array.isArray(saved)) for (const job of saved.slice(-16)) {
        if (!job.id || !/^[0-9a-f-]{36}$/i.test(job.id)) continue;
        this.jobs.set(job.id, job); this.poll(job.id);
      }
    } catch { /* malformed storage does not block exports */ }
    this.emit(); return this.settings;
  }
  async submit(file, delivery = 'DOWNLOAD') {
    if (!file || !/\.zip$/i.test(file.name)) throw new Error('Seleziona un archivio .zip.');
    if (!file.size || file.size > this.settings.maxUploadBytes) throw new Error('Archivio vuoto o oltre il limite indicato.');
    if (!this.settings.deliveries.includes(delivery)) throw new Error('Modalità di consegna non disponibile.');
    const uploadKey = `${file.name}:${file.size}:${file.lastModified}`;
    if ([...this.jobs.values()].some(j => j.state === 'UPLOADING' && j.uploadKey === uploadKey)) return;
    const key = `upload-${++this.sequence}`;
    this.jobs.set(key, { key, name: file.name, state: 'UPLOADING', uploadKey }); this.emit();
    try {
      const job = await this.api.submit(file, delivery);
      if (this.disposed) return;
      this.jobs.delete(key); this.jobs.set(job.id, { ...job, name: file.name }); this.emit(); this.poll(job.id);
    } catch (error) {
      if (this.disposed) return;
      this.jobs.set(key, { key, name: file.name, state: 'FAILED', error: error.message }); this.emit();
    }
  }
  async poll(id) {
    if (this.disposed) return;
    try {
      const job = await this.api.status(id);
      if (this.disposed) return;
      this.jobs.set(id, { ...job, name: this.jobs.get(id)?.name }); this.emit();
      if (job.state === 'READY' || job.state === 'FAILED') { this.timers.delete(id); return; }
    } catch (error) {
      if (this.disposed) return;
      const job = this.jobs.get(id);
      if (error.status === 404 || error.status === 410) {
        this.jobs.set(id, { ...job, state: 'FAILED', error: 'Risultato scaduto o non disponibile: invia di nuovo il pacchetto.', resultUrl: null });
        this.emit(); this.timers.delete(id); return;
      }
      this.jobs.set(id, { ...job, connectionWarning: 'Connessione interrotta; riprovo a leggere lo stato…' }); this.emit();
    }
    if (!this.disposed) this.timers.set(id, this.schedule(() => this.poll(id), this.interval));
  }
  dispose() { this.disposed = true; for (const timer of this.timers.values()) this.unschedule(timer); this.timers.clear(); }
}
