package com.tangluobo.rdp4j.clipboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DeferredFileListTransferableTest {

    @Test
    void exposesFileDropImmediatelyButWaitsForCompletedFiles() throws Exception {
        DeferredFileListTransferable transferable = new DeferredFileListTransferable();
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor));

        CompletableFuture<Object> data = CompletableFuture.supplyAsync(() -> {
            try {
                return transferable.getTransferData(DataFlavor.javaFileListFlavor);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        assertFalse(data.isDone());

        List<File> files = List.of(new File("downloaded.txt"));
        transferable.complete(files);

        assertEquals(files, data.get(2, TimeUnit.SECONDS));
        assertTrue(transferable.isReady());
    }

    @Test
    void cancellationReleasesWaitingClipboardReader() throws Exception {
        DeferredFileListTransferable transferable = new DeferredFileListTransferable();
        CompletableFuture<Object> data = CompletableFuture.supplyAsync(() -> {
            try {
                return transferable.getTransferData(DataFlavor.javaFileListFlavor);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });

        transferable.cancel();

        assertEquals(List.of(), data.get(2, TimeUnit.SECONDS));
    }
}
