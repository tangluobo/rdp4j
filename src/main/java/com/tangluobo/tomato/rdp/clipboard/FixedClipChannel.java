package com.tangluobo.tomato.rdp.clipboard;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.FocusEvent;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.tangluobo.tomato.rdp.Packet;
import com.tangluobo.tomato.rdp.RdesktopException;
import com.tangluobo.tomato.rdp.rdp5.cliprdr.ClipChannel;
import com.tangluobo.tomato.rdp.rdp5.cliprdr.TextHandler;
import com.tangluobo.tomato.rdp.rdp5.cliprdr.TypeHandler;

/**
 * 修复+增强版剪贴板虚拟通道（cliprdr），基于MS-RDPECLIP规范。
 *
 * 修复javardp库的问题：
 * 1. clipboard字段未初始化：库的标准入口（RdesktopFrame）才会调用
 *    setClipboard(系统剪贴板)，嵌入式使用时clipboard为null，
 *    focusGained→send_format_announce抛NPE，导致本地剪贴板格式
 *    永远无法通告给远程端（远程Ctrl+V无数据可贴）。构造时立即绑定。
 * 2. UnicodeHandler编码bug（中文乱码）：见{@link FixedUnicodeHandler}。
 * 3. 远程→本地格式选择：优先CF_UNICODETEXT，避免TextHandler的Latin-1乱码。
 *
 * 文件双向复制支持（File Contents虚拟流，MS-RDPECLIP 2.2.5）：
 * - 本地→远程：通告"FileGroupDescriptorW"命名格式（带FILECONTENTS标志），
 *   响应服务器的DATA_REQUEST（FILEGROUPDESW文件描述列表）与
 *   FILECONTENTS_REQUEST（SIZE/RANGE读取本地文件内容）。
 * - 远程→本地：收到服务器文件格式通告后拉取FILEGROUPDESW描述列表，
 *   按块FILECONTENTS_REQUEST下载文件到本地临时目录，
 *   完成后以javaFileListFlavor写入系统剪贴板（AWT映射为CF_HDROP，
 *   本地资源管理器可直接Ctrl+V粘贴）。
 */
public class FixedClipChannel extends ClipChannel {

    private static final Logger logger = Logger.getLogger(FixedClipChannel.class.getName());

    /** CLIPRDR消息类型（MS-RDPECLIP 2.2.1） */
    private static final int MSG_MONITOR_READY = 1;
    private static final int MSG_FORMAT_LIST = 2;
    private static final int MSG_FORMAT_ACK = 3;
    private static final int MSG_DATA_REQUEST = 4;
    private static final int MSG_DATA_RESPONSE = 5;
    private static final int MSG_CAPS = 7;
    private static final int MSG_FILECONTENTS_REQUEST = 8;
    private static final int MSG_FILECONTENTS_RESPONSE = 9;

    /** msgFlags：OK应答位 */
    private static final int FLAG_OK = 1;
    /** msgFlags：MONITOR_READY位 */
    private static final int FLAG_MONITOR_READY = 2;
    /** msgFlags：CB_ASCII_NAMES（仅短格式名时表示ANSI编码；长格式名时msgFlags恒为0） */
    private static final int FLAG_ASCII_NAMES = 4;

    /** CAPS generalFlags：CB_USE_LONG_FORMAT_NAMES */
    private static final int GF_LONG_FORMAT_NAMES = 0x02;
    /** CAPS generalFlags：CB_STREAM_FILECLIP_ENABLED（文件流式传输） */
    private static final int GF_STREAM_FILECLIP = 0x04;
    /** CAPS generalFlags：CB_FILECLIP_NO_FILE_PATHS（描述中不带本地路径） */
    private static final int GF_NO_FILE_PATHS = 0x08;

    /** CF_UNICODETEXT 标准格式ID */
    private static final int CF_UNICODETEXT = 13;
    /** CF_TEXT 标准格式ID */
    private static final int CF_TEXT = 1;

    /** 我们通告的注册格式ID：文件描述列表 */
    private static final int FMT_FILE_GROUP_DESC = 0xC001;
    /** 我们通告的注册格式ID：文件内容（非流式回退） */
    private static final int FMT_FILE_CONTENTS = 0xC002;
    /** 我们通告的注册格式ID：拖放效果 */
    private static final int FMT_PREFER_DROP_EFFECT = 0xC003;

    /** DROPEFFECT_COPY | DROPEFFECT_LINK（Preferred DropEffect响应值） */
    private static final int DROPEFFECT_COPY_LINK = 0x5;

    /** FILECONTENTS_REQUEST的dwFlags：请求文件大小 */
    private static final int FC_FLAG_SIZE = 0x1;
    /** FILECONTENTS_REQUEST的dwFlags：请求范围读取 */
    private static final int FC_FLAG_RANGE = 0x2;

    /** FILEDESCRIPTORW的dwFlags位 */
    private static final int FD_ATTRIBUTES = 0x04;
    private static final int FD_WRITESTIME = 0x20;
    private static final int FD_FILESIZE = 0x40;
    /** FILEDESCRIPTORW的dwFlags位：复制时显示进度UI（FreeRDP同样设置） */
    private static final int FD_PROGRESSUI = 0x4000;
    /** 文件/目录属性 */
    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x80;

