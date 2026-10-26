package com.cloudcore.api.page;

import com.cloudcore.api.document.UiDocument;

/**
 * Loads one author page that is stored in a single file.
 *
 * <p>The server implementation owns YAML parsing, composition, repeat expansion and
 * validation. This interface keeps addon code independent of the YAML library.
 */
public interface SingleFilePageLoader {

    UiDocument load(String pageId) throws PageLoadException;
}
