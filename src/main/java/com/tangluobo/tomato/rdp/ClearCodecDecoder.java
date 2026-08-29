package com.tangluobo.tomato.rdp;

import java.util.Arrays;

/**
 * Stateful, pure-Java ClearCodec decoder used by the RDP graphics pipeline.
 *
 * <p>The sequence number and the glyph/VBar caches belong to the codec
 * context, so one decoder is shared by all graphics surfaces in a session.</p>
 */
final class ClearCodecDecoder {

    private static final int FLAG_GLYPH_INDEX = 0x01;
    private static final int FLAG_GLYPH_HIT = 0x02;
    private static final int FLAG_CACHE_RESET = 0x04;

    private static final int GLYPH_CACHE_SIZE = 4000;
    private static final int VBAR_CACHE_SIZE = 32768;
    private static final int SHORT_VBAR_CACHE_SIZE = 16384;
    private static final int MAX_GLYPH_PIXELS = 1024 * 1024;
    private static final int MAX_VBAR_HEIGHT = 52;

    private final int[][] glyphCache = new int[GLYPH_CACHE_SIZE][];
    private final int[][] vbarCache = new int[VBAR_CACHE_SIZE][];
    private final int[][] shortVbarCache = new int[SHORT_VBAR_CACHE_SIZE][];
    private int expectedSequence = -1;
    private int vbarCursor;
    private int shortVbarCursor;

    void resetSequence() {
        expectedSequence = -1;
    }

    void decode(byte[] data, int offset, int length, int width, int height,
            int[] destination, int destinationWidth, int destinationX, int destinationY)
            throws RdesktopException {
        validateDestination(width, height, destination, destinationWidth,
                destinationX, destinationY);
        Cursor input = new Cursor(data, offset, length, "ClearCodec");
        int flags = input.u8();
        int sequence = input.u8();
        if (expectedSequence < 0) {
            expectedSequence = sequence;
        }
        if (sequence != expectedSequence) {
            throw new RdesktopException("ClearCodec序号不连续: 收到=" + sequence
                    + ", 期望=" + expectedSequence);
        }
        expectedSequence = (sequence + 1) & 0xFF;

        if ((flags & FLAG_CACHE_RESET) != 0) {
            // CACHE_RESET rewinds the replacement cursors. Existing entries
            // remain readable until the server overwrites their slots.
            vbarCursor = 0;
            shortVbarCursor = 0;
        }
        if ((flags & FLAG_GLYPH_HIT) != 0 && (flags & FLAG_GLYPH_INDEX) == 0) {
            throw new RdesktopException("ClearCodec字形命中缺少缓存索引");
        }

        int glyphIndex = -1;
        if ((flags & FLAG_GLYPH_INDEX) != 0) {
            glyphIndex = input.u16();
            if (glyphIndex >= GLYPH_CACHE_SIZE) {
                throw new RdesktopException("ClearCodec字形缓存索引越界: " + glyphIndex);
            }
        }

        if ((flags & FLAG_GLYPH_HIT) != 0) {
            int[] glyph = glyphCache[glyphIndex];
            int pixelCount = checkedPixels(width, height, "ClearCodec字形");
            if (glyph == null || glyph.length < pixelCount) {
                throw new RdesktopException("ClearCodec引用了不存在的字形缓存: " + glyphIndex);
            }
            writeRectangle(glyph, width, height, destination, destinationWidth,
                    destinationX, destinationY);
            if (input.remaining() == 0) {
                return;
            }
        }

        if (input.remaining() < 12) {
            throw new RdesktopException("ClearCodec组合数据头不完整");
        }
        int residualLength = input.length32("ClearCodec残差层");
        int bandsLength = input.length32("ClearCodec条带层");
        int subcodecLength = input.length32("ClearCodec子编码层");
        long compositionLength = (long) residualLength + bandsLength + subcodecLength;
        if (compositionLength != input.remaining()) {
            throw new RdesktopException("ClearCodec组合数据长度不匹配: 声明="
                    + compositionLength + ", 实际=" + input.remaining());
        }

        if (residualLength != 0) {
            decodeResidual(input.section(residualLength, "ClearCodec残差层"), width, height,
                    destination, destinationWidth, destinationX, destinationY);
        }
        if (bandsLength != 0) {
            decodeBands(input.section(bandsLength, "ClearCodec条带层"), width, height,
                    destination, destinationWidth, destinationX, destinationY);
        }
        if (subcodecLength != 0) {
            decodeSubcodecs(input.section(subcodecLength, "ClearCodec子编码层"), width, height,
                    destination, destinationWidth, destinationX, destinationY);
        }
        input.requireFinished();

        if (glyphIndex >= 0 && (flags & FLAG_GLYPH_HIT) == 0) {
            int pixelCount = checkedPixels(width, height, "ClearCodec字形");
            if (pixelCount > MAX_GLYPH_PIXELS) {
                throw new RdesktopException("ClearCodec字形过大: " + width + "x" + height);
            }
            glyphCache[glyphIndex] = snapshotRectangle(destination, destinationWidth,
                    destinationX, destinationY, width, height);
        }
    }

