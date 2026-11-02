# gs-export-docx

Servizio Spring Boot per trasformare pacchetti XML GreekSchools in DOCX, con archiviazione opzionale su un eXist-db esistente.

**La conversione avviene localmente anche quando eXist non è configurato o raggiungibile.** Spazi, rientri, righe vuote, sigma lunate, parentesi, diacritici e caratteri privati Unicode vengono conservati. Nessun errore viene sostituito da un'edizione dimostrativa.

## Avvio dai sorgenti

Serve un JDK completo 17 o successivo. Il Wrapper scarica Maven 3.9.9 con checksum SHA-256 fissato. Node 22.8+ serve soltanto ai test JavaScript; il frontend non richiede npm install, bundler o CDN.

```bash
./mvnw -B verify
java -jar target/gs-export-docx.jar
```

Su Windows usare `mvnw.cmd -B verify`. Aprire `http://localhost:8081`: interfaccia e API sono servite dalla stessa applicazione. Il file HTML non richiede un server separato. I campioni forniti sono in `src/test/resources/fixtures/`.

```bash
npm test
```

## Docker

```bash
docker compose up --build
```

Compose avvia il solo esportatore su `127.0.0.1:8081`, con utente non root, filesystem in sola lettura, `/tmp` temporaneo e memoria limitata a 768 MB. Non crea un secondo database.

Per costruire e provare l'immagine autonomamente:

```bash
docker build -t gs-export-docx:local .
bash scripts/smoke-container.sh gs-export-docx:local output/smoke
```

Lo smoke test richiede Bash, curl e Python 3. Avvia e arresta un proprio container, configura intenzionalmente un archivio irraggiungibile e verifica il DOCX reale del campione. La directory finale opzionale conserva documento, header e log.

Build multi-stage con JDK 17, runtime JRE 17 Temurin e health check. Il Dockerfile accetta i secret BuildKit opzionali `maven_settings` e `java_truststore` per proxy/CA aziendali: non vengono copiati nell'immagine e la verifica TLS resta attiva. Le credenziali eXist non sono argomenti di build.

## Archiviazione eXist facoltativa

La conversione usa sempre gli XML validati del pacchetto. Se richiesta nell'interfaccia, l'archiviazione prova a salvare i **byte XML originali dopo la generazione**. Un errore DB non altera il documento e viene segnalato nell'interfaccia e nell'header `X-Archive-Status`.

| Variabile | Default / significato |
|---|---|
| `PORT` | `8081` |
| `EXISTDB_ENABLED` | `false` |
| `EXISTDB_REST_URL` | `http://localhost:8080/exist/rest`, senza `/db` finale |
| `EXISTDB_COLLECTION` | `/db/gs-export`, collection dedicata già esistente |
| `EXISTDB_USERNAME` | Vuoto; obbligatorio quando abilitato |
| `EXISTDB_PASSWORD` | Vuoto; configurare secondo l'account dell'istanza |

Per Compose partire da `.env.example`, mantenendo `.env` fuori da Git. In un container `localhost` identifica il container stesso: usare l'URL reale di eXist o un nome raggiungibile. Compose fornisce `host.docker.internal` per un DB di sviluppo sull'host.

Provisionare la collection di archivio e un account con permessi di creazione/scrittura/cancellazione solo su quella collection, usando gli strumenti della propria istanza. Non ci sono credenziali amministrative predefinite. Proteggere il collegamento di produzione con TLS o una rete privata appropriata.

Ogni archivio crea una collection `batch_<UUID>` con XQuery e carica i file via REST PUT. I batch completi sono conservati; in caso di errore viene tentata la rimozione del solo batch corrente. Se il cleanup non è confermato, il log riporta l'ID da verificare. Nessun batch preesistente viene sovrascritto. Definire una retention operativa per gli archivi conservati.

Default: 2 s per richiesta HTTP, 5 s complessivi di archiviazione, più un cleanup limitato a 250 ms. Proprietà Spring: `existdb.timeout`, `existdb.archive-budget`. Il timeout comprende il corpo della risposta; la risposta è limitata a 64 KiB.

