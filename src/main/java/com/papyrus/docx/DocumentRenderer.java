package com.papyrus.docx;

import com.papyrus.domain.Edition;
import java.io.IOException;

public interface DocumentRenderer {
    String format();
    String mediaType();
    String extension();
    byte[] render(Edition edition) throws IOException;
}