    private static void decodeResidual(Cursor input, int width, int height,
            int[] destination, int destinationWidth, int destinationX, int destinationY)
            throws RdesktopException {
        int pixelCount = checkedPixels(width, height, "ClearCodec残差层");
        int pixelIndex = 0;
        while (input.remaining() > 0) {
            int color = input.bgr();
            long runLength = input.runLength();
            if (runLength > pixelCount - pixelIndex) {
                throw new RdesktopException("ClearCodec残差游程越过目标位图");
            }
            int run = (int) runLength;
            while (run-- > 0) {
                int x = pixelIndex % width;
                int y = pixelIndex / width;
                destination[(destinationY + y) * destinationWidth + destinationX + x] = color;
                pixelIndex++;
            }
        }
        if (pixelIndex != pixelCount) {
            throw new RdesktopException("ClearCodec残差像素数不匹配: " + pixelIndex
                    + "/" + pixelCount);
        }
    }

    private void decodeBands(Cursor input, int width, int height,
            int[] destination, int destinationWidth, int destinationX, int destinationY)
            throws RdesktopException {
        while (input.remaining() > 0) {
            int xStart = input.u16();
            int xEnd = input.u16();
            int yStart = input.u16();
            int yEnd = input.u16();
            int background = input.bgr();
            if (xEnd < xStart || yEnd < yStart || xEnd >= width || yEnd >= height) {
                throw new RdesktopException("ClearCodec条带区域越界: " + xStart + "," + yStart
                        + "-" + xEnd + "," + yEnd + "，位图=" + width + "x" + height);
            }
            int barHeight = yEnd - yStart + 1;
            if (barHeight > MAX_VBAR_HEIGHT) {
                throw new RdesktopException("ClearCodec垂直条高度过大: " + barHeight);
            }

            int barCount = xEnd - xStart + 1;
            for (int column = 0; column < barCount; column++) {
                int header = input.u16();
                int[] bar;
                if ((header & 0xC000) == 0x4000) {
                    int index = header & 0x3FFF;
                    int[] shortBar = shortVbarCache[index];
                    if (shortBar == null) {
                        throw new RdesktopException("ClearCodec引用了不存在的短条缓存: " + index);
                    }
                    int yOn = input.u8();
                    bar = composeBar(background, barHeight, yOn, shortBar);
                    vbarCache[vbarCursor] = bar;
                    vbarCursor = (vbarCursor + 1) % VBAR_CACHE_SIZE;
                } else if ((header & 0xC000) == 0) {
                    int yOn = header & 0xFF;
                    int yOff = (header >>> 8) & 0x3F;
                    if (yOff < yOn) {
                        throw new RdesktopException("ClearCodec短条范围无效: " + yOn + "-" + yOff);
                    }
                    int count = yOff - yOn;
                    if (count > MAX_VBAR_HEIGHT) {
                        throw new RdesktopException("ClearCodec短条像素数过大: " + count);
                    }
                    int[] shortBar = new int[count];
                    for (int i = 0; i < count; i++) {
                        shortBar[i] = input.bgr();
                    }
                    shortVbarCache[shortVbarCursor] = shortBar;
                    shortVbarCursor = (shortVbarCursor + 1) % SHORT_VBAR_CACHE_SIZE;
                    bar = composeBar(background, barHeight, yOn, shortBar);
                    vbarCache[vbarCursor] = bar;
                    vbarCursor = (vbarCursor + 1) % VBAR_CACHE_SIZE;
                } else if ((header & 0x8000) != 0) {
                    int index = header & 0x7FFF;
                    bar = vbarCache[index];
                    if (bar == null) {
                        // Windows can legally hit a zero-initialized entry after
                        // rewinding the replacement cursor.
                        bar = opaqueBlack(barHeight);
                        vbarCache[index] = bar;
                    } else if (bar.length != barHeight) {
                        int[] resized = opaqueBlack(barHeight);
                        System.arraycopy(bar, 0, resized, 0, Math.min(bar.length, resized.length));
                        bar = resized;
                        vbarCache[index] = bar;
                    }
                } else {
                    throw new RdesktopException(String.format(
                            "ClearCodec垂直条头无效: 0x%04x", header));
                }

                int targetX = destinationX + xStart + column;
                int targetY = destinationY + yStart;
                for (int y = 0; y < barHeight; y++) {
                    destination[(targetY + y) * destinationWidth + targetX] = bar[y];
                }
            }
        }
    }

