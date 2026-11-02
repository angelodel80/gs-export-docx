package com.papyrus.mapping;

import com.papyrus.api.ExportException;
import com.papyrus.config.ExportProperties;
import com.papyrus.domain.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class GsUnitMappingStrategy implements EditionMappingStrategy {
    public static final String SOURCES = "gs-sources";
    public static final String DIPLOMATIC = "gs-diplomatic-transcription";
    public static final String PALEOGRAPHIC = "gs-diplomatic-apparatus";
    public static final String LITERARY = "gs-literary-transcription";
    public static final String PHILOLOGICAL = "gs-literary-apparatus";
    public static final String TRANSLATION = "gs-literary-translation";
    private static final Set<String> REQUIRED = Set.of(SOURCES, DIPLOMATIC, PALEOGRAPHIC, LITERARY, PHILOLOGICAL, TRANSLATION);
    private final ExportProperties limits;

    public GsUnitMappingStrategy(ExportProperties limits) { this.limits = limits; }
    public String name() { return "GS_UNIT"; }

    public Edition map(List<XmlUnit> units, TextConversionStrategy conversion) {
        Map<String, XmlUnit> byId = new LinkedHashMap<>();
        for (var unit : units) {
            if (byId.putIfAbsent(unit.id(), unit) != null) throw ExportException.invalid("ID duplicato: " + unit.id());
        }
        var roots = units.stream().filter(u -> u.target().isEmpty()).toList();
        if (roots.size() != 1) throw ExportException.invalid("Il pacchetto deve avere una sola radice.");
        XmlUnit root = roots.get(0);
        if (root.language() != null) throw ExportException.invalid("La radice deve essere un'unità strutturale.");
        for (var unit : units) {
            if (!unit.target().isEmpty()) {
                XmlUnit parent = byId.get(unit.target());
                if (parent == null || !parent.children().contains(unit.id())) {
                    throw ExportException.invalid("target/children incoerenti per " + unit.id());
                }
            }
            if (new HashSet<>(unit.children()).size() != unit.children().size()) {
                throw ExportException.invalid("Riferimento child duplicato in " + unit.id());
            }
            for (String id : unit.children()) {
                XmlUnit child = byId.get(id);
                if (child == null || !child.target().equals(unit.id())) {
                    throw ExportException.invalid("Figlio mancante o target incoerente: " + id);
                }
            }
            if (unit.language() != null && (!unit.children().isEmpty() || !REQUIRED.contains(unit.language()))) {
                throw ExportException.invalid("Tipo testuale sconosciuto o figli non consentiti in " + unit.id());
            }
        }
        List<Edition.Fragment> fragments = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visit(root, byId, visited, fragments, conversion);
        if (visited.size() != units.size()) throw ExportException.invalid("Unità non raggiungibili dalla radice o ciclo nel pacchetto.");
        if (fragments.isEmpty()) throw ExportException.invalid("Nessun frammento esportabile.");
        return new Edition(root.id(), root.title(), root.creator(), fragments);
    }

    private void visit(XmlUnit unit, Map<String, XmlUnit> byId, Set<String> visited,
            List<Edition.Fragment> fragments, TextConversionStrategy conversion) {
        if (!visited.add(unit.id())) throw ExportException.invalid("Ciclo nella gerarchia: " + unit.id());
        if (unit.children().isEmpty()) throw ExportException.invalid("Unità strutturale senza figli: " + unit.id());
        boolean hasText = unit.children().stream().map(byId::get).anyMatch(u -> u.language() != null);
        if (hasText) {
            Map<String, String> texts = new LinkedHashMap<>();
            for (String id : unit.children()) {
                XmlUnit text = byId.get(id);
                if (text.language() == null) throw ExportException.invalid("Figli strutturali e testuali mescolati in " + unit.id());
                visited.add(id);
                if (texts.putIfAbsent(text.language(), conversion.convert(text.language(), text.content())) != null) {
                    throw ExportException.invalid("Tipo testuale duplicato in " + unit.id() + ": " + text.language());
                }
            }
            if (!texts.keySet().containsAll(REQUIRED)) {
                Set<String> missing = new TreeSet<>(REQUIRED);
                missing.removeAll(texts.keySet());
                throw ExportException.invalid("Unità mancanti in " + unit.id() + ": " + String.join(", ", missing));
            }
            if (fragments.size() >= limits.maxStructuralUnits()) throw ExportException.tooLarge("Troppi frammenti da impaginare.");
            fragments.add(new Edition.Fragment(unit.id(), unit.title(), texts));
        } else {
            for (String id : unit.children()) visit(byId.get(id), byId, visited, fragments, conversion);
        }
    }
}
