# Contratto GS_UNIT

Il mapper riconosce risorse `<unit>` senza namespace. Esempio:

```xml
<unit>
  <id>fragment-1-diplomatic</id>
  <title>Fr. 1</title>
  <creator>Autore || Editore</creator>
  <target>fragment-1</target>
  <children/>
  <dsl>
    <content>  ]ϲιν τ[  
  ]α̣ ϲ[</content>
    <language>gs-diplomatic-transcription</language>
  </dsl>
</unit>
```

I metadati vengono letti eliminando soltanto gli spazi esterni. Il contenuto non viene ripulito: l'indentazione XML dentro `content` è contenuto da esportare. Per `<` o `&` usare escape o CDATA. Markup HTML/TEI annidato viene rifiutato.

Una sola unità ha `target` vuoto: la radice. Le unità strutturali non hanno `dsl` ed elencano i figli nell'ordine editoriale. Ogni figlio dichiara lo stesso padre in `target`. Le unità testuali hanno `dsl` e nessun figlio.

Ogni frammento esportabile deve avere esattamente questi tipi:

| Lingua DSL | Ruolo |
|---|---|
| `gs-sources` | Fonti |
| `gs-diplomatic-transcription` | Trascrizione diplomatica |
| `gs-diplomatic-apparatus` | Apparato paleografico |
| `gs-literary-transcription` | Trascrizione letteraria |
| `gs-literary-apparatus` | Apparato filologico |
| `gs-literary-translation` | Traduzione |

Un contenuto presente può essere vuoto. Un tipo assente è un errore: non si generano sostituzioni o testi dimostrativi.

Sono ammessi livelli intermedi (papiro → gruppo → colonna → sei testi). Un padre non mescola figli strutturali e testuali. Tutte le unità devono essere raggiungibili dalla radice. Ordine ZIP e nomi dei file non determinano l'ordine dell'edizione.

ID: massimo 200 caratteri, `[A-Za-z0-9][A-Za-z0-9._-]{0,199}`, utilizzabile anche come nome sicuro in archivio. Sono rifiutati duplicati, riferimenti mancanti, cicli, tipi sconosciuti e campi obbligatori duplicati.

Il campione PHerc. 1025 contiene 15 risorse: una radice, due frammenti e dodici testi. L'output ha quattro viste tabellari. Sigma lunate, segni combinanti e `U+E5CE` devono rimanere invariati dopo la riapertura del DOCX.
