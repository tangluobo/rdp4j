package com.tangluobo.tomato.rdp;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Stateful, pure-Java RDP 8.0 bulk decompressor used by the RDP Graphics
 * Pipeline. The history is shared by consecutive encoded segments in a
 * dynamic-channel instance, as required by MS-RDPEGFX section 3.1.9.1.
 */
final class Rdp8BulkDecompressor {

    private static final int SEGMENTED_SINGLE = 0xE0;
    private static final int SEGMENTED_MULTIPART = 0xE1;
    private static final int COMPRESSION_TYPE_MASK = 0x0F;
    private static final int PACKET_COMPR_TYPE_RDP8 = 0x04;
    private static final int PACKET_COMPRESSED = 0x20;
    private static final int PACKET_AT_FRONT = 0x40;
    private static final int PACKET_FLUSHED = 0x80;

    private static final int HISTORY_SIZE = 2_500_000;
    private static final int MAX_SEGMENT_OUTPUT = 65_535;
    private static final int MAX_SEGMENT_COUNT = 65_535;

    private static final Token[] TOKENS = {
            new Token(1, 0, 8, false, 0),
            new Token(5, 17, 5, true, 0),
            new Token(5, 18, 7, true, 32),
            new Token(5, 19, 9, true, 160),
            new Token(5, 20, 10, true, 672),
            new Token(5, 21, 12, true, 1696),
            new Token(5, 24, 0, false, 0x00),
            new Token(5, 25, 0, false, 0x01),
            new Token(6, 44, 14, true, 5792),
            new Token(6, 45, 15, true, 22176),
            new Token(6, 52, 0, false, 0x02),
            new Token(6, 53, 0, false, 0x03),
            new Token(6, 54, 0, false, 0xFF),
            new Token(7, 92, 18, true, 54944),
            new Token(7, 93, 20, true, 317088),
            new Token(7, 110, 0, false, 0x04),
            new Token(7, 111, 0, false, 0x05),
            new Token(7, 112, 0, false, 0x06),
            new Token(7, 113, 0, false, 0x07),
            new Token(7, 114, 0, false, 0x08),
            new Token(7, 115, 0, false, 0x09),
            new Token(7, 116, 0, false, 0x0A),
            new Token(7, 117, 0, false, 0x0B),
            new Token(7, 118, 0, false, 0x3A),
            new Token(7, 119, 0, false, 0x3B),
            new Token(7, 120, 0, false, 0x3C),
            new Token(7, 121, 0, false, 0x3D),
            new Token(7, 122, 0, false, 0x3E),
            new Token(7, 123, 0, false, 0x3F),
            new Token(7, 124, 0, false, 0x40),
            new Token(7, 125, 0, false, 0x80),
            new Token(8, 188, 20, true, 1365664),
            new Token(8, 189, 21, true, 2414240),
            new Token(8, 252, 0, false, 0x0C),
            new Token(8, 253, 0, false, 0x38),
            new Token(8, 254, 0, false, 0x39),
            new Token(8, 255, 0, false, 0x66),
            new Token(9, 380, 22, true, 4511392),
            new Token(9, 381, 23, true, 8705696),
            new Token(9, 382, 24, true, 17094304)
    };

    private final byte[] history = new byte[HISTORY_SIZE];
    private int historyIndex;
    private int historyValid;

