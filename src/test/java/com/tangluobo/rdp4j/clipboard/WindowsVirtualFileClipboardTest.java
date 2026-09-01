package com.tangluobo.rdp4j.clipboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sun.jna.Native;

class WindowsVirtualFileClipboardTest {

    @Test
    void buildsShellDescriptorWithProgressUiFileSizeAndUnicodeName() {
        byte[] descriptor = WindowsVirtualFileClipboard.buildFileGroupDescriptor(List.of(
                new WindowsVirtualFileClipboard.Entry("目录\\测试.bin", 0x1_23456789L, false)));

        assertEquals(596, descriptor.length);
        assertEquals(1, le32(descriptor, 0));
        assertEquals(0x4044, le32(descriptor, 4));
        assertEquals(0x80, le32(descriptor, 40));
        assertEquals(1, le32(descriptor, 68));
        assertEquals(0x23456789, le32(descriptor, 72));
        String name = new String(descriptor, 76, "目录\\测试.bin".length() * 2,
                StandardCharsets.UTF_16LE);
        assertEquals("目录\\测试.bin", name);
    }

    @Test
    void buildsDirectoryDescriptorWithoutFileSize() {
        byte[] descriptor = WindowsVirtualFileClipboard.buildFileGroupDescriptor(List.of(
                new WindowsVirtualFileClipboard.Entry("folder", 999, true)));

        assertEquals(0x10, le32(descriptor, 40));
        assertEquals(0, le32(descriptor, 68));
        assertEquals(0, le32(descriptor, 72));
    }

    @Test
    void nativeComStructuresMatchWindowsAbi() {
        assertEquals(Native.POINTER_SIZE == 8 ? 32 : 20,
                new WindowsVirtualFileClipboard.FormatEtc().size());
        assertEquals(Native.POINTER_SIZE == 8 ? 24 : 12,
                new WindowsVirtualFileClipboard.StgMedium(com.sun.jna.Pointer.NULL).size());
        assertEquals(Native.POINTER_SIZE == 8 ? 80 : 72,
                new WindowsVirtualFileClipboard.StatStg(com.sun.jna.Pointer.NULL).size());
    }

    private static int le32(byte[] value, int offset) {
        return (value[offset] & 0xff)
                | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16)
                | ((value[offset + 3] & 0xff) << 24);
    }
}