L'adattatore è coperto da test REST locali; il collegamento alla versione/configurazione reale di eXist va collaudato nell'ambiente di integrazione. Questa versione esporta pacchetti ZIP, non risorse selezionate direttamente sul DB.

## API

```bash
curl --fail-with-body \
  -F file=@src/test/resources/fixtures/pherc-1025.zip \
  -F granularity=PRESERVE -F archive=false \
  http://localhost:8081/api/papyrus/upload -o PHerc.1025.docx
```

`POST /api/papyrus/upload`, `multipart/form-data`:

| Campo | Default | Funzione |
|---|---|---|
| `file` | Obbligatorio | ZIP con soli XML ed eventuali directory |
| `mapping` | `GS_UNIT` | Strategia di ricostruzione |
| `granularity` | `PRESERVE` | Politica testuale |
| `format` | `DOCX` | Renderer |
| `archive` | `false` | Archiviazione esplicitamente richiesta |

`FULL_TEXT` e `CHAR_LEVEL` sono alias conservativi di `PRESERVE`: non eliminano più spazi, parentesi o sigma lunate. Strategie sconosciute producono `400`.

Successo: MIME DOCX, `Content-Disposition: attachment`, `Cache-Control: no-store`, `X-Request-Id`, `X-Fragment-Count`, `X-Archive-Status`:

- `SKIPPED`: archiviazione non richiesta;
- `NOT_CONFIGURED`: richiesta ma disabilitata;
- `ARCHIVED`: completata;
- `UNAVAILABLE`: non completata, documento generato correttamente dai dati locali.

Gli errori sono `application/problem+json` con `code`, `detail`, `requestId`: `400` richiesta errata, `413` limiti superati, `415` input non ZIP/formato HTTP errato, `422` XML/ZIP o gerarchia non validi, `429` capacità occupata, `500` errore inatteso di conversione. L'archivio facoltativo indisponibile non è un errore di conversione.

## XML e impaginazione

Vedere [contratto XML](docs/xml-contract.md). La classificazione usa `dsl/language`, i collegamenti `id`, `target`, `children/child`. È supportata una gerarchia con livelli strutturali intermedi; ogni frammento esportabile deve avere le sei unità testuali previste. I tipi sconosciuti richiedono un mapper esplicito.

Il parser riconosce l'encoding XML. La normalizzazione dei fine riga XML 1.0 (CRLF/CR → LF) avviene nel parsing; nessun trim, sostituzione editoriale o normalizzazione Unicode viene applicato al contenuto. I byte originali rimangono disponibili per l'archivio.

Layout derivato dal Word fornito:

1. Pagina diplomatica: fonti e apparato paleografico; numeri di riga; trascrizione diplomatica.
2. Pagina letteraria: numeri; trascrizione letteraria; traduzione; apparato filologico in cella unita sottostante.

Il campione produce quattro tabelle. A4 verticale, margini 20 mm, griglia fissa entro l'area stampabile, corpo 10 pt, interlinea 12 pt e numeri marginali con la stessa interlinea. Ogni riga è un paragrafo, incluse righe vuote e tabulazioni. Numeri ogni cinque e sull'ultima riga; righe vuote e indicazioni iniziate da `desunt` escluse. L'etichetta di frammento/colonna viene ricavata dal titolo, senza inventare numeri editoriali.

Le larghezze sono adattate all'area stampabile: il modello estende alcune tabelle oltre i margini. Non vengono ricostruite tutte le formattazioni manuali (corsivi selettivi, segni Symbol), assenti dagli XML testuali; non vengono aggiunte pagine bianche per imporre la parità sinistra/destra.

I testi lunghi possono occupare ulteriori pagine. POI non calcola la paginazione finale di Word e non viene applicato un ridimensionamento silenzioso. Il confronto visuale richiede Word con i font corretti.

