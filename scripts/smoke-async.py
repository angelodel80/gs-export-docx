"""Functional check of the async API against an already-running service (stdlib only)."""
import sys, urllib.request, json, time, uuid, zipfile, io, xml.etree.ElementTree as E
from pathlib import Path

base, project, out = sys.argv[1:]
boundary = 'smoke-' + uuid.uuid4().hex
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
data = (f'--{boundary}\r\nContent-Disposition: form-data; name="delivery"\r\n\r\nEXIST_URL\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="pherc.zip"\r\n\r\n'.encode()
        + Path(project, 'src/test/resources/fixtures/pherc-1025.zip').read_bytes()
        + f'\r\n--{boundary}--\r\n'.encode())
request = urllib.request.Request(base + '/api/papyrus/jobs', data=data,
                                 headers={'Content-Type': f'multipart/form-data; boundary={boundary}'})
with opener.open(request, timeout=15) as response:
    assert response.status == 202
    job = json.load(response)
    assert job['state'] == 'ACCEPTED'
    status_url = base + response.headers['Location']
for _ in range(100):
    with opener.open(status_url, timeout=5) as response:
        job = json.load(response)
    if job['state'] in ['READY', 'FAILED']:
        break
    time.sleep(.2)
assert job['state'] == 'READY', job
assert job['delivery'] == 'DOWNLOAD' and job['warning'] and job['transformationStartedAt'] and job['completedAt']
with opener.open(base + job['resultUrl'], timeout=15) as response:
    document = response.read()
with zipfile.ZipFile(io.BytesIO(document)) as archive:
    root = E.fromstring(archive.read('word/document.xml'))
assert len(root.findall('.//{http://schemas.openxmlformats.org/wordprocessingml/2006/main}tbl')) == 4
assert 'ϲ' in ''.join(root.itertext())
Path(out, 'async-result.json').write_text(json.dumps(job, indent=2))
Path(out, 'PHerc.1025-async.docx').write_bytes(document)
print('Async container smoke passed: ACCEPTED -> TRANSFORMING -> READY; eXist fallback to real DOCX download.')
