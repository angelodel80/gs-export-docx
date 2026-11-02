export class PapyrusViewModel {
  constructor(api, download) {
    this.api = api;
    this.download = download;
    this.listeners = new Set();
    this.current = { state: 'IDLE', message: 'Seleziona un pacchetto per iniziare.' };
    this.controller = null;
  }
  subscribe(listener) {
    this.listeners.add(listener);
    listener(this.current);
    return () => this.listeners.delete(listener);
  }
  set(state, message) {
    this.current = { state, message };
    for (const listener of this.listeners) listener(this.current);
  }
  cancel() { this.controller?.abort(); }
  async process(file, archive) {
    if (this.controller) return;
    if (!file || !/\.zip$/i.test(file.name)) { this.set('ERROR', 'Seleziona un archivio .zip.'); return; }
    if (file.size > 8 * 1024 * 1024) { this.set('ERROR', 'L’archivio supera il limite di 8 MB.'); return; }
    this.controller = new AbortController();
    try {
      this.set('LOADING', 'Preparazione dell’invio…');
      this.set('RUNNING', 'Invio e conversione del pacchetto in corso…');
      const result = await this.api.upload(file, archive, this.controller.signal);
      if (this.controller.signal.aborted) { this.set('ERROR', 'Attesa annullata.'); return; }
      await this.download(result);
      const archiveMessage = {
        SKIPPED: '', ARCHIVED: ' Copia archiviata su eXist-db.',
        NOT_CONFIGURED: ' DOCX generato localmente; archiviazione eXist non configurata.',
        UNAVAILABLE: ' DOCX generato localmente; archiviazione eXist non riuscita.'
      }[result.archiveStatus] || '';
      this.set('DONE', 'Download DOCX avviato.' + archiveMessage);
    } catch (error) {
      this.set('ERROR', error.name === 'AbortError' ? 'Attesa annullata.' : error.message);
    } finally {
      this.controller = null;
    }
  }
}
