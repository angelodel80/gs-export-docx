# Verifiche della consegna

## Suite riproducibili

`./mvnw -B verify` verifica:

- pacchetto reale di 15 XML, due frammenti e tutti i dodici contenuti dopo generazione e riapertura Word;
- quattro tabelle, celle unite, font, numeri di riga e salti pagina;
- download HTTP del DOCX reale con eXist non raggiungibile;
- ZIP vuoto/non valido, XML malformato, DTD/XXE, nomi non sicuri e markup annidato;
- decompressione limitata, inclusi dati associati a directory ZIP;
- riferimenti incoerenti, ID duplicati, unità mancanti e cicli disconnessi;
- tabulazioni, righe vuote, Unicode supplementare, sigma e diacritici;
- limite di concorrenza e registrazione di un renderer aggiuntivo;
- archiviatore su server REST di prova: byte originali, batch UUID, cleanup, query fallita con HTTP 200 e timeout del corpo;
- frontend e health check.

`npm test` esegue i test del client compatibile e del flusso asincrono: contesto di fetch, stati indipendenti, polling, ripresa della sessione, duplicati, errori, timeout e controlli della view disponibili durante l'elaborazione.

`bash scripts/smoke-container.sh <immagine>` verifica l'immagine finale non root, filesystem in sola lettura, limiti di risorse, interfaccia, readiness e conversione reale con DB non disponibile. Il container di prova viene arrestato al termine.

## Limiti

- Il server REST di prova non è un'istanza eXist completa: query, permessi e URL vanno collaudati sulla versione dell'utente.
- Contenuto e struttura DOCX sono verificati automaticamente. La resa visuale in Word con IFAO-Grec Unicode richiede controllo dedicato.
- Non si dichiara identità grafica col modello o un numero fisso di pagine su contenuti arbitrari.
- Pipeline GitHub remota, push e pubblicazioni non vengono eseguiti durante la consegna.
- I test funzionali non sostituiscono benchmark di produzione o una scansione completa delle vulnerabilità.

I risultati effettivamente eseguiti sono riportati nella consegna e negli artefatti di verifica.

## Risultati del 2 novembre 2026

- Maven Wrapper 3.9.9, compilazione `--release 17`: **19 test Java eseguiti, 0 fallimenti/errori, 0 saltati**; JAR prodotto.
- Node 24.19.0: **6 test JavaScript eseguiti, tutti passati**, nessuno saltato.
- Docker multi-stage: build completata con JDK 17, immagine runtime Temurin JRE 17 per `linux/amd64`.
- Container: utente `10001:10001`, filesystem in sola lettura, 768 MB; health probe eseguita, interfaccia servita e conversione del campione riuscita con eXist intenzionalmente indisponibile.
- Risposta reale: HTTP 200, MIME DOCX, `X-Archive-Status: UNAVAILABLE`, `X-Fragment-Count: 2`; quattro tabelle e dati PHerc. 1025, senza dati dimostrativi.
- Parser YAML e controllo sintattico degli script shell superati. GitHub Actions e push remoti non eseguiti.

## Feature asincrona — 3 novembre 2026

- JDK 17, Maven Wrapper: **34 test Java, zero fallimenti/errori, zero saltati**.
- Frontend: **14 test JavaScript passati**, inclusa l'esecuzione del modulo della view con DOM di test e verifica dei controlli non disabilitati.
- Test del pacchetto reale in modalità job e di uno ZIP superiore a 8 MiB: conversione riuscita; il vecchio endpoint rifiuta invece il pacchetto oltre il suo limite.
- Verificati presa in carico, trasformazione, disponibilità del risultato, saturazione della coda, quota disco, output limitato, eliminazione di file parziali, riavvio e scadenza.
- Trasporto eXist binario su server REST di prova: MIME e byte DOCX, controllo HEAD, URL pubblico, fallback locale e cleanup degli errori.
- Docker: build e smoke test eseguiti anche per 202/polling/download con eXist intenzionalmente irraggiungibile, utente non root e filesystem di sistema in sola lettura.
- Non è stato eseguito un benchmark al limite di 512 MiB, né un collaudo sulla specifica istanza eXist dell'utente. Il confronto grafico in un browser reale e in Word resta distinto dai test di comportamento e OOXML.