    /** FILEDESCRIPTORW结构大小（字节） */
    private static final int FILE_DESCRIPTOR_W_SIZE = 592;

    /** 远程→本地下载分块大小 */
    private static final long DOWNLOAD_CHUNK = 1024 * 1024;

    private final FixedUnicodeHandler unicodeHandler = new FixedUnicodeHandler();

    /** 能力协商（CB_CLIP_CAPS）是否已发送 */
    private boolean capsSent;
    /** 服务器CAPS通告的generalFlags（未收到前为-1） */
    private int serverGeneralFlags = -1;
    /** 最近一次process()入参packet的数据起始绝对位置（8字节消息头后） */
    private int lastPacketStart;

    // ===== 本地→远程（服务器拉取本地文件）=====
    /** 本地复制的文件（展开目录树后的扁平列表），通告给远程端 */
    private List<FlatLocalFile> announcedFiles;

    // ===== 远程→本地（下载远程文件到本地剪贴板）=====
    /** 服务器通告的FileGroupDescriptorW格式ID */
    private int remoteFileFormatId = -1;
    /** 是否正在等待服务器的FILEGROUPDESW描述列表响应 */
    private boolean descPending;
    /** 解析出的远程文件条目 */
    private List<RemoteFileEntry> remoteEntries;
    /** 下载根临时目录 */
    private File downloadRoot;
    /** 当前下载的文件索引/偏移/总大小 */
    private int downloadIdx;
    private long downloadOff;
    private long downloadTotal;
    /** 当前下载输出流与目标文件 */
    private OutputStream downloadOut;
    private File downloadFile;
    /** 在途FILECONTENTS_REQUEST的streamId */
    private int pendingStreamId;
    /** streamId序列 */
    private int streamSeq;
    /** 下载完成待放入剪贴板的文件 */
    private List<File> downloadedFiles;

    public FixedClipChannel() {
        super();
        // 修复1：立即绑定系统剪贴板（原库在嵌入式场景下为null导致通告NPE）
        setClipboard(Toolkit.getDefaultToolkit().getSystemClipboard());
        // 修复2：替换库内bug版UnicodeHandler
        replaceUnicodeHandler();
    }

    /**
     * 反射替换allHandlers中的UnicodeHandler为修复版（保持原注册位置）。
     * allHandlers为package-private字段，TypeHandlerList.handlers为private字段。
     */
    @SuppressWarnings("unchecked")
    private void replaceUnicodeHandler() {
        try {
            Field allField = ClipChannel.class.getDeclaredField("allHandlers");
            allField.setAccessible(true);
            Object allHandlers = allField.get(this);
            if (allHandlers == null) {
                logger.warning("allHandlers未初始化，跳过UnicodeHandler替换");
                return;
            }
            Field listField = allHandlers.getClass().getDeclaredField("handlers");
            listField.setAccessible(true);
            List<TypeHandler> handlers = (List<TypeHandler>) listField.get(allHandlers);
            List<TypeHandler> replaced = new ArrayList<>();
            boolean replacedFlag = false;
            for (TypeHandler h : handlers) {
                if (h instanceof com.tangluobo.tomato.rdp.rdp5.cliprdr.UnicodeHandler) {
                    replaced.add(unicodeHandler);
                    replacedFlag = true;
                } else {
                    replaced.add(h);
                }
            }
            if (replacedFlag) {
                handlers.clear();
                handlers.addAll(replaced);
                logger.info("UnicodeHandler已替换为UTF-16LE修复版");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "替换UnicodeHandler失败: " + e.getMessage(), e);
        }
    }

    // =====================================================================
    // 消息分发
    // =====================================================================

