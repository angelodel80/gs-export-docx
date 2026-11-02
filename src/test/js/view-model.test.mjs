import test from 'node:test';
import assert from 'node:assert/strict';
import { PapyrusViewModel } from '../../main/resources/static/view-model.mjs';
import { ApiModel, DOCX_TYPE } from '../../main/resources/static/api.mjs';

const file = () => new File(['data'], 'package.zip', { type: 'application/zip' });

test('DB archival failure still downloads the document and displays a warning', async () => {
  let downloaded = false;
  const vm = new PapyrusViewModel({ upload: async () => ({ archiveStatus: 'UNAVAILABLE' }) }, () => { downloaded = true; });
  const states = []; vm.subscribe(({ state }) => states.push(state));
  await vm.process(file(), true);
  assert.equal(downloaded, true);
  assert.equal(vm.current.state, 'DONE');
  assert.match(vm.current.message, /non riuscita/);
  assert.deepEqual(states, ['IDLE', 'LOADING', 'RUNNING', 'DONE']);
});

test('invalid files and oversized archives are rejected before upload', async () => {
  const vm = new PapyrusViewModel({ upload: () => assert.fail('must not upload') }, () => {});
  await vm.process(new File(['x'], 'x.txt'), false);
  assert.equal(vm.current.state, 'ERROR');
  await vm.process({ name: 'large.zip', size: 9 * 1024 * 1024 }, false);
  assert.match(vm.current.message, /8 MB/);
});

test('duplicate submissions are ignored and cancellation never reports success', async () => {
  let calls = 0;
  const vm = new PapyrusViewModel({ upload: (f, a, signal) => {
    calls++;
    return new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))));
  } }, () => assert.fail('must not download'));
  const pending = vm.process(file(), false);
  await vm.process(file(), false);
  vm.cancel(); await pending;
  assert.equal(calls, 1);
  assert.equal(vm.current.state, 'ERROR');
  assert.match(vm.current.message, /annullata/);
});

test('API uses same-origin URL, reads server errors and never downloads JSON as DOCX', async () => {
  const api = new ApiModel(async (url, options) => {
    assert.equal(url, '/api/papyrus/upload');
    assert.equal(options.body.get('granularity'), 'PRESERVE');
    return new Response(JSON.stringify({ detail: 'XML incompleto' }), { status: 422, headers: { 'X-Request-Id': 'test-id' } });
  });
  await assert.rejects(api.upload(file(), false), /XML incompleto.*test-id/);
  const bad = new ApiModel(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } }));
  await assert.rejects(bad.upload(file(), false), /non ha restituito/);
});

test('API returns real blob, safe filename and archive outcome', async () => {
  const api = new ApiModel(async () => new Response('docx-bytes', { headers: {
    'Content-Type': DOCX_TYPE, 'Content-Disposition': "attachment; filename*=UTF-8''pherc-1025.docx",
    'X-Archive-Status': 'UNAVAILABLE'
  } }));
  const result = await api.upload(file(), true);
  assert.equal(result.filename, 'pherc-1025.docx');
  assert.equal(result.archiveStatus, 'UNAVAILABLE');
  assert.equal(await result.blob.text(), 'docx-bytes');
});

test('API timeout aborts the pending request and gives an explicit diagnostic', async () => {
  const api = new ApiModel((url, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
  }), 5);
  await assert.rejects(api.upload(file(), false), /Tempo di attesa esaurito/);
});