    byte[] decompress(byte[] input) throws RdesktopException {
        if (input == null || input.length < 2) {
            throw new RdesktopException("RDP8批量数据过短");
        }
        int descriptor = input[0] & 0xFF;
        if (descriptor == SEGMENTED_SINGLE) {
            return outputSegment(input, 1, input.length - 1);
        }
        if (descriptor != SEGMENTED_MULTIPART) {
            throw new RdesktopException(String.format("无效的RDP8分段标识: 0x%02x", descriptor));
        }
        if (input.length < 7) {
            throw new RdesktopException("RDP8多段数据头不完整");
        }
        int segmentCount = u16(input, 1);
        long declaredSizeLong = u32(input, 3);
        if (segmentCount <= 0 || segmentCount > MAX_SEGMENT_COUNT) {
            throw new RdesktopException("RDP8分段数量无效: " + segmentCount);
        }
        if (declaredSizeLong > Integer.MAX_VALUE) {
            throw new RdesktopException("RDP8解压后数据过大: " + declaredSizeLong);
        }
        int declaredSize = (int) declaredSizeLong;
        ByteArrayOutputStream output = new ByteArrayOutputStream(declaredSize);
        int position = 7;
        for (int i = 0; i < segmentCount; i++) {
            require(input, position, 4, "RDP8分段长度");
            long segmentLengthLong = u32(input, position);
            position += 4;
            if (segmentLengthLong < 1 || segmentLengthLong > input.length - position) {
                throw new RdesktopException("RDP8分段长度无效: " + segmentLengthLong);
            }
            int segmentLength = (int) segmentLengthLong;
            byte[] segment = outputSegment(input, position, segmentLength);
            if (segment.length > declaredSize - output.size()) {
                throw new RdesktopException("RDP8多段数据超过声明的解压长度");
            }
            output.write(segment, 0, segment.length);
            position += segmentLength;
        }
        if (position != input.length) {
            throw new RdesktopException("RDP8多段数据尾部存在未解析字节: " + (input.length - position));
        }
        if (output.size() != declaredSize) {
            throw new RdesktopException("RDP8解压长度不匹配: 声明=" + declaredSize
                    + ", 实际=" + output.size());
        }
        return output.toByteArray();
    }

    private byte[] outputSegment(byte[] input, int offset, int length) throws RdesktopException {
        require(input, offset, 1, "RDP8数据段头");
        if (length < 1 || length > input.length - offset) {
            throw new RdesktopException("RDP8数据段长度无效: " + length);
        }
        int flags = input[offset] & 0xFF;
        if ((flags & COMPRESSION_TYPE_MASK) != PACKET_COMPR_TYPE_RDP8) {
            throw new RdesktopException("不支持的RDP8压缩类型: " + (flags & COMPRESSION_TYPE_MASK));
        }
        if ((flags & PACKET_FLUSHED) != 0) {
            Arrays.fill(history, (byte) 0);
            historyIndex = 0;
            historyValid = HISTORY_SIZE;
        } else if ((flags & PACKET_AT_FRONT) != 0) {
            historyIndex = 0;
        }
        if ((flags & PACKET_COMPRESSED) == 0) {
            return outputRaw(input, offset + 1, length - 1);
        }
        return outputCompressed(input, offset + 1, length - 1);
    }

    private byte[] outputRaw(byte[] input, int offset, int length) throws RdesktopException {
        if (length > MAX_SEGMENT_OUTPUT) {
            throw new RdesktopException("RDP8未压缩数据段过大: " + length);
        }
        require(input, offset, length, "RDP8未压缩数据段");
        byte[] output = Arrays.copyOfRange(input, offset, offset + length);
        for (byte value : output) {
            appendHistory(value);
        }
        return output;
    }

