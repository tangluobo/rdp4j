package com.tangluobo.tomato.rdp.clipboard;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.tangluobo.tomato.rdp.Packet;
import com.tangluobo.tomato.rdp.RdesktopException;
import com.tangluobo.tomato.rdp.rdp5.cliprdr.ClipInterface;
import com.tangluobo.tomato.rdp.rdp5.cliprdr.TypeHandler;

/**
 * 修复版 CF_UNICODETEXT 剪贴板格式处理器。
 *
 * 库内 UnicodeHandler 存在严重编码bug：
 * fromTransferable 将 String.getBytes()（平台默认编码，中文Windows为GBK）的
 * 每个字节直接作为UTF-16码元发送，中文"你"(GBK=0xC4,0xE3)会被编码为
 * U+00C4 U+00E3（"Äã"）导致远程粘贴乱码（英文因单字节值相同而碰巧正常）。
 *
 * 本类按MS-RDPECLIP规范使用UTF-16LE编解码，正确支持中文等多字节字符。
 */
public class FixedUnicodeHandler extends TypeHandler {

    private static final Logger logger = Logger.getLogger(FixedUnicodeHandler.class.getName());

    /** CF_UNICODETEXT 标准格式ID */
    private static final int CF_UNICODETEXT = 13;

    @Override
    public boolean formatValid(int format) {
        return format == CF_UNICODETEXT;
    }

    @Override
    public int preferredFormat() {
        return CF_UNICODETEXT;
    }

    @Override
    public String name() {
        return "CF_UNICODETEXT";
    }

    @Override
    public boolean mimeTypeValid(String mimeType) {
        return "text".equals(mimeType);
    }

    /**
     * 本地→远程：将本地剪贴板文本以UTF-16LE编码发送给远程。
     */
    @Override
    public void send_data(Transferable t, ClipInterface clip) throws RdesktopException, java.io.IOException {
        String str = extractString(t);
        if (str == null || str.isEmpty()) {
            return;
        }
        // 统一为CRLF换行（Windows剪贴板规范）
        str = str.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
        byte[] data = str.getBytes(StandardCharsets.UTF_16LE);
        clip.send_data(data, data.length);
    }

    /**
     * 远程→本地：按UTF-16LE解码远程剪贴板数据并写入本地系统剪贴板。
     */
    @Override
    public void handleData(Packet packet, int length, ClipInterface clip) {
        StringBuilder sb = new StringBuilder();
        // length为字节数，每字符2字节，末尾以null(0x0000)终止
        for (int i = 0; i + 1 < length; i += 2) {
            int c = packet.getLittleEndian16();
            if (c == 0) {
                break; // null终止符
            }
            sb.append((char) c);
        }
        try {
            clip.copyToClipboard(new StringSelection(sb.toString()));
        } catch (Exception e) {
            logger.log(Level.WARNING, "写入本地剪贴板失败: " + e.getMessage(), e);
        }
    }

    private String extractString(Transferable t) {
        if (t == null) {
            return null;
        }
        try {
            Object data = t.getTransferData(DataFlavor.stringFlavor);
            if (data instanceof String) {
                return (String) data;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "读取本地剪贴板文本失败: " + e.getMessage(), e);
        }
        return null;
    }
}