    private static int[] composeBar(int background, int height, int yOn, int[] shortBar) {
        int[] bar = new int[height];
        Arrays.fill(bar, background);
        if (yOn < height && shortBar.length > 0) {
            int sourceOffset = Math.max(0, -yOn);
            int targetOffset = Math.max(0, yOn);
            int count = Math.min(shortBar.length - sourceOffset, height - targetOffset);
            if (count > 0) {
                System.arraycopy(shortBar, sourceOffset, bar, targetOffset, count);
            }
        }
        return bar;
    }

    private static void decodeSubcodecs(Cursor input, int width, int height,
            int[] destination, int destinationWidth, int destinationX, int destinationY)
            throws RdesktopException {
        while (input.remaining() > 0) {
            int x = input.u16();
            int y = input.u16();
            int subWidth = input.u16();
            int subHeight = input.u16();
            int dataLength = input.length32("ClearCodec子编码位图");
            int codecId = input.u8();
            if (subWidth == 0 || subHeight == 0
                    || (long) x + subWidth > width || (long) y + subHeight > height) {
                throw new RdesktopException("ClearCodec子编码区域越界: " + x + "," + y
                        + "+" + subWidth + "x" + subHeight + "，位图=" + width + "x" + height);
            }
            Cursor bitmap = input.section(dataLength, "ClearCodec子编码位图");
            switch (codecId) {
                case 0:
                    decodeRaw(bitmap, subWidth, subHeight, destination, destinationWidth,
                            destinationX + x, destinationY + y);
                    break;
                case 1:
                    decodeNsc(bitmap, subWidth, subHeight, destination, destinationWidth,
                            destinationX + x, destinationY + y);
                    break;
                case 2:
                    decodeRlex(bitmap, subWidth, subHeight, destination, destinationWidth,
                            destinationX + x, destinationY + y);
                    break;
                default:
                    throw new RdesktopException("ClearCodec子编码类型不支持: " + codecId);
            }
            bitmap.requireFinished();
        }
    }

