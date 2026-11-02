import { ApiModel } from './api.mjs';
import { PapyrusViewModel } from './view-model.mjs';

function download({ blob, filename }) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

const vm = new PapyrusViewModel(new ApiModel(), download);
const form = document.querySelector('#export-form');
const file = document.querySelector('#package');
const archive = document.querySelector('#archive');
const submit = document.querySelector('#submit');
const cancel = document.querySelector('#cancel');
const status = document.querySelector('#status');

vm.subscribe(({ state, message }) => {
  const busy = state === 'LOADING' || state === 'RUNNING';
  status.dataset.state = state;
  status.textContent = message;
  submit.disabled = busy;
  file.disabled = busy;
  archive.disabled = busy;
  cancel.hidden = !busy;
  form.setAttribute('aria-busy', String(busy));
});
form.addEventListener('submit', event => { event.preventDefault(); vm.process(file.files[0], archive.checked); });
cancel.addEventListener('click', () => vm.cancel());