    @Override
    public void process(Packet packet) throws RdesktopException, java.io.IOException {
        sendCapsIfNeeded();
        lastPacketStart = packet.getPosition();
        int msgType = packet.getLittleEndian16();
        int msgFlags = packet.getLittleEndian16();
        int dataLen = packet.getLittleEndian32();

        if (msgType == MSG_FORMAT_ACK && (msgFlags & FLAG_MONITOR_READY) != 0) {
            // 服务器监视器就绪：发送初始格式通告（本地剪贴板格式告知远程）
            announceLocalFormats();
            return;
        }

        switch (msgType) {
            case MSG_MONITOR_READY:
                announceLocalFormats();
                break;
            case MSG_FORMAT_LIST:
                handleFormatAnnounce(packet, dataLen, msgFlags);
                break;
            case MSG_DATA_REQUEST:
                int fmtId = packet.getLittleEndian32();
                if (announcedFiles != null && fmtId == FMT_FILE_GROUP_DESC) {
                    // 服务器请求本地文件描述列表（远程粘贴时）
                    sendFileGroupDescriptor();
                } else if (announcedFiles != null && fmtId == FMT_FILE_CONTENTS) {
                    // 老服务器非流式回退：请求整个文件内容（lindex在msgFlags高16位）
                    sendWholeFileContents(msgFlags >>> 16);
                } else if (announcedFiles != null && fmtId == FMT_PREFER_DROP_EFFECT) {
                    sendDropEffect();
                } else {
                    // 回退position后走库原文本路径
                    packet.setPosition(lastPacketStart + 8);
                    invokePrivate("handle_data_request", packet);
                }
                break;
            case MSG_DATA_RESPONSE:
                if (descPending) {
                    // 服务器返回FILEGROUPDESW文件描述列表
                    parseFileGroupDescriptor(packet, dataLen);
                } else {
                    packet.setPosition(lastPacketStart + 8);
                    invokePrivate("handle_data_response", packet, dataLen);
                }
                break;
            case MSG_FILECONTENTS_REQUEST:
                // 服务器拉取本地文件内容（远程粘贴时）
                handleFileContentsRequest(packet);
                break;
            case MSG_FILECONTENTS_RESPONSE:
                // 远程文件数据块到达（远程→本地下载中）
                handleFileContentsResponse(packet, dataLen);
                break;
            case MSG_CAPS:
                // 解析服务器能力通告：记录generalFlags用于长/短格式名协商
                handleServerCaps(packet, dataLen);
                break;
            default:
                // FORMAT_ACK / TEMP_DIRECTORY等：忽略（与库行为一致）
                break;
        }
    }