    private static void decodeRaw(Cursor input, int width, int height,
            int[] destination, int destinationWidth, int destinationX, int destinationY)
            throws RdesktopException {
        long expected = (long) width * height * 3;
        if (expected != input.remaining()) {
            throw new RdesktopException("ClearCodec原始子位图长度不匹配: " + input.remaining()
                    + "/" + expected);
        }
        for (int y = 0; y < height; y++) {
            int target = (destinationY + y) * destinationWidth + destinationX;
            for (int x = 0; x < width; x++) {
                destination[target + x] = input.bgr();
            }
        }
    }

    private static void decodeRlex(Cursor input, int width, int height,
            int[] destination, int destinationWidth, int destinationX, int destinationY)
            throws RdesktopException {
        int paletteCount = input.u8();
        if (paletteCount < 1 || paletteCount > 127) {
            throw new RdesktopException("ClearCodec RLEX调色板大小无效: " + paletteCount);
        }
        int[] palette = new int[paletteCount];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = input.bgr();
        }
        int numBits = 32 - Integer.numberOfLeadingZeros(paletteCount - 1);
        if (numBits == 0) numBits = 1;
        int indexMask = (1 << numBits) - 1;
        int depthMask = (1 << (8 - numBits)) - 1;
        int pixelCount = checkedPixels(width, height, "ClearCodec RLEX");
        int pixelIndex = 0;

