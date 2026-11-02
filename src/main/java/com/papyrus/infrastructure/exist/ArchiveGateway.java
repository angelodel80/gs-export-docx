package com.papyrus.infrastructure.exist;

import com.papyrus.domain.XmlUnit;
import java.util.List;

public interface ArchiveGateway {
    enum Status { SKIPPED, NOT_CONFIGURED, ARCHIVED, UNAVAILABLE }
    Status archive(List<XmlUnit> units);
}
