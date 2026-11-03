package com.papyrus.docx;

import com.papyrus.domain.Edition;
import java.io.IOException;
import java.io.OutputStream;

public interface DocumentRenderer {
    String format();
    String mediaType();
    String extension();
    byte[] render(Edition edition) throws IOException;
    default void render(Edition edition, OutputStream output) throws IOException { output.write(render(edition)); }
}