        while (input.remaining() > 0) {
            int suite = input.u8();
            long runLength = input.runLength();
            int depth = (suite >>> numBits) & depthMask;
            int stop = suite & indexMask;
            int start = stop - depth;
            if (start < 0 || stop >= paletteCount) {
                throw new RdesktopException("ClearCodec RLEX调色板索引越界: "
                        + start + "-" + stop + "/" + paletteCount);
            }
            if (runLength > pixelCount - pixelIndex) {
                throw new RdesktopException("ClearCodec RLEX游程越过目标位图");
            }
            for (long i = 0; i < runLength; i++) {
                writeLinear(destination, destinationWidth, destinationX, destinationY,
                        width, pixelIndex++, palette[start]);
            }
            int suiteLength = depth + 1;
            if (suiteLength > pixelCount - pixelIndex) {
                throw new RdesktopException("ClearCodec RLEX色组越过目标位图");
            }
            for (int paletteIndex = start; paletteIndex <= stop; paletteIndex++) {
                writeLinear(destination, destinationWidth, destinationX, destinationY,
                        width, pixelIndex++, palette[paletteIndex]);
            }
        }
        if (pixelIndex != pixelCount) {
            throw new RdesktopException("ClearCodec RLEX像素数不匹配: " + pixelIndex
                    + "/" + pixelCount);
        }
    }

    private static void decodeNsc(Cursor input, int width, int height,
            int[] destination, int destinationWidth, int destinationX, int destinationY)
            throws RdesktopException {
        if (input.remaining() < 20) {
            throw new RdesktopException("NSCodec数据头不完整");
        }
        int[] planeLengths = new int[4];
        long encodedLength = 0;
        for (int i = 0; i < planeLengths.length; i++) {
            planeLengths[i] = input.length32("NSCodec平面");
            encodedLength += planeLengths[i];
        }
        int colorLossLevel = input.u8();
        int chromaSubsampling = input.u8();
        input.u16(); // reserved
        if (colorLossLevel < 1 || colorLossLevel > 7) {
            throw new RdesktopException("NSCodec颜色损失级别无效: " + colorLossLevel);
        }
        if (chromaSubsampling != 0 && chromaSubsampling != 1) {
            throw new RdesktopException("NSCodec色度抽样级别无效: " + chromaSubsampling);
        }
        if (encodedLength != input.remaining()) {
            throw new RdesktopException("NSCodec平面长度不匹配: 声明=" + encodedLength
                    + ", 实际=" + input.remaining());
        }

        int roundedWidth = (width + 7) & ~7;
        int roundedHeight = (height + 1) & ~1;
        int fullPlaneSize = checkedPixels(width, height, "NSCodec平面");
        int[] originalLengths = { fullPlaneSize, fullPlaneSize, fullPlaneSize, fullPlaneSize };
        if (chromaSubsampling != 0) {
            long yLength = (long) roundedWidth * height;
            long chromaLength = (long) (roundedWidth >>> 1) * (roundedHeight >>> 1);
            if (yLength > Integer.MAX_VALUE || chromaLength > Integer.MAX_VALUE) {
                throw new RdesktopException("NSCodec抽样平面尺寸过大");
            }
            originalLengths[0] = (int) yLength;
            originalLengths[1] = (int) chromaLength;
            originalLengths[2] = (int) chromaLength;
        }

        byte[][] planes = new byte[4][];
        for (int i = 0; i < planes.length; i++) {
            Cursor encoded = input.section(planeLengths[i], "NSCodec平面" + i);
            planes[i] = decodeNscPlane(encoded, planeLengths[i], originalLengths[i]);
        }
        input.requireFinished();

        int shift = colorLossLevel - 1;
        for (int y = 0; y < height; y++) {
            int target = (destinationY + y) * destinationWidth + destinationX;
            int fullRow = y * width;
            int yRow = chromaSubsampling != 0 ? y * roundedWidth : fullRow;
            int chromaRow = chromaSubsampling != 0
                    ? (y >>> 1) * (roundedWidth >>> 1) : fullRow;
            for (int x = 0; x < width; x++) {
                int yValue = planes[0][yRow + x] & 0xFF;
                int chromaX = chromaSubsampling != 0 ? x >>> 1 : x;
                int co = (byte) ((planes[1][chromaRow + chromaX] & 0xFF) << shift);
                int cg = (byte) ((planes[2][chromaRow + chromaX] & 0xFF) << shift);
                int red = clamp8(yValue + co - cg);
                int green = clamp8(yValue + cg);
                int blue = clamp8(yValue - co - cg);
                destination[target + x] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
        }
    }

    private static byte[] decodeNscPlane(Cursor input, int encodedLength, int originalLength)
            throws RdesktopException {
        byte[] output = new byte[originalLength];
        if (encodedLength == 0) {
            Arrays.fill(output, (byte) 0xFF);
            return output;
        }
        if (encodedLength >= originalLength) {
            for (int i = 0; i < originalLength; i++) {
                output[i] = (byte) input.u8();
            }
            return output;
        }

        int outputPosition = 0;
        int left = originalLength;
        while (left > 4) {
            int value = input.u8();
            if (left == 5) {
                output[outputPosition++] = (byte) value;
                left--;
            } else if (input.peekU8() == value) {
                input.u8();
                long runLength = input.u8();
                if (runLength < 0xFF) {
                    runLength += 2;
                } else {
                    runLength = input.u32();
                }
                if (runLength > left || runLength > output.length - outputPosition) {
                    throw new RdesktopException("NSCodec RLE游程越过目标平面");
                }
                Arrays.fill(output, outputPosition, outputPosition + (int) runLength, (byte) value);
                outputPosition += (int) runLength;
                left -= (int) runLength;
            } else {
                output[outputPosition++] = (byte) value;
                left--;
            }
        }
        for (int i = 0; i < 4; i++) {
            output[outputPosition++] = (byte) input.u8();
        }
        if (outputPosition != originalLength) {
            throw new RdesktopException("NSCodec RLE解码长度不匹配: " + outputPosition
                    + "/" + originalLength);
        }
        return output;
    }

    private static int clamp8(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static void writeLinear(int[] destination, int destinationWidth,
            int destinationX, int destinationY, int width, int index, int color) {
        int x = index % width;
        int y = index / width;
        destination[(destinationY + y) * destinationWidth + destinationX + x] = color;
    }

    private static void validateDestination(int width, int height, int[] destination,
            int destinationWidth, int destinationX, int destinationY) throws RdesktopException {
        int pixelCount = checkedPixels(width, height, "ClearCodec目标");
        if (pixelCount == 0 || destination == null || destinationWidth <= 0
                || destination.length % destinationWidth != 0) {
            throw new RdesktopException("ClearCodec目标位图无效");
        }
        int destinationHeight = destination.length / destinationWidth;
        if (destinationX < 0 || destinationY < 0
                || (long) destinationX + width > destinationWidth
                || (long) destinationY + height > destinationHeight) {
            throw new RdesktopException("ClearCodec目标区域越界: " + destinationX + ","
                    + destinationY + "+" + width + "x" + height);
        }
    }

    private static int checkedPixels(int width, int height, String name) throws RdesktopException {
        long count = (long) width * height;
        if (width <= 0 || height <= 0 || count > Integer.MAX_VALUE) {
            throw new RdesktopException(name + "尺寸无效: " + width + "x" + height);
        }
        return (int) count;
    }

    private static int[] snapshotRectangle(int[] source, int sourceWidth,
            int x, int y, int width, int height) {
        int[] result = new int[width * height];
        for (int row = 0; row < height; row++) {
            System.arraycopy(source, (y + row) * sourceWidth + x,
                    result, row * width, width);
        }
        return result;
    }

    private static void writeRectangle(int[] source, int width, int height,
            int[] destination, int destinationWidth, int destinationX, int destinationY) {
        for (int row = 0; row < height; row++) {
            System.arraycopy(source, row * width, destination,
                    (destinationY + row) * destinationWidth + destinationX, width);
        }
    }

    private static int[] opaqueBlack(int length) {
        int[] result = new int[length];
        Arrays.fill(result, 0xFF000000);
        return result;
    }

    private static final class Cursor {
        private final byte[] data;
        private final int end;
        private final String name;
        private int position;

        Cursor(byte[] data, int offset, int length, String name) throws RdesktopException {
            if (data == null || offset < 0 || length < 0 || offset > data.length - length) {
                throw new RdesktopException(name + "数据不完整");
            }
            this.data = data;
            this.position = offset;
            this.end = offset + length;
            this.name = name;
        }

        int remaining() {
            return end - position;
        }

        int u8() throws RdesktopException {
            require(1);
            return data[position++] & 0xFF;
        }

        int peekU8() throws RdesktopException {
            require(1);
            return data[position] & 0xFF;
        }

        int u16() throws RdesktopException {
            require(2);
            int value = (data[position] & 0xFF) | ((data[position + 1] & 0xFF) << 8);
            position += 2;
            return value;
        }

        long u32() throws RdesktopException {
            require(4);
            long value = (data[position] & 0xFFL)
                    | ((data[position + 1] & 0xFFL) << 8)
                    | ((data[position + 2] & 0xFFL) << 16)
                    | ((data[position + 3] & 0xFFL) << 24);
            position += 4;
            return value;
        }

        int bgr() throws RdesktopException {
            int blue = u8();
            int green = u8();
            int red = u8();
            return 0xFF000000 | (red << 16) | (green << 8) | blue;
        }

        long runLength() throws RdesktopException {
            long run = u8();
            if (run == 0xFF) {
                run = u16();
                if (run == 0xFFFF) {
                    run = u32();
                }
            }
            return run;
        }

        int length32(String field) throws RdesktopException {
            long value = u32();
            if (value > Integer.MAX_VALUE) {
                throw new RdesktopException(field + "长度过大: " + value);
            }
            return (int) value;
        }

        Cursor section(int length, String sectionName) throws RdesktopException {
            require(length);
            Cursor section = new Cursor(data, position, length, sectionName);
            position += length;
            return section;
        }

        void requireFinished() throws RdesktopException {
            if (position != end) {
                throw new RdesktopException(name + "含有未解析数据: " + remaining() + "字节");
            }
        }

        private void require(int length) throws RdesktopException {
            if (length < 0 || position > end - length) {
                throw new RdesktopException(name + "数据不完整");
            }
        }
    }
}