Il font **IFAO-Grec Unicode** è necessario per alcuni segni privati, tra cui `U+E5CE`, e deve essere disponibile sul computer che apre il DOCX. I font non sono incorporati o redistribuiti. Sono configurabili con `EXPORT_GREEK_FONT` e `EXPORT_LATIN_FONT`, verificando la copertura dei glifi.

## Strategie e modularità

```text
api/                     HTTP, errori, identificativi richiesta
application/             orchestrazione e concorrenza
domain/                  modello immutabile
xml/                     lettura sicura e limitata
mapping/                 mapping e conversione del testo
docx/                    renderer, stili e strategie di pagina
infrastructure/exist/    archiviatore REST facoltativo
```

I bean Spring vengono registrati per nome, senza switch nell'orchestratore:

- `EditionMappingStrategy`: nuovo schema con `name()` e `map(...)`;
- `TextConversionStrategy`: nuova politica esplicita con `names()` e `convert(...)`;
- `DocumentRenderer`: nuovo formato con MIME, estensione e rendering;
- `PageLayoutStrategy`: ulteriori pagine DOCX con bean e `@Order`;
- `ArchiveGateway`: archiviatore sostituibile senza modificare il motore.

Registrazioni duplicate falliscono all'avvio. Le nuove trasformazioni devono avere test di integrità testuale. L'interfaccia separa Model (`ApiModel`), ViewModel (`PapyrusViewModel`) e View con moduli JavaScript nativi e stato osservabile, senza dipendenze esterne.

## Limiti e operatività

Default configurabili in `application.yml`:

- upload 8 MB; richiesta multipart 9 MB;
- 256 entry, 2 MiB ciascuna, 16 MiB decompressi complessivi;
- profondità XML 64; DTD, entità esterne e XInclude disabilitati;
- 100 frammenti, 10.000 righe testuali, 20.000 unità UTF-16 per riga;
- due conversioni contemporanee; oltre il limite `429` con `Retry-After`.

Le entry non vengono estratte sul filesystem. Anche dati associati a directory ZIP sono contati e rifiutati. Dimensionare le soglie sulla memoria e sul carico effettivi. Non ci sono code persistenti o conservazione locale dei DOCX dopo la risposta.

`/actuator/health/liveness` e `/actuator/health/readiness` verificano il servizio. Il DB facoltativo non abbassa la readiness. La JVM termina in modo controllato; i log riportano stato, durata e ID richiesta senza testo XML o password.

Il servizio non implementa login. Per l'esposizione pubblica integrarlo con autenticazione/autorizzazione, TLS e limiti di frequenza del proprio gateway. Compose è un avvio locale, non un deployment pubblico.

## GitHub e Docker Hub

Repository previsto: `angelodel80/gs-export-docx`. La CI verifica backend, frontend e container. La release su tag `vX.Y.Z` ripete i test e pubblica su Docker Hub per `linux/amd64` con tag versione/revisione, SBOM e provenienza.

Configurare l'environment GitHub **dockerhub** con secret `DOCKERHUB_USERNAME`, secret `DOCKERHUB_TOKEN` e variable `DOCKERHUB_NAMESPACE`. Prima della release fissare la versione nel POM. È possibile configurare reviewer dell'environment per controllare il rilascio. Pipeline remota e pubblicazioni non sono eseguite da questa consegna.

Esempio, dopo aver esaminato le modifiche:

```bash
git status
git add README.md pom.xml mvnw mvnw.cmd .mvn .gitignore .dockerignore .env.example \
  Dockerfile compose.yaml package.json src scripts docs .github
git diff --cached --stat
git commit -m "Implement conservative XML to DOCX export with optional eXist archive"
git push origin HEAD:main
```

Seguire la propria procedura di branch/PR se richiesta. Il repository è già inizializzato: non ricreare origin. I campioni editoriali inclusi per regressione non entrano nell'immagine runtime.

Vedere [verifiche della consegna](docs/validation.md) per controlli eseguiti e limiti.

## Licenza

Codice MIT: [LICENSE](LICENSE). Font e materiali editoriali mantengono i propri diritti.
