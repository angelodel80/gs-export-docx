import test from 'node:test';
import assert from 'node:assert/strict';
import { JobApi, AsyncJobsViewModel } from '../../main/resources/static/jobs.mjs';

const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const file = name => new File(['zip'], name, { type: 'application/zip' });
const flush = () => new Promise(resolve => setImmediate(resolve));

test('two exports progress independently and reach ready without loading a document blob', async () => {
  const callbacks = []; let count = 0; const calls = new Map();
  const api = { submit: async () => ({ id: id(++count), state: 'ACCEPTED' }), status: async jobId => {
    const n = (calls.get(jobId) || 0) + 1; calls.set(jobId, n);
    return { id: jobId, state: n === 1 ? 'TRANSFORMING' : 'READY', delivery: 'DOWNLOAD', resultUrl: `/api/papyrus/jobs/${jobId}/document` };
  } };
  const vm = new AsyncJobsViewModel(api, { schedule: callback => { callbacks.push(callback); return callbacks.length; } });
  await Promise.all([vm.submit(file('one.zip')), vm.submit(file('two.zip'))]); await flush();
  assert.equal(vm.snapshot().length, 2); assert.ok(vm.snapshot().every(j => j.state === 'TRANSFORMING'));
  for (const callback of callbacks.splice(0)) await callback();
  assert.ok(vm.snapshot().every(j => j.state === 'READY')); vm.dispose();
});

test('double click during the same upload is ignored while another file can be submitted', async () => {
  const pending = []; let calls = 0;
  const vm = new AsyncJobsViewModel({ submit: () => { calls++; return new Promise(resolve => pending.push(resolve)); },
    status: async jobId => ({ id: jobId, state: 'READY' }) });
  const firstFile = file('one.zip'); const first = vm.submit(firstFile); const duplicate = vm.submit(firstFile); const second = vm.submit(file('two.zip'));
  assert.equal(calls, 2); pending[0]({ id: id(1), state: 'ACCEPTED' }); pending[1]({ id: id(2), state: 'ACCEPTED' });
  await Promise.all([first, duplicate, second]); vm.dispose();
});

test('transient poll failures retry and expired results stop polling with an explicit message', async () => {
  let callback; let calls = 0;
  const api = { submit: async () => ({ id: id(1), state: 'ACCEPTED' }), status: async () => {
    if (++calls === 1) throw new Error('network'); const error = new Error('gone'); error.status = 410; throw error;
  } };
  const vm = new AsyncJobsViewModel(api, { schedule: fn => { callback = fn; return 1; } });
  await vm.submit(file('one.zip')); await flush(); assert.match(vm.snapshot()[0].connectionWarning, /Connessione/);
  await callback(); assert.equal(vm.snapshot()[0].state, 'FAILED'); assert.match(vm.snapshot()[0].error, /scaduto/);
  assert.equal(calls, 2); vm.dispose();
});

test('session reload resumes known jobs and eXist result is exposed as a URL', async () => {
  const saved = JSON.stringify([{ id: id(3), state: 'TRANSFORMING', name: 'one.zip' }]);
  const storage = { getItem: () => saved, setItem: () => {} };
  const vm = new AsyncJobsViewModel({ config: async () => ({ maxUploadBytes: 1000, deliveries: ['DOWNLOAD', 'EXIST_URL'] }),
    status: async jobId => ({ id: jobId, state: 'READY', delivery: 'EXIST_URL', resultUrl: 'https://example.test/document.docx' }) }, { storage });
  await vm.initialize(); await flush(); assert.equal(vm.snapshot()[0].delivery, 'EXIST_URL');
  assert.equal(vm.snapshot()[0].resultUrl, 'https://example.test/document.docx'); vm.dispose();
});

test('large upload uses multipart 202 API and the default fetch keeps its receiver', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = function (url, options) {
    assert.equal(this, globalThis); assert.equal(url, '/api/papyrus/jobs');
    assert.equal(options.body.get('delivery'), 'DOWNLOAD');
    return Promise.resolve(new Response(JSON.stringify({ id: id(1), state: 'ACCEPTED' }), { status: 202 }));
  };
  try { assert.equal((await new JobApi().submit(file('one.zip'), 'DOWNLOAD')).state, 'ACCEPTED'); }
  finally { globalThis.fetch = original; }
});

test('upload errors leave an independent failed item and dispose clears polling', async () => {
  let cancelled = false;
  const vm = new AsyncJobsViewModel({ submit: async () => { const error = new Error('Coda piena'); error.status = 429; throw error; } }, { unschedule: () => { cancelled = true; } });
  await vm.submit(file('one.zip')); assert.equal(vm.snapshot()[0].state, 'FAILED'); assert.match(vm.snapshot()[0].error, /Coda/);
  vm.timers.set(id(1), 1); vm.dispose(); assert.equal(cancelled, true);
});
