package com.tangluobo.rdp4j.clipboard;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Publishes the file-list clipboard flavor immediately while delaying the
 * actual paths until their RDP file-content streams have finished downloading.
 */
final class DeferredFileListTransferable implements Transferable {

    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile List<File> files;

    synchronized void complete(List<File> completedFiles) {
        if (ready.getCount() != 0) {
            files = completedFiles == null ? List.of() : List.copyOf(completedFiles);
            ready.countDown();
        }
    }

    void cancel() {
        complete(List.of());
    }

    boolean isReady() {
        return ready.getCount() == 0;
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return new DataFlavor[] { DataFlavor.javaFileListFlavor };
    }

    @Override
    public boolean isDataFlavorSupported(DataFlavor flavor) {
        return DataFlavor.javaFileListFlavor.equals(flavor);
    }

    @Override
    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
        if (!isDataFlavorSupported(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        try {
            ready.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for remote clipboard files", error);
        }
        return files;
    }
}
