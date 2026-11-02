export const DOCX_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

export class ApiModel {
  constructor(fetcher = (...args) => globalThis.fetch(...args), timeoutMs = 180_000) {
    this.fetcher = fetcher;
    this.timeoutMs = timeoutMs;
  }

  async upload(file, archive, signal) {
    const controller = new AbortController();
    let timedOut = false;
    const abort = () => controller.abort();
    signal?.addEventListener('abort', abort, { once: true });
    if (signal?.aborted) abort();
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, this.timeoutMs);
    try {
      const body = new FormData();
      body.append('file', file);
      body.append('granularity', 'PRESERVE');
      body.append('archive', String(archive));
      const response = await this.fetcher('/api/papyrus/upload', { method: 'POST', body, signal: controller.signal });
      const requestId = response.headers.get('X-Request-Id');
      if (!response.ok) {
        let message = `Conversione non riuscita (HTTP ${response.status}).`;
        try { const problem = await response.json(); message = problem.detail || message; } catch { /* keep HTTP diagnostic */ }
        throw new Error(message + (requestId ? ` Richiesta: ${requestId}` : ''));
      }
      if (response.headers.get('Content-Type')?.split(';')[0] !== DOCX_TYPE) {
        throw new Error('Il server non ha restituito un documento DOCX.');
      }
      let filename = 'edizione.docx';
      const disposition = response.headers.get('Content-Disposition') || '';
      const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i);
      const plain = disposition.match(/filename="([^"]+)"/i);
      try {
        const candidate = encoded ? decodeURIComponent(encoded[1]) : plain?.[1];
        if (candidate && /^[A-Za-z0-9][A-Za-z0-9._-]*\.docx$/.test(candidate)) filename = candidate;
      } catch { /* use safe fallback */ }
      return { blob: await response.blob(), filename,
        archiveStatus: response.headers.get('X-Archive-Status') || 'SKIPPED', requestId };
    } catch (error) {
      if (timedOut) throw new Error('Tempo di attesa esaurito. Verifica l’esito prima di ripetere l’invio.');
      throw error;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
    }
  }
}
