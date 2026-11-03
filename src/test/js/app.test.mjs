import test from 'node:test';
import assert from 'node:assert/strict';

test('actual view keeps controls usable and rotates the button indicator while a job transforms', async () => {
  class Element {
    constructor() { this.disabled = false; this.hidden = false; this.events = {}; this.children = []; this.files = []; }
    setAttribute(key, value) { this[key] = value; }
    addEventListener(key, callback) { this.events[key] = callback; }
    append(...children) { this.children.push(...children); }
    prepend(...children) { this.children.unshift(...children); }
    replaceChildren(...children) { this.children = children; }
  }
  const selectors = new Map();
  for (const name of ['#export-form', '#package', '#delivery', '#submit', '#submit-spinner', '#status', '#jobs', '#file-help', 'option[value="EXIST_URL"]']) selectors.set(name, new Element());
  const originals = { window: globalThis.window, document: globalThis.document, fetch: globalThis.fetch };
  const events = {}; const id = '00000000-0000-4000-8000-000000000001';
  globalThis.document = { querySelector: selector => selectors.get(selector), createElement: () => new Element() };
  globalThis.window = { location: { origin: 'http://localhost:8081' }, sessionStorage: { getItem: () => null, setItem: () => {} },
    addEventListener: (name, callback) => { events[name] = callback; } };
  globalThis.fetch = async url => {
    if (url.endsWith('/config')) return Response.json({ maxUploadBytes: 536870912, deliveries: ['DOWNLOAD'], retentionSeconds: 7200 });
    if (url === '/api/papyrus/jobs') return Response.json({ id, state: 'ACCEPTED' }, { status: 202 });
    return Response.json({ id, state: 'TRANSFORMING' });
  };
  try {
    await import('../../main/resources/static/app.mjs'); await new Promise(resolve => setImmediate(resolve));
    selectors.get('#package').files = [new File(['zip'], 'package.zip')]; selectors.get('#delivery').value = 'DOWNLOAD';
    await selectors.get('#export-form').events.submit({ preventDefault() {} });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(selectors.get('#submit-spinner').hidden, false);
    assert.equal(selectors.get('#submit').disabled, false);
    assert.equal(selectors.get('#package').disabled, false);
    assert.equal(selectors.get('#delivery').disabled, false);
    assert.equal(selectors.get('#submit')['aria-busy'], 'true');
    assert.equal(selectors.get('#jobs').children[0].children[1].textContent, 'Trasformazione');
  } finally {
    events.pagehide?.();
    globalThis.window = originals.window; globalThis.document = originals.document; globalThis.fetch = originals.fetch;
  }
});