    /**
     * 解析服务器CB_CLIP_CAPS：提取generalFlags。
     * 长/短格式名模式由双方CAPS协商决定（我的CAPS固定含
     * CB_USE_LONG_FORMAT_NAMES），服务器通告含该标志即启用长格式名解析，
     * 否则回退短格式名（36字节项，兼容老服务器）。
     */
    private void handleServerCaps(Packet packet, int dataLen) {
        try {
            if (dataLen < 4) {
                return;
            }
            int count = packet.getLittleEndian16();
            packet.getLittleEndian16(); // pad
            int remaining = dataLen - 4;
            for (int i = 0; i < count && remaining >= 4; i++) {
                int capType = packet.getLittleEndian16();
                int capLen = packet.getLittleEndian16();
                remaining -= 4;
                if (capLen < 4 || capLen > remaining + 4) {
                    break;
                }
                if (capType == 1 && capLen >= 12) {
                    // CB_CAPSTYPE_GENERAL：version(4) + generalFlags(4)
                    packet.getLittleEndian32(); // version
                    serverGeneralFlags = packet.getLittleEndian32();
                    logger.info("服务器cliprdr能力: generalFlags=0x" + Integer.toHexString(serverGeneralFlags));
                    remaining -= 8;
                } else {
                    packet.incrementPosition(capLen - 4);
                    remaining -= capLen - 4;
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "解析服务器cliprdr能力失败: " + e.getMessage(), e);
        }
    }

    /**
     * 是否按长格式名解析服务器通告（双方CAPS均含CB_USE_LONG_FORMAT_NAMES）。
     */
    private boolean isLongFormatNames() {
        return serverGeneralFlags >= 0 && (serverGeneralFlags & GF_LONG_FORMAT_NAMES) != 0;
    }

    @Override
    public void focusGained(FocusEvent e) {
        try {
            if (state != null && state.isRDP5()) {
                sendCapsIfNeeded();
                announceLocalFormats();
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "剪贴板焦点同步失败: " + ex.getMessage(), ex);
        }
    }

    // =====================================================================
    // 能力协商与格式通告（本地→远程入口）
    // =====================================================================

    /**
     * 发送CB_CLIP_CAPS能力协商（仅一次）。
     * generalFlags设置：
     * - CB_USE_LONG_FORMAT_NAMES(0x02)：格式列表使用变长UTF-16长格式名
     * - CB_STREAM_FILECLIP_ENABLED(0x04)：启用文件流式传输
     * - CB_FILECLIP_NO_FILE_PATHS(0x08)：文件描述不带本地绝对路径
     */
    private void sendCapsIfNeeded() {
        if (capsSent) {
            return;
        }
        capsSent = true;
        try {
            Packet p = new Packet(8 + 16);
            p.setLittleEndian16(MSG_CAPS);
            p.setLittleEndian16(0);
            p.setLittleEndian32(16);
            p.setLittleEndian16(1);   // cCapabilitiesSets
            p.setLittleEndian16(0);   // pad
            p.setLittleEndian16(1);   // capSetType = CB_CAPSTYPE_GENERAL
            p.setLittleEndian16(12); // lengthCapability
            p.setLittleEndian32(2);  // version
            p.setLittleEndian32(GF_LONG_FORMAT_NAMES | GF_STREAM_FILECLIP | GF_NO_FILE_PATHS);
            p.markEnd();
            send_packet(p);
            logger.fine("已发送cliprdr能力协商（长格式名+文件流）");
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送cliprdr能力协商失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通告本地剪贴板格式给远程端（长格式名编码）。
     *
     * msgFlags恒为0：0x0004在FORMAT_LIST中是CB_ASCII_NAMES（短格式名的ANSI编码标志），
     * 长格式名由CAPS协商决定，msgFlags必须为0（FreeRDP的
     * cliprdr_packet_format_list_new同样固定传0）。
     *
     * - 文本：CF_UNICODETEXT(13)
     * - 文件：注册格式"FileGroupDescriptorW"(0xC001) + "FileContents"(0xC002)
     *   + "Preferred DropEffect"(0xC003)
     *   服务器粘贴时优先DATA_REQUEST描述列表+FILECONTENTS_REQUEST流式拉取；
     *   老服务器回退DATA_REQUEST(FileContents)整文件拉取。
     */
    private void announceLocalFormats() {
        try {
            Clipboard clip = getClipboard();
            Transferable t = clip == null ? null : clip.getContents(this);
            boolean hasText = t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor);

            announcedFiles = null;
            if (t != null && t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    List<FlatLocalFile> flat = flattenFiles(files);
                    if (!flat.isEmpty()) {
                        announcedFiles = flat;
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "读取本地剪贴板文件列表失败: " + e.getMessage(), e);
                }
            }
            boolean hasFiles = announcedFiles != null;

            // 长格式名：每项 formatId(4) + UTF-16LE null-terminated名字
            int payload = 0;
            if (hasText) {
                payload += 4 + 2; // 空名（仅null终止符）
            }
            if (hasFiles) {
                payload += 4 + (20 + 1) * 2;   // "FileGroupDescriptorW"
                payload += 4 + (12 + 1) * 2;   // "FileContents"
                payload += 4 + (20 + 1) * 2;   // "Preferred DropEffect"
            }
            if (payload == 0) {
                // 空剪贴板：发送空格式列表清空远程剪贴板
                payload = 0;
            }
            Packet p = new Packet(8 + payload);
            p.setLittleEndian16(MSG_FORMAT_LIST);
            p.setLittleEndian16(0);
            p.setLittleEndian32(payload);
            if (hasText) {
                p.setLittleEndian32(CF_UNICODETEXT);
                p.setLittleEndian16(0);
            }
            if (hasFiles) {
                writeLongFormatName(p, FMT_FILE_GROUP_DESC, "FileGroupDescriptorW");
                writeLongFormatName(p, FMT_FILE_CONTENTS, "FileContents");
                writeLongFormatName(p, FMT_PREFER_DROP_EFFECT, "Preferred DropEffect");
            }
            p.markEnd();
            send_packet(p);
            if (hasFiles) {
                logger.info("已通告本地剪贴板文件（" + announcedFiles.size() + "项，长格式名）");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "通告本地剪贴板格式失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入长格式名格式项：formatId(4) + UTF-16LE null-terminated名字。
     */
    private void writeLongFormatName(Packet p, int formatId, String name) {
        p.setLittleEndian32(formatId);
        for (int i = 0; i < name.length(); i++) {
            p.setLittleEndian16(name.charAt(i));
        }
        p.setLittleEndian16(0);
    }

    /**
     * 递归展开文件列表为扁平描述列表（目录在前、子项带相对路径）。
     * relPath用于FILEGROUPDESW的cFileName，远程粘贴时保留目录结构。
     */
    private List<FlatLocalFile> flattenFiles(List<File> files) {
        List<FlatLocalFile> out = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                flattenInto(f, f.getName(), out);
            }
        }
        return out;
    }

    private void flattenInto(File f, String relPath, List<FlatLocalFile> out) {
        boolean dir = f.isDirectory();
        out.add(new FlatLocalFile(f, relPath, dir, dir ? 0 : f.length()));
        if (dir) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    flattenInto(c, relPath + "\\" + c.getName(), out);
                }
            }
        }
    }

    // ======================================================================
    // 本地→远程：响应服务器对本地文件的请求
    // ======================================================================

    /**
     * 响应服务器的DATA_REQUEST(0xC001)：发送FILEGROUPDESW结构
     * （文件描述列表：相对路径、大小、时间戳、目录/文件属性）。
     */
    private void sendFileGroupDescriptor() {
        try {
            List<FlatLocalFile> files = announcedFiles;
            if (files == null) {
                send_null(MSG_DATA_RESPONSE, FLAG_OK);
                return;
            }
            int dataLen = 4 + files.size() * FILE_DESCRIPTOR_W_SIZE;
            Packet p = new Packet(8 + dataLen);
            p.setLittleEndian16(MSG_DATA_RESPONSE);
            p.setLittleEndian16(FLAG_OK);
            p.setLittleEndian32(dataLen);
            p.setLittleEndian32(files.size());
            for (FlatLocalFile f : files) {
                writeFileDescriptorW(p, f);
            }
            p.markEnd();
            send_packet(p);
        } catch (Exception e) {
            logger.log(Level.WARNING, "发送文件描述列表失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入单个FILEDESCRIPTORW结构（592字节，MS-RDPECLIP 2.2.5.2.3.1）。
     * 标志组合与FreeRDP的convert_local_file_to_filedescriptor对齐：
     * FD_ATTRIBUTES|FD_FILESIZE|FD_WRITESTIME|FD_PROGRESSUI，
     * 目录用FILE_ATTRIBUTE_DIRECTORY（大小0），文件用FILE_ATTRIBUTE_NORMAL。
     */
    private void writeFileDescriptorW(Packet p, FlatLocalFile f) throws IOException {
        p.setLittleEndian32(FD_ATTRIBUTES | FD_FILESIZE | FD_WRITESTIME | FD_PROGRESSUI);
        // clsid(16) + sizel(8) + pointl(8) = 8个0
        for (int i = 0; i < 8; i++) {
            p.setLittleEndian32(0);
        }
        p.setLittleEndian32(f.dir ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL);
        // ftCreationTime / ftLastAccessTime / ftLastWriteTime（各8字节）
        p.setLittleEndian64(0);
        p.setLittleEndian64(0);
        p.setLittleEndian64(toFileTime(f.file.lastModified()));
        // nFileSizeHigh / nFileSizeLow（目录为0）
        long size = f.dir ? 0 : f.size;
        p.setLittleEndian32((int) (size >>> 32));
        p.setLittleEndian32((int) (size & 0xFFFFFFFFL));
        // cFileName(520字节UTF-16LE，260字符，null填充）
        String name = f.relPath;
        for (int i = 0; i < 260; i++) {
            p.setLittleEndian16(i < name.length() ? name.charAt(i) : 0);
        }
    }

    /**
     * Java纪元毫秒 → Windows FILETIME（1601年起100纳秒单位）。
     */
    private static long toFileTime(long epochMillis) {
        if (epochMillis <= 0) {
            return 0;
        }
        return (epochMillis + 11644473600000L) * 10000L;
    }

    /**
     * 处理服务器的FILECONTENTS_REQUEST(8)：流式拉取本地文件内容。
     *
     * - dwFlags含SIZE(0x1)：响应8字节文件大小
     * - dwFlags含RANGE(0x2)：响应从指定偏移起的cbRequested字节数据
     */
    private void handleFileContentsRequest(Packet packet) {
        try {
            int streamId = packet.getLittleEndian32();
            int listIndex = packet.getLittleEndian32();
            int dwFlags = packet.getLittleEndian32();
            int posLow = packet.getLittleEndian32();
            int posHigh = packet.getLittleEndian32();
            int cbRequested = packet.getLittleEndian32();

            List<FlatLocalFile> files = announcedFiles;
            if (files == null || listIndex < 0 || listIndex >= files.size()) {
                logger.warning("FILECONTENTS_REQUEST索引越界: " + listIndex);
                return;
            }
            FlatLocalFile f = files.get(listIndex);

            if ((dwFlags & FC_FLAG_SIZE) != 0) {
                sendFileContentsSize(streamId, f.dir ? 0 : f.size);
            } else if ((dwFlags & FC_FLAG_RANGE) != 0) {
                long offset = (((long) posHigh) << 32) | (posLow & 0xFFFFFFFFL);
                sendFileContentsRange(streamId, f, offset, cbRequested);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "处理文件内容请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送FILECONTENTS_RESPONSE(9)：文件大小（8字节）。
     * msgFlags必须为CB_RESPONSE_OK(1)，否则服务器视为传输失败
     * （FreeRDP同样在成功时设置CB_RESPONSE_OK）。
     */
    private void sendFileContentsSize(int streamId, long size) throws IOException, RdesktopException {
        Packet p = new Packet(8 + 4 + 8);
        p.setLittleEndian16(MSG_FILECONTENTS_RESPONSE);
        p.setLittleEndian16(FLAG_OK);
        p.setLittleEndian32(12);
        p.setLittleEndian32(streamId);
        p.setLittleEndian64(size);
        p.markEnd();
        send_packet(p);
    }

    /**
     * 发送FILECONTENTS_RESPONSE(9)：文件数据块。
     * 注意：服务器通常一次请求整个文件，大文件会占用较大内存。
     */
    private void sendFileContentsRange(int streamId, FlatLocalFile f, long offset, int cbRequested)
            throws IOException, RdesktopException {
        if (f.dir || cbRequested <= 0) {
            // 目录或空请求：回0字节
            Packet empty = new Packet(8 + 4);
            empty.setLittleEndian16(MSG_FILECONTENTS_RESPONSE);
            empty.setLittleEndian16(FLAG_OK);
            empty.setLittleEndian32(4);
            empty.setLittleEndian32(streamId);
            empty.markEnd();
            send_packet(empty);
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(f.file, "r")) {
            raf.seek(offset);
            byte[] buf = new byte[cbRequested];
            int n = raf.read(buf);
            if (n < 0) {
                n = 0;
            }
            Packet p = new Packet(8 + 4 + n);
            p.setLittleEndian16(MSG_FILECONTENTS_RESPONSE);
            p.setLittleEndian16(FLAG_OK);
            p.setLittleEndian32(4 + n);
            p.setLittleEndian32(streamId);
            p.copyFromByteArray(buf, 0, p.getPosition(), n);
            p.incrementPosition(n);
            p.markEnd();
            send_packet(p);
            logger.fine("发送文件数据块: " + f.relPath + " offset=" + offset + " len=" + n);
        }
    }

    /**
     * 老服务器非流式回退：DATA_REQUEST("FileContents")响应整个文件内容。
     * lindex在msgFlags高16位（DATA_REQUEST分发处提取）。
     */
    private void sendWholeFileContents(int lindex) {
        try {
            List<FlatLocalFile> files = announcedFiles;
            if (files == null || lindex < 0 || lindex >= files.size()) {
                send_null(MSG_DATA_RESPONSE, FLAG_OK);
                return;
            }
            FlatLocalFile f = files.get(lindex);
            if (f.dir) {
                send_null(MSG_DATA_RESPONSE, FLAG_OK);
                return;
            }
            byte[] data = java.nio.file.Files.readAllBytes(f.file.toPath());
            Packet p = new Packet(8 + data.length);
            p.setLittleEndian16(MSG_DATA_RESPONSE);
            p.setLittleEndian16(FLAG_OK);
            p.setLittleEndian32(data.length);
            p.copyFromByteArray(data, 0, p.getPosition(), data.length);
            p.incrementPosition(data.length);
            p.markEnd();
            send_packet(p);
            logger.info("非流式响应文件内容: " + f.relPath + " (" + data.length + "字节)");
        } catch (Exception e) {
            logger.log(Level.WARNING, "非流式响应文件内容失败: " + e.getMessage(), e);
        }
    }

    /**
     * 响应DATA_REQUEST("Preferred DropEffect")：返回DROPEFFECT_COPY|LINK。
     */
    private void sendDropEffect() {
        try {
            Packet p = new Packet(8 + 4);
            p.setLittleEndian16(MSG_DATA_RESPONSE);
            p.setLittleEndian16(FLAG_OK);
            p.setLittleEndian32(4);
            p.setLittleEndian32(DROPEFFECT_COPY_LINK);
            p.markEnd();
            send_packet(p);
        } catch (Exception e) {
            logger.log(Level.WARNING, "响应DropEffect失败: " + e.getMessage(), e);
        }
    }

    // ======================================================================
    // 远程→本地：下载远程文件到本地剪贴板
    // ======================================================================

    /**
     * 远端剪贴板格式通告处理（远程Ctrl+C后触发）。
     *
     * 长/短格式名由CAPS协商决定（isLongFormatNames）：
     * - 长格式名：每项 formatId(4) + UTF-16LE null-terminated名（变长）
     * - 短格式名：每项36字节 formatId(4)+名(32)；
     *   msgFlags带CB_ASCII_NAMES(0x04)时为ANSI编码，否则为UTF-16编码
     *   （16字符，Windows服务器常无null终止，与FreeRDP处理一致）
     *
     * 检测"FileGroupDescriptorW"命名格式 → 发起文件下载流程；
     * 无文件格式时保持文本逻辑：优先CF_UNICODETEXT，回退CF_TEXT。
     */
    private void handleFormatAnnounce(Packet packet, int dataLen, int msgFlags) {
        try {
            List<Integer> formats = new ArrayList<>();
            int fileFormatId = -1;
            int len = dataLen;
            if (isLongFormatNames()) {
                // 长格式名：formatId(4) + UTF-16LE null-terminated
                while (len >= 4) {
                    int id = packet.getLittleEndian32();
                    len -= 4;
                    StringBuilder name = new StringBuilder();
                    while (len >= 2) {
                        int ch = packet.getLittleEndian16();
                        len -= 2;
                        if (ch == 0) {
                            break;
                        }
                        name.append((char) ch);
                    }
                    if (name.length() > 0 && name.toString().toLowerCase().contains("filegroupdescriptorw")) {
                        fileFormatId = id;
                    } else {
                        formats.add(id);
                    }
                }
            } else {
                // 短格式名：每项36字节 formatId(4)+名(32)
                boolean asciiNames = (msgFlags & FLAG_ASCII_NAMES) != 0;
                while (len >= 36) {
                    int id = packet.getLittleEndian32();
                    StringBuilder name = new StringBuilder();
                    if (asciiNames) {
                        // ANSI编码：读字节直到null
                        for (int i = 0; i < 32; i++) {
                            int b = packet.get8();
                            if (b == 0) {
                                packet.incrementPosition(31 - i);
                                break;
                            }
                            name.append((char) (b & 0xFF));
                        }
                    } else {
                        // UTF-16编码：16个wchar（可能无null终止）
                        for (int i = 0; i < 16; i++) {
                            int ch = packet.getLittleEndian16();
                            if (ch == 0) {
                                break;
                            }
                            name.append((char) ch);
                        }
                    }
                    if (name.length() > 0 && name.toString().toLowerCase().contains("filegroupdescriptorw")) {
                        fileFormatId = id;
                    } else {
                        formats.add(id);
                    }
                    len -= 36;
                }
            }
            // 回复FORMAT_ACK
            send_null(MSG_FORMAT_ACK, FLAG_OK);
            // 新通告到达：作废进行中的下载
            cancelRemoteDownload();

            if (fileFormatId >= 0) {
                // 文件剪贴板：拉取FILEGROUPDESW描述列表
                remoteFileFormatId = fileFormatId;
                descPending = true;
                sendFormatDataRequest(fileFormatId);
                logger.info("远程剪贴板包含文件，开始拉取文件描述列表");
            } else {
                // 文本路径（原逻辑）：优先Unicode
                TypeHandler chosen = null;
                if (formats.contains(CF_UNICODETEXT)) {
                    chosen = unicodeHandler;
                } else if (formats.contains(CF_TEXT)) {
                    chosen = new TextHandler();
                }
                if (chosen != null) {
                    setField("currentHandler", chosen);
                    invokePrivate("request_clipboard_data", chosen.preferredFormat());
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "处理远程剪贴板格式通告失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送DATA_REQUEST(4)：请求远程FileGroupDescriptorW格式数据。
     */
    private void sendFormatDataRequest(int formatId) throws IOException, RdesktopException {
        Packet p = new Packet(8 + 4);
        p.setLittleEndian16(MSG_DATA_REQUEST);
        p.setLittleEndian16(0);
        p.setLittleEndian32(4);
        p.setLittleEndian32(formatId);
        p.markEnd();
        send_packet(p);
    }

    /**
     * 解析服务器DATA_RESPONSE返回的FILEGROUPDESW结构，开始下载文件。
     */
    private void parseFileGroupDescriptor(Packet packet, int dataLen) {
        descPending = false;
        try {
            if (dataLen < 4) {
                return;
            }
            int n = packet.getLittleEndian32();
            remoteEntries = new ArrayList<>();
            for (int i = 0; i < n && dataLen >= 4 + (i + 1) * FILE_DESCRIPTOR_W_SIZE; i++) {
                // dwFlags(4) + clsid(16) + sizel(8) + pointl(8)
                packet.getLittleEndian32();
                for (int j = 0; j < 8; j++) {
                    packet.getLittleEndian32();
                }
                int attrs = packet.getLittleEndian32();
                // ftCreationTime / ftLastAccessTime / ftLastWriteTime
                packet.getLittleEndian64();
                packet.getLittleEndian64();
                packet.getLittleEndian64();
                long sizeHigh = packet.getLittleEndian32() & 0xFFFFFFFFL;
                long sizeLow = packet.getLittleEndian32() & 0xFFFFFFFFL;
                StringBuilder name = new StringBuilder();
                for (int c = 0; c < 260; c++) {
                    int ch = packet.getLittleEndian16();
                    if (ch == 0) {
                        packet.incrementPosition((259 - c) * 2);
                        break;
                    }
                    name.append((char) ch);
                }
                remoteEntries.add(new RemoteFileEntry(name.toString(),
                        (sizeHigh << 32) | sizeLow, (attrs & FILE_ATTRIBUTE_DIRECTORY) != 0));
            }
            if (remoteEntries.isEmpty()) {
                return;
            }
            logger.info("远程文件描述列表: " + remoteEntries.size() + "项");
            startRemoteDownload();
        } catch (Exception e) {
            logger.log(Level.WARNING, "解析远程文件描述列表失败: " + e.getMessage(), e);
            cancelRemoteDownload();
        }
    }

    /**
     * 开始远程→本地下载：创建临时目录，串行分块拉取。
     */
    private void startRemoteDownload() throws IOException, RdesktopException {
        downloadRoot = Files.createTempDirectory("tomato-cliprdr-").toFile();
        downloadedFiles = new ArrayList<>();
        downloadIdx = 0;
        startNextRemoteFile();
    }

    /**
     * 下载状态机推进：目录直接创建，文件分块拉取。
     * 每次只在途一个FILECONTENTS_REQUEST（响应到达后继续）。
     */
    private void startNextRemoteFile() throws IOException, RdesktopException {
        closeDownloadOut();
        if (remoteEntries == null || downloadIdx >= remoteEntries.size()) {
            finishRemoteDownload();
            return;
        }
        RemoteFileEntry e = remoteEntries.get(downloadIdx);
        File target = new File(downloadRoot, sanitizePath(e.name));
        if (e.dir) {
            target.mkdirs();
            downloadedFiles.add(target);
            downloadIdx++;
            startNextRemoteFile();
            return;
        }
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        downloadFile = target;
        downloadOut = new BufferedOutputStream(new FileOutputStream(target));
        downloadOff = 0;
        downloadTotal = e.size;
        requestNextDownloadChunk();
    }

    /**
     * 发送FILECONTENTS_REQUEST(8)拉取下一块远程文件数据。
     */
    private void requestNextDownloadChunk() throws IOException, RdesktopException {
        long remain = downloadTotal - downloadOff;
        if (remain <= 0) {
            // 当前文件完成
            closeDownloadOut();
            if (downloadFile != null) {
                downloadedFiles.add(downloadFile);
                downloadFile = null;
            }
            downloadIdx++;
            startNextRemoteFile();
            return;
        }
        int cb = (int) Math.min(DOWNLOAD_CHUNK, remain);
        pendingStreamId = ++streamSeq;
        Packet p = new Packet(8 + 24);
        p.setLittleEndian16(MSG_FILECONTENTS_REQUEST);
        p.setLittleEndian16(0);
        p.setLittleEndian32(24);
        p.setLittleEndian32(pendingStreamId);
        p.setLittleEndian32(downloadIdx);
        p.setLittleEndian32(FC_FLAG_RANGE);
        p.setLittleEndian32((int) (downloadOff & 0xFFFFFFFFL));
        p.setLittleEndian32((int) (downloadOff >>> 32));
        p.setLittleEndian32(cb);
        p.markEnd();
        send_packet(p);
    }

    /**
     * 处理FILECONTENTS_RESPONSE(9)：写入数据块并继续拉取。
     */
    private void handleFileContentsResponse(Packet packet, int dataLen) {
        try {
            if (dataLen < 4 || downloadOut == null) {
                return;
            }
            int streamId = packet.getLittleEndian32();
            if (streamId != pendingStreamId) {
                logger.warning("忽略不匹配的文件内容响应: streamId=" + streamId);
                return;
            }
            int n = dataLen - 4;
            if (n > 0) {
                byte[] buf = new byte[n];
                // copyToByteArray(dst, dstOffset, srcOffset, length)：
                // 源偏移=消息头(8)+streamId(4)后的数据起始（相对packet底层缓冲）
                packet.copyToByteArray(buf, 0, lastPacketStart + 12, n);
                downloadOut.write(buf);
                downloadOff += n;
            }
            requestNextDownloadChunk();
        } catch (Exception e) {
            logger.log(Level.WARNING, "处理远程文件数据失败: " + e.getMessage(), e);
            cancelRemoteDownload();
        }
    }

    /**
     * 远程→本地下载完成：文件列表写入系统剪贴板
     * （javaFileListFlavor由AWT映射为CF_HDROP，资源管理器可直接粘贴）。
     */
    private void finishRemoteDownload() {
        try {
            if (downloadedFiles != null && !downloadedFiles.isEmpty()) {
                getClipboard().setContents(new FileListTransferable(downloadedFiles), this);
                logger.info("远程文件已下载到本地剪贴板: " + downloadRoot
                        + " (" + downloadedFiles.size() + "项)");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "写入本地剪贴板文件列表失败: " + e.getMessage(), e);
        } finally {
            closeDownloadOut();
        }
    }

    /**
     * 取消进行中的远程文件下载（新通告到达或出错时）。
     */
    private void cancelRemoteDownload() {
        descPending = false;
        remoteFileFormatId = -1;
        remoteEntries = null;
        pendingStreamId = -1;
        closeDownloadOut();
        downloadFile = null;
        downloadedFiles = null;
    }

    private void closeDownloadOut() {
        if (downloadOut != null) {
            try {
                downloadOut.close();
            } catch (IOException e) {
                // 忽略关闭失败
            }
            downloadOut = null;
        }
    }

    /**
     * 清理远程文件名中的路径穿越风险（去除..与绝对路径部分）。
     */
    private static String sanitizePath(String name) {
        String normalized = name.replace('/', '\\');
        String[] parts = normalized.split("\\\\");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(File.separatorChar);
            }
            sb.append(part);
        }
        return sb.length() > 0 ? sb.toString() : "file";
    }

    // =====================================================================
    // 辅助
    // =====================================================================

    /**
     * 本地剪贴板文件列表Transferable（javaFileListFlavor）。
     */
    private static class FileListTransferable implements Transferable {
        private final List<File> files;

        FileListTransferable(List<File> files) {
            this.files = files;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) {
            return files;
        }
    }

    /** 本地文件的扁平描述（含目录树相对路径） */
    private static class FlatLocalFile {
        final File file;
        final String relPath;
        final boolean dir;
        final long size;

        FlatLocalFile(File file, String relPath, boolean dir, long size) {
            this.file = file;
            this.relPath = relPath;
            this.dir = dir;
            this.size = size;
        }
    }

    /** 远程FILEGROUPDESW解析出的文件条目 */
    private static class RemoteFileEntry {
        final String name;
        final long size;
        final boolean dir;

        RemoteFileEntry(String name, long size, boolean dir) {
            this.name = name;
            this.size = size;
            this.dir = dir;
        }
    }

    /**
     * 反射调用父类package-private方法（按名称和参数个数匹配）。
     * 反射异常统一包装为RdesktopException以匹配process()签名。
     */
    private void invokePrivate(String methodName, Object... args) throws RdesktopException {
        try {
            for (Method m : ClipChannel.class.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    m.invoke(this, args);
                    return;
                }
            }
            throw new NoSuchMethodException(methodName);
        } catch (Exception e) {
            if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
                Throwable cause = e.getCause();
                if (cause instanceof RdesktopException) {
                    throw (RdesktopException) cause;
                }
                if (cause instanceof java.io.IOException) {
                    throw new RdesktopException("剪贴板通道处理失败: " + cause.getMessage(), cause);
                }
            }
            throw new RdesktopException("反射调用剪贴板方法失败: " + methodName + ": " + e.getMessage(), e);
        }
    }

    /**
     * 反射设置父类package-private字段。
     */
    private void setField(String fieldName, Object value) throws Exception {
        Field f = ClipChannel.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(this, value);
    }
}
