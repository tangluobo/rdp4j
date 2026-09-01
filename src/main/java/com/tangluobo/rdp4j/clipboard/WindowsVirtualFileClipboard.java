package com.tangluobo.rdp4j.clipboard;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Publishes remote RDP files as a native Windows Shell virtual-file data
 * object. Explorer consumes each {@code FileContents} entry through an
 * {@code IStream}; this is the path that makes the Microsoft copy UI track
 * the actual remote transfer instead of a later copy of an eager temp file.
 */
final class WindowsVirtualFileClipboard implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(WindowsVirtualFileClipboard.class.getName());

    private static final int S_OK = 0;
    private static final int S_FALSE = 1;
    private static final int E_NOTIMPL = 0x80004001;
    private static final int E_NOINTERFACE = 0x80004002;
    private static final int E_FAIL = 0x80004005;
    private static final int E_INVALIDARG = 0x80070057;
    private static final int STG_E_ACCESSDENIED = 0x80030005;
    private static final int STG_E_INVALIDFUNCTION = 0x80030001;
    private static final int DV_E_FORMATETC = 0x80040064;
    private static final int DV_E_LINDEX = 0x80040068;
    private static final int DV_E_TYMED = 0x80040069;
    private static final int OLE_E_ADVISENOTSUPPORTED = 0x80040003;
    private static final int DATA_S_SAMEFORMATETC = 0x00040130;

    private static final int DATADIR_GET = 1;
    private static final int DVASPECT_CONTENT = 1;
    private static final int TYMED_HGLOBAL = 1;
    private static final int TYMED_ISTREAM = 4;
    private static final int GMEM_MOVEABLE = 0x0002;
    private static final int GMEM_ZEROINIT = 0x0040;
    private static final int WM_QUIT = 0x0012;
    private static final int PM_NOREMOVE = 0;

    private static final int FD_ATTRIBUTES = 0x00000004;
    private static final int FD_FILESIZE = 0x00000040;
    private static final int FD_PROGRESSUI = 0x00004000;
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x80;
    private static final int FILE_DESCRIPTOR_W_SIZE = 592;
    private static final int FILE_NAME_OFFSET = 72;
    private static final int MAX_FILE_NAME_CHARS = 259;
    private static final int DROPEFFECT_COPY = 1;
    private static final int STREAM_COPY_BUFFER = 1024 * 1024;

    private static final GUID IID_IUNKNOWN = new GUID("{00000000-0000-0000-C000-000000000046}");
    private static final GUID IID_IDATAOBJECT = new GUID("{0000010E-0000-0000-C000-000000000046}");
    private static final GUID IID_ISEQUENTIALSTREAM = new GUID("{0C733A30-2A1C-11CE-ADE5-00AA0044773D}");
    private static final GUID IID_ISTREAM = new GUID("{0000000C-0000-0000-C000-000000000046}");

    interface RemoteFileSource {
        byte[] read(int fileIndex, long offset, int length) throws IOException;
    }

    record Entry(String name, long size, boolean directory) {
        Entry {
            name = name == null ? "" : name;
            size = Math.max(0, size);
        }
    }

    private final List<Entry> entries;
    private final RemoteFileSource source;
    private final CompletableFuture<Boolean> published = new CompletableFuture<>();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final Set<VirtualStream> streams = ConcurrentHashMap.newKeySet();
    private final Thread oleThread;

    private volatile int oleThreadId;
    private volatile DataObject dataObject;

    private WindowsVirtualFileClipboard(List<Entry> entries, RemoteFileSource source) {
        this.entries = List.copyOf(entries);
        this.source = Objects.requireNonNull(source, "source");
        oleThread = new Thread(this::runOleClipboard, "rdp-native-file-clipboard");
        oleThread.setDaemon(true);
    }

    static boolean isSupported() {
        return Platform.isWindows();
    }

    static WindowsVirtualFileClipboard publish(List<Entry> entries, RemoteFileSource source) {
        if (!isSupported() || entries == null || entries.isEmpty()) {
            return null;
        }
        WindowsVirtualFileClipboard clipboard = new WindowsVirtualFileClipboard(entries, source);
        clipboard.oleThread.start();
        try {
            if (Boolean.TRUE.equals(clipboard.published.get(5, TimeUnit.SECONDS))) {
                return clipboard;
            }
        } catch (Exception error) {
            logger.log(Level.WARNING, "等待Windows虚拟文件剪贴板失败: " + error.getMessage(), error);
        }
        clipboard.close();
        return null;
    }

    /** True while this native data object still owns the Windows clipboard. */
    boolean ownsClipboard() {
        return published.isDone()
                && Boolean.TRUE.equals(published.getNow(false))
                && !closeRequested.get();
    }

    private void runOleClipboard() {
        HRESULT initialized = NativeApis.OLE32.OleInitialize(null);
        try {
            oleThreadId = NativeApis.KERNEL32.GetCurrentThreadId();
            // Force creation of this STA thread's message queue before another
            // thread can request shutdown through PostThreadMessage.
            NativeApis.USER32.PeekMessage(new MSG(), null, 0, 0, PM_NOREMOVE);

            dataObject = new DataObject();
            HRESULT result = NativeApis.OLE32_EXTRA.OleSetClipboard(dataObject.pointer());
            if (failed(result)) {
                logger.warning("OleSetClipboard失败: 0x" + Integer.toHexString(result.intValue()));
                published.complete(false);
                return;
            }
            // OleSetClipboard AddRef'd the object; release the creator's ref.
            dataObject.releaseReference();
            published.complete(true);
            logger.info("已发布Windows原生虚拟文件剪贴板（" + entries.size() + "项）");

            MSG message = new MSG();
            while (!closeRequested.get()) {
                int status = NativeApis.USER32.GetMessage(message, null, 0, 0);
                if (status <= 0) {
                    break;
                }
                NativeApis.USER32.TranslateMessage(message);
                NativeApis.USER32.DispatchMessage(message);
            }
        } catch (Throwable error) {
            published.complete(false);
            logger.log(Level.WARNING, "Windows虚拟文件剪贴板线程失败: " + error.getMessage(), error);
        } finally {
            try {
                DataObject current = dataObject;
                if (current != null
                        && succeeded(NativeApis.OLE32_EXTRA.OleIsCurrentClipboard(current.pointer()))) {
                    NativeApis.OLE32_EXTRA.OleSetClipboard(null);
                }
            } catch (Throwable error) {
                logger.log(Level.FINE, "清理Windows虚拟文件剪贴板失败", error);
            }
            for (VirtualStream stream : streams) {
                stream.invalidate();
            }
            streams.clear();
            if (succeeded(initialized)) {
                Ole32.INSTANCE.OleUninitialize();
            }
        }
    }

    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) {
            return;
        }
        for (VirtualStream stream : streams) {
            stream.invalidate();
        }
        int threadId = oleThreadId;
        if (threadId != 0) {
            NativeApis.USER32.PostThreadMessage(threadId, WM_QUIT, new WPARAM(), new LPARAM());
        }
    }

    private void wakeOwnerAfterRelease() {
        if (closeRequested.compareAndSet(false, true)) {
            int threadId = oleThreadId;
            if (threadId != 0) {
                NativeApis.USER32.PostThreadMessage(threadId, WM_QUIT, new WPARAM(), new LPARAM());
            }
        }
    }

    private final class DataObject {
        private final AtomicInteger references = new AtomicInteger(1);
        private final DataObjectVTable vtable = new DataObjectVTable();
        private final ComInterface instance = new ComInterface();
        private final int descriptorFormat;
        private final int contentsFormat;
        private final int preferredDropEffectFormat;

        DataObject() {
            descriptorFormat = NativeApis.USER32.RegisterClipboardFormat("FileGroupDescriptorW");
            contentsFormat = NativeApis.USER32.RegisterClipboardFormat("FileContents");
            preferredDropEffectFormat = NativeApis.USER32.RegisterClipboardFormat("Preferred DropEffect");
            initializeVTable();
            vtable.write();
            instance.lpVtbl = vtable.getPointer();
            instance.write();
        }

        Pointer pointer() {
            return instance.getPointer();
        }

        void releaseReference() {
            release(pointer());
        }

        private void initializeVTable() {
            vtable.queryInterface = this::queryInterface;
            vtable.addRef = this::addRef;
            vtable.release = this::release;
            vtable.getData = this::getData;
            vtable.getDataHere = (self, format, medium) -> hr(E_NOTIMPL);
            vtable.queryGetData = this::queryGetData;
            vtable.getCanonicalFormatEtc = this::getCanonicalFormatEtc;
            vtable.setData = (self, format, medium, release) -> hr(E_NOTIMPL);
            vtable.enumFormatEtc = this::enumFormatEtc;
            vtable.dAdvise = (self, format, flags, sink, connection) -> hr(OLE_E_ADVISENOTSUPPORTED);
            vtable.dUnadvise = (self, connection) -> hr(OLE_E_ADVISENOTSUPPORTED);
            vtable.enumDAdvise = (self, enumerator) -> hr(OLE_E_ADVISENOTSUPPORTED);
        }

        private HRESULT queryInterface(Pointer self, Pointer iidPointer, PointerByReference result) {
            if (result == null) {
                return hr(E_INVALIDARG);
            }
            GUID iid = new GUID(iidPointer);
            if (IID_IUNKNOWN.equals(iid) || IID_IDATAOBJECT.equals(iid)) {
                result.setValue(pointer());
                addRef(self);
                return hr(S_OK);
            }
            result.setValue(null);
            return hr(E_NOINTERFACE);
        }

        private int addRef(Pointer self) {
            return references.incrementAndGet();
        }

        private int release(Pointer self) {
            int remaining = references.updateAndGet(value -> Math.max(0, value - 1));
            if (remaining == 0) {
                wakeOwnerAfterRelease();
            }
            return remaining;
        }

        private HRESULT queryGetData(Pointer self, Pointer formatPointer) {
            if (formatPointer == null) {
                return hr(E_INVALIDARG);
            }
            FormatEtc format = new FormatEtc(formatPointer);
            int clipboardFormat = Short.toUnsignedInt(format.cfFormat);
            if (clipboardFormat == descriptorFormat || clipboardFormat == preferredDropEffectFormat) {
                return (format.tymed & TYMED_HGLOBAL) != 0 ? hr(S_OK) : hr(DV_E_TYMED);
            }
            if (clipboardFormat == contentsFormat) {
                if ((format.tymed & TYMED_ISTREAM) == 0) {
                    return hr(DV_E_TYMED);
                }
                if (format.lindex < 0 || format.lindex >= entries.size()
                        || entries.get(format.lindex).directory()) {
                    return hr(DV_E_LINDEX);
                }
                return hr(S_OK);
            }
            return hr(DV_E_FORMATETC);
        }

        private HRESULT getData(Pointer self, Pointer formatPointer, Pointer mediumPointer) {
            HRESULT supported = queryGetData(self, formatPointer);
            if (failed(supported) || mediumPointer == null) {
                return failed(supported) ? supported : hr(E_INVALIDARG);
            }
            try {
                FormatEtc format = new FormatEtc(formatPointer);
                StgMedium medium = new StgMedium(mediumPointer);
                int clipboardFormat = Short.toUnsignedInt(format.cfFormat);
                if (clipboardFormat == descriptorFormat) {
                    medium.tymed = TYMED_HGLOBAL;
                    medium.unionValue = allocateGlobal(buildFileGroupDescriptor(entries));
                    medium.pUnkForRelease = null;
                    medium.write();
                    return hr(S_OK);
                }
                if (clipboardFormat == preferredDropEffectFormat) {
                    byte[] effect = new byte[4];
                    effect[0] = (byte) DROPEFFECT_COPY;
                    medium.tymed = TYMED_HGLOBAL;
                    medium.unionValue = allocateGlobal(effect);
                    medium.pUnkForRelease = null;
                    medium.write();
                    return hr(S_OK);
                }
                Entry entry = entries.get(format.lindex);
                VirtualStream stream = new VirtualStream(format.lindex, entry);
                streams.add(stream);
                medium.tymed = TYMED_ISTREAM;
                medium.unionValue = stream.pointer();
                medium.pUnkForRelease = null;
                medium.write();
                return hr(S_OK);
            } catch (Throwable error) {
                logger.log(Level.WARNING, "提供Windows虚拟文件数据失败: " + error.getMessage(), error);
                return hr(E_FAIL);
            }
        }

        private HRESULT getCanonicalFormatEtc(Pointer self, Pointer input, Pointer output) {
            if (output != null) {
                FormatEtc canonical = new FormatEtc(output);
                canonical.ptd = null;
                canonical.write();
            }
            return hr(DATA_S_SAMEFORMATETC);
        }

        private HRESULT enumFormatEtc(Pointer self, int direction, PointerByReference enumerator) {
            if (direction != DATADIR_GET || enumerator == null) {
                return hr(E_NOTIMPL);
            }
            FormatEtc[] formats = (FormatEtc[]) new FormatEtc().toArray(3);
            populateFormat(formats[0], descriptorFormat, -1, TYMED_HGLOBAL);
            populateFormat(formats[1], contentsFormat, -1, TYMED_ISTREAM);
            populateFormat(formats[2], preferredDropEffectFormat, -1, TYMED_HGLOBAL);
            for (FormatEtc format : formats) {
                format.write();
            }
            return NativeApis.SHELL32.SHCreateStdEnumFmtEtc(
                    formats.length, formats[0].getPointer(), enumerator);
        }

        private void populateFormat(FormatEtc format, int id, int index, int medium) {
            format.cfFormat = (short) id;
            format.ptd = null;
            format.dwAspect = DVASPECT_CONTENT;
            format.lindex = index;
            format.tymed = medium;
        }
    }

    private final class VirtualStream {
        private final int fileIndex;
        private final Entry entry;
        private final AtomicInteger references = new AtomicInteger(1);
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private final StreamVTable vtable = new StreamVTable();
        private final ComInterface instance = new ComInterface();
        private long position;

        VirtualStream(int fileIndex, Entry entry) {
            this.fileIndex = fileIndex;
            this.entry = entry;
            initializeVTable();
            vtable.write();
            instance.lpVtbl = vtable.getPointer();
            instance.write();
        }

        Pointer pointer() {
            return instance.getPointer();
        }

        void invalidate() {
            valid.set(false);
        }

        private void initializeVTable() {
            vtable.queryInterface = this::queryInterface;
            vtable.addRef = this::addRef;
            vtable.release = this::release;
            vtable.read = this::read;
            vtable.write = (self, buffer, count, written) -> hr(STG_E_ACCESSDENIED);
            vtable.seek = this::seek;
            vtable.setSize = (self, size) -> hr(STG_E_ACCESSDENIED);
            vtable.copyTo = this::copyTo;
            vtable.commit = (self, flags) -> hr(S_OK);
            vtable.revert = self -> hr(E_NOTIMPL);
            vtable.lockRegion = (self, offset, count, type) -> hr(STG_E_INVALIDFUNCTION);
            vtable.unlockRegion = (self, offset, count, type) -> hr(STG_E_INVALIDFUNCTION);
            vtable.stat = this::stat;
            vtable.cloneStream = this::cloneStream;
        }

        private HRESULT queryInterface(Pointer self, Pointer iidPointer, PointerByReference result) {
            if (result == null) {
                return hr(E_INVALIDARG);
            }
            GUID iid = new GUID(iidPointer);
            if (IID_IUNKNOWN.equals(iid) || IID_ISEQUENTIALSTREAM.equals(iid) || IID_ISTREAM.equals(iid)) {
                result.setValue(pointer());
                addRef(self);
                return hr(S_OK);
            }
            result.setValue(null);
            return hr(E_NOINTERFACE);
        }

        private int addRef(Pointer self) {
            return references.incrementAndGet();
        }

        private int release(Pointer self) {
            int remaining = references.updateAndGet(value -> Math.max(0, value - 1));
            if (remaining == 0) {
                valid.set(false);
                streams.remove(this);
            }
            return remaining;
        }

        private synchronized HRESULT read(Pointer self, Pointer buffer, int count, Pointer bytesRead) {
            if (bytesRead != null) {
                bytesRead.setInt(0, 0);
            }
            if (!valid.get() || closeRequested.get()) {
                return hr(E_FAIL);
            }
            if (buffer == null && count > 0) {
                return hr(E_INVALIDARG);
            }
            int wanted = (int) Math.min(Math.max(0L, count), Math.max(0L, entry.size() - position));
            if (wanted == 0) {
                return count == 0 ? hr(S_OK) : hr(S_FALSE);
            }
            try {
                byte[] data = source.read(fileIndex, position, wanted);
                int length = Math.min(wanted, data == null ? 0 : data.length);
                if (length > 0) {
                    buffer.write(0, data, 0, length);
                    position += length;
                }
                if (bytesRead != null) {
                    bytesRead.setInt(0, length);
                }
                return length == count ? hr(S_OK) : hr(S_FALSE);
            } catch (IOException error) {
                logger.log(Level.WARNING, "读取远程虚拟文件失败: " + entry.name() + ": " + error.getMessage(), error);
                return hr(E_FAIL);
            }
        }

        private synchronized HRESULT seek(Pointer self, long move, int origin, Pointer newPosition) {
            long base = switch (origin) {
                case 0 -> 0;
                case 1 -> position;
                case 2 -> entry.size();
                default -> Long.MIN_VALUE;
            };
            if (base == Long.MIN_VALUE) {
                return hr(STG_E_INVALIDFUNCTION);
            }
            long target;
            try {
                target = Math.addExact(base, move);
            } catch (ArithmeticException error) {
                return hr(STG_E_INVALIDFUNCTION);
            }
            if (target < 0) {
                return hr(STG_E_INVALIDFUNCTION);
            }
            position = target;
            if (newPosition != null) {
                newPosition.setLong(0, position);
            }
            return hr(S_OK);
        }

        private synchronized HRESULT copyTo(Pointer self, Pointer target, long count,
                                             Pointer bytesRead, Pointer bytesWritten) {
            if (target == null || count < 0 || !valid.get() || closeRequested.get()) {
                return hr(E_INVALIDARG);
            }
            long totalRead = 0;
            long totalWritten = 0;
            try {
                Pointer targetVTable = target.getPointer(0);
                Pointer writeAddress = targetVTable.getPointer(4L * Native.POINTER_SIZE);
                Function writeFunction = Function.getFunction(writeAddress, Function.ALT_CONVENTION);
                int capacity = (int) Math.min(STREAM_COPY_BUFFER,
                        Math.max(1L, Math.min(count, Integer.MAX_VALUE)));
                Memory buffer = new Memory(capacity);
                while (totalRead < count && position < entry.size()) {
                    int wanted = (int) Math.min(capacity,
                            Math.min(count - totalRead, entry.size() - position));
                    byte[] data = source.read(fileIndex, position, wanted);
                    int length = Math.min(wanted, data == null ? 0 : data.length);
                    if (length <= 0) {
                        break;
                    }
                    buffer.write(0, data, 0, length);
                    IntByReference written = new IntByReference();
                    int result = writeFunction.invokeInt(new Object[]{target, buffer, length, written});
                    if (result < 0) {
                        return hr(result);
                    }
                    int accepted = Math.max(0, Math.min(length, written.getValue()));
                    position += accepted;
                    totalRead += accepted;
                    totalWritten += accepted;
                    if (accepted < length) {
                        break;
                    }
                }
                if (bytesRead != null) {
                    bytesRead.setLong(0, totalRead);
                }
                if (bytesWritten != null) {
                    bytesWritten.setLong(0, totalWritten);
                }
                return totalRead == count ? hr(S_OK) : hr(S_FALSE);
            } catch (Throwable error) {
                logger.log(Level.WARNING, "复制远程虚拟文件流失败: " + entry.name(), error);
                return hr(E_FAIL);
            }
        }

        private HRESULT stat(Pointer self, Pointer statPointer, int flags) {
            if (statPointer == null) {
                return hr(E_INVALIDARG);
            }
            StatStg stat = new StatStg(statPointer);
            stat.clear();
            stat.type = 2; // STGTY_STREAM
            stat.cbSize = entry.size();
            stat.grfMode = 0; // STGM_READ
            stat.write();
            return hr(S_OK);
        }

        private synchronized HRESULT cloneStream(Pointer self, PointerByReference clonePointer) {
            if (clonePointer == null) {
                return hr(E_INVALIDARG);
            }
            VirtualStream clone = new VirtualStream(fileIndex, entry);
            clone.position = position;
            streams.add(clone);
            clonePointer.setValue(clone.pointer());
            return hr(S_OK);
        }
    }

    static byte[] buildFileGroupDescriptor(List<Entry> entries) {
        byte[] result = new byte[4 + entries.size() * FILE_DESCRIPTOR_W_SIZE];
        putInt(result, 0, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int offset = 4 + i * FILE_DESCRIPTOR_W_SIZE;
            putInt(result, offset, FD_ATTRIBUTES | FD_FILESIZE | FD_PROGRESSUI);
            putInt(result, offset + 36,
                    entry.directory() ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL);
            long size = entry.directory() ? 0 : entry.size();
            putInt(result, offset + 64, (int) (size >>> 32));
            putInt(result, offset + 68, (int) size);
            String safeName = entry.name().length() > MAX_FILE_NAME_CHARS
                    ? entry.name().substring(0, MAX_FILE_NAME_CHARS) : entry.name();
            for (int c = 0; c < safeName.length(); c++) {
                putShort(result, offset + FILE_NAME_OFFSET + c * 2, safeName.charAt(c));
            }
        }
        return result;
    }

    private static Pointer allocateGlobal(byte[] data) throws IOException {
        Pointer handle = NativeApis.KERNEL32.GlobalAlloc(
                GMEM_MOVEABLE | GMEM_ZEROINIT, new SIZE_T(data.length));
        if (handle == null) {
            throw new IOException("GlobalAlloc失败: " + Native.getLastError());
        }
        Pointer memory = NativeApis.KERNEL32.GlobalLock(handle);
        if (memory == null) {
            NativeApis.KERNEL32.GlobalFree(handle);
            throw new IOException("GlobalLock失败: " + Native.getLastError());
        }
        memory.write(0, data, 0, data.length);
        NativeApis.KERNEL32.GlobalUnlock(handle);
        return handle;
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static void putShort(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }

    private static HRESULT hr(int value) {
        return new HRESULT(value);
    }

    private static boolean succeeded(HRESULT value) {
        return value != null && value.intValue() >= 0;
    }

    private static boolean failed(HRESULT value) {
        return !succeeded(value);
    }

    @Structure.FieldOrder({"lpVtbl"})
    public static class ComInterface extends Structure {
        public Pointer lpVtbl;
    }

    @Structure.FieldOrder({"cfFormat", "ptd", "dwAspect", "lindex", "tymed"})
    public static class FormatEtc extends Structure {
        public short cfFormat;
        public Pointer ptd;
        public int dwAspect;
        public int lindex;
        public int tymed;

        public FormatEtc() {
        }

        public FormatEtc(Pointer pointer) {
            super(pointer);
            read();
        }
    }

    @Structure.FieldOrder({"tymed", "unionValue", "pUnkForRelease"})
    public static class StgMedium extends Structure {
        public int tymed;
        public Pointer unionValue;
        public Pointer pUnkForRelease;

        public StgMedium(Pointer pointer) {
            super(pointer);
            read();
        }
    }

    @Structure.FieldOrder({"pwcsName", "type", "cbSize", "mtime", "ctime", "atime",
            "grfMode", "grfLocksSupported", "clsid", "grfStateBits", "reserved"})
    public static class StatStg extends Structure {
        public Pointer pwcsName;
        public int type;
        public long cbSize;
        public long mtime;
        public long ctime;
        public long atime;
        public int grfMode;
        public int grfLocksSupported;
        public GUID clsid = new GUID();
        public int grfStateBits;
        public int reserved;

        public StatStg(Pointer pointer) {
            super(pointer);
        }
    }

    interface QueryInterfaceCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, Pointer iid, PointerByReference result);
    }

    interface AddRefCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self);
    }

    interface ReleaseCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self);
    }

    interface GetDataCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, Pointer format, Pointer medium);
    }

    interface QueryGetDataCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, Pointer format);
    }

    interface CanonicalFormatCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, Pointer input, Pointer output);
    }

    interface SetDataCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, Pointer format, Pointer medium, int release);
    }

    interface EnumFormatCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, int direction, PointerByReference enumerator);
    }

    interface DAdviseCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, Pointer format, int flags, Pointer sink, Pointer connection);
    }

    interface DUnadviseCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, int connection);
    }

    interface EnumDAdviseCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, PointerByReference enumerator);
    }

    @Structure.FieldOrder({"queryInterface", "addRef", "release", "getData", "getDataHere",
            "queryGetData", "getCanonicalFormatEtc", "setData", "enumFormatEtc", "dAdvise",
            "dUnadvise", "enumDAdvise"})
    public static class DataObjectVTable extends Structure {
        public QueryInterfaceCallback queryInterface;
        public AddRefCallback addRef;
        public ReleaseCallback release;
        public GetDataCallback getData;
        public GetDataCallback getDataHere;
        public QueryGetDataCallback queryGetData;
        public CanonicalFormatCallback getCanonicalFormatEtc;
        public SetDataCallback setData;
        public EnumFormatCallback enumFormatEtc;
        public DAdviseCallback dAdvise;
        public DUnadviseCallback dUnadvise;
        public EnumDAdviseCallback enumDAdvise;
    }

    interface ReadCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, Pointer buffer, int count, Pointer bytesRead);
    }

    interface WriteCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, Pointer buffer, int count, Pointer bytesWritten);
    }

    interface SeekCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, long move, int origin, Pointer newPosition);
    }

    interface SetSizeCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, long size);
    }

    interface CopyToCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, Pointer target, long count, Pointer bytesRead, Pointer bytesWritten);
    }

    interface CommitCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, int flags);
    }

    interface RevertCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self);
    }

    interface RegionCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, long offset, long count, int type);
    }

    interface StatCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, Pointer stat, int flags);
    }

    interface CloneCallback extends StdCallLibrary.StdCallCallback {
        HRESULT invoke(Pointer self, PointerByReference clone);
    }

    @Structure.FieldOrder({"queryInterface", "addRef", "release", "read", "write", "seek",
            "setSize", "copyTo", "commit", "revert", "lockRegion", "unlockRegion", "stat",
            "cloneStream"})
    public static class StreamVTable extends Structure {
        public QueryInterfaceCallback queryInterface;
        public AddRefCallback addRef;
        public ReleaseCallback release;
        public ReadCallback read;
        public WriteCallback write;
        public SeekCallback seek;
        public SetSizeCallback setSize;
        public CopyToCallback copyTo;
        public CommitCallback commit;
        public RevertCallback revert;
        public RegionCallback lockRegion;
        public RegionCallback unlockRegion;
        public StatCallback stat;
        public CloneCallback cloneStream;
    }

    private interface Ole32Extra extends StdCallLibrary {
        HRESULT OleSetClipboard(Pointer dataObject);

        HRESULT OleIsCurrentClipboard(Pointer dataObject);
    }

    private interface Kernel32Extra extends StdCallLibrary {
        Pointer GlobalAlloc(int flags, SIZE_T bytes);

        Pointer GlobalLock(Pointer handle);

        boolean GlobalUnlock(Pointer handle);

        Pointer GlobalFree(Pointer handle);

        int GetCurrentThreadId();
    }

    private interface Shell32Extra extends StdCallLibrary {
        HRESULT SHCreateStdEnumFmtEtc(int count, Pointer formats, PointerByReference enumerator);
    }

    private static final class NativeApis {
        static final Ole32 OLE32 = Ole32.INSTANCE;
        static final Ole32Extra OLE32_EXTRA = Native.load(
                "Ole32", Ole32Extra.class, W32APIOptions.DEFAULT_OPTIONS);
        static final Kernel32Extra KERNEL32 = Native.load(
                "Kernel32", Kernel32Extra.class, W32APIOptions.DEFAULT_OPTIONS);
        static final Shell32Extra SHELL32 = Native.load(
                "Shell32", Shell32Extra.class, W32APIOptions.DEFAULT_OPTIONS);
        static final User32 USER32 = User32.INSTANCE;

        private NativeApis() {
        }
    }
}
