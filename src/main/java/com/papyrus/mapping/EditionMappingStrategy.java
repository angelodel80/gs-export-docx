package com.papyrus.mapping;

import com.papyrus.domain.Edition;
import com.papyrus.domain.XmlUnit;
import java.util.List;

public interface EditionMappingStrategy {
    String name();
    Edition map(List<XmlUnit> units, TextConversionStrategy conversion);
    default Edition map(List<XmlUnit> units, TextConversionStrategy conversion, int maxFragments) {
        return map(units, conversion);
    }
}
