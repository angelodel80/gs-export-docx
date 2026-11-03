#!/usr/bin/env bash
set -euo pipefail
image="${1:-gs-export-docx:local}"
project_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
name="gs-export-smoke-$$"
if [ "$#" -ge 2 ]; then
  output_dir="$2"
  mkdir -p "$output_dir"
  remove_output=false
else
  output_dir="$(mktemp -d)"
  remove_output=true
fi
cleanup() {
  docker logs "$name" > "$output_dir/container.log" 2>&1 || true
  docker rm -fv "$name" > /dev/null 2>&1 || true
  if [ "$remove_output" = true ]; then rm -rf -- "$output_dir"; fi
}
trap cleanup EXIT
docker run -d --name "$name" -p 127.0.0.1::8081 \
  --memory=1536m --pids-limit=160 --read-only --tmpfs /tmp:size=96m,mode=1777 \
  --cap-drop ALL --security-opt no-new-privileges:true \
  -e EXISTDB_ENABLED=true -e EXISTDB_REST_URL=http://127.0.0.1:1/exist/rest \
  -e EXISTDB_USERNAME=smoke "$image" > /dev/null
port="$(docker port "$name" 8081/tcp | awk -F: '{print $NF}')"
base="http://127.0.0.1:$port"
for attempt in $(seq 1 40); do
  if curl -fsS "$base/actuator/health/readiness" -o "$output_dir/readiness.json" 2>/dev/null; then break; fi
  sleep 1
done
curl -fsS "$base/" -o "$output_dir/index.html"
curl -fsS "$base/app.mjs" -D "$output_dir/js-headers.txt" -o "$output_dir/app.mjs"
grep -Eqi '^Content-Type: (text|application)/javascript' "$output_dir/js-headers.txt"
curl -fsS "$base/styles.css" -o "$output_dir/styles.css"
curl -fsS -F "file=@$project_root/src/test/resources/fixtures/pherc-1025.zip" -F archive=true \
  "$base/api/papyrus/upload" -D "$output_dir/headers.txt" -o "$output_dir/PHerc.1025-generated.docx"
grep -qi 'X-Archive-Status: UNAVAILABLE' "$output_dir/headers.txt"
grep -qi 'X-Fragment-Count: 2' "$output_dir/headers.txt"
docker exec "$name" java -Xmx32m -cp /app/health com.papyrus.Healthcheck
python3 - "$output_dir/PHerc.1025-generated.docx" <<'PY'
import sys, zipfile, xml.etree.ElementTree as E
with zipfile.ZipFile(sys.argv[1]) as z:
    root = E.fromstring(z.read('word/document.xml'))
ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
assert len(root.findall('.//w:tbl', ns)) == 4
text = ''.join(root.itertext())
assert '1025' in text and 'ϲ' in text and '\uE5CE' in text and 'P.Oxy.' not in text
print('Container smoke test passed: real PHerc. 1025 DOCX, four tables, database unavailable.')
PY
python3 "$project_root/scripts/smoke-async.py" "$base" "$project_root" "$output_dir"