    private byte[] outputCompressed(byte[] input, int offset, int length) throws RdesktopException {
        if (length < 1) {
            throw new RdesktopException("RDP8压缩数据段缺少尾部位计数");
        }
        int paddingBits = input[offset + length - 1] & 0xFF;
        if (paddingBits > 7) {
            throw new RdesktopException("RDP8尾部无效位数量无效: " + paddingBits);
        }
        BitReader bits = new BitReader(input, offset, length - 1, paddingBits);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(MAX_SEGMENT_OUTPUT, length * 4));
        while (bits.remaining() > 0) {
            Token token = readToken(bits);
            if (!token.match) {
                int literal = token.valueBase + bits.readBits(token.valueBits);
                appendOutput(output, (byte) literal);
                continue;
            }

            int distance = token.valueBase + bits.readBits(token.valueBits);
            if (distance == 0) {
                int rawLength = bits.readBits(15);
                bits.alignToByte();
                for (int i = 0; i < rawLength; i++) {
                    appendOutput(output, (byte) bits.readAlignedByte());
                }
                continue;
            }
            if (distance > HISTORY_SIZE || distance > historyValid) {
                throw new RdesktopException("RDP8回溯距离超出历史窗口: " + distance);
            }
            int count;
            if (bits.readBits(1) == 0) {
                count = 3;
            } else {
                count = 4;
                int extraBits = 2;
                while (bits.readBits(1) == 1) {
                    if (extraBits >= 15) {
                        throw new RdesktopException("RDP8匹配长度编码过大");
                    }
                    count *= 2;
                    extraBits++;
                }
                count += bits.readBits(extraBits);
            }
            int previous = historyIndex + HISTORY_SIZE - distance;
            if (previous >= HISTORY_SIZE) previous -= HISTORY_SIZE;
            for (int i = 0; i < count; i++) {
                byte value = history[previous];
                if (++previous == HISTORY_SIZE) previous = 0;
                appendOutput(output, value);
            }
        }
        return output.toByteArray();
    }

    private static Token readToken(BitReader bits) throws RdesktopException {
        int prefix = 0;
        int haveBits = 0;
        for (Token token : TOKENS) {
            while (haveBits < token.prefixLength) {
                prefix = (prefix << 1) | bits.readBits(1);
                haveBits++;
            }
            if (prefix == token.prefixCode) {
                return token;
            }
        }
        throw new RdesktopException("RDP8压缩流包含未知Huffman令牌");
    }

    private void appendOutput(ByteArrayOutputStream output, byte value) throws RdesktopException {
        if (output.size() >= MAX_SEGMENT_OUTPUT) {
            throw new RdesktopException("RDP8单段解压数据超过65535字节");
        }
        output.write(value);
        appendHistory(value);
    }

    private void appendHistory(byte value) {
        history[historyIndex] = value;
        if (++historyIndex == HISTORY_SIZE) historyIndex = 0;
        if (historyValid < HISTORY_SIZE) historyValid++;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static void require(byte[] data, int offset, int length, String field) throws RdesktopException {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new RdesktopException(field + "数据不完整");
        }
    }

    private static final class Token {
        final int prefixLength;
        final int prefixCode;
        final int valueBits;
        final boolean match;
        final int valueBase;

        Token(int prefixLength, int prefixCode, int valueBits, boolean match, int valueBase) {
            this.prefixLength = prefixLength;
            this.prefixCode = prefixCode;
            this.valueBits = valueBits;
            this.match = match;
            this.valueBase = valueBase;
        }
    }

    private static final class BitReader {
        private final byte[] data;
        private final int endBit;
        private int bitPosition;

        BitReader(byte[] data, int offset, int encodedBytes, int paddingBits) throws RdesktopException {
            this.data = data;
            this.bitPosition = offset * 8;
            long bitCount = (long) encodedBytes * 8L - paddingBits;
            if (bitCount < 0 || bitCount > Integer.MAX_VALUE) {
                throw new RdesktopException("RDP8压缩位流长度无效");
            }
            this.endBit = this.bitPosition + (int) bitCount;
        }

        int remaining() {
            return endBit - bitPosition;
        }

        int readBits(int count) throws RdesktopException {
            if (count < 0 || count > 24 || count > remaining()) {
                throw new RdesktopException("RDP8压缩位流提前结束，需要" + count
                        + "位，剩余" + remaining() + "位");
            }
            int value = 0;
            for (int i = 0; i < count; i++) {
                value = (value << 1)
                        | ((data[bitPosition >>> 3] >>> (7 - (bitPosition & 7))) & 1);
                bitPosition++;
            }
            return value;
        }

        void alignToByte() throws RdesktopException {
            int bufferedBits = bitPosition & 7;
            if (bufferedBits != 0) {
                int discard = 8 - bufferedBits;
                if (discard > remaining()) {
                    throw new RdesktopException("RDP8未编码块字节对齐越界");
                }
                bitPosition += discard;
            }
        }

        int readAlignedByte() throws RdesktopException {
            if ((bitPosition & 7) != 0 || remaining() < 8) {
                throw new RdesktopException("RDP8未编码块数据不完整");
            }
            int value = data[bitPosition >>> 3] & 0xFF;
            bitPosition += 8;
            return value;
        }
    }
}
