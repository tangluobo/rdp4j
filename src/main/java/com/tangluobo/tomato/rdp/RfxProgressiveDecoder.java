package com.tangluobo.tomato.rdp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java decoder for the full-quality TILE_SIMPLE subset of the RemoteFX
 * Progressive codec (MS-RDPEGFX 2.2.4.2). GNOME Remote Desktop uses this
 * subset when AVC is disabled in the RDPGFX capability set.
 */
final class RfxProgressiveDecoder {

    private static final int TILE_SIZE = 64;
    private static final int COEFFICIENT_COUNT = TILE_SIZE * TILE_SIZE;

    private static final int BLOCK_SYNC = 0xCCC0;
    private static final int BLOCK_FRAME_BEGIN = 0xCCC1;
    private static final int BLOCK_FRAME_END = 0xCCC2;
    private static final int BLOCK_CONTEXT = 0xCCC3;
    private static final int BLOCK_REGION = 0xCCC4;
    private static final int BLOCK_TILE_SIMPLE = 0xCCC5;
    private static final int BLOCK_TILE_FIRST = 0xCCC6;
    private static final int BLOCK_TILE_UPGRADE = 0xCCC7;

    private static final int OFF_HL1 = 0;
    private static final int OFF_LH1 = 1024;
    private static final int OFF_HH1 = 2048;
    private static final int OFF_HL2 = 3072;
    private static final int OFF_LH2 = 3328;
    private static final int OFF_HH2 = 3584;
    private static final int OFF_HL3 = 3840;
    private static final int OFF_LH3 = 3904;
    private static final int OFF_HH3 = 3968;
    private static final int OFF_LL3 = 4032;

    private static final Subband[] STANDARD_SUBBANDS = {
            new Subband(OFF_HL1, 1024, 7),
            new Subband(OFF_LH1, 1024, 8),
            new Subband(OFF_HH1, 1024, 9),
            new Subband(OFF_HL2, 256, 4),
            new Subband(OFF_LH2, 256, 5),
            new Subband(OFF_HH2, 256, 6),
            new Subband(OFF_HL3, 64, 1),
            new Subband(OFF_LH3, 64, 2),
            new Subband(OFF_HH3, 64, 3),
            new Subband(OFF_LL3, 64, 0)
    };

    /* The extrapolate transform has an odd-sized low-pass branch at every
     * level (33/17/9 samples), so its packed subbands use different offsets. */
    private static final int OFF_EXTRAPOLATE_LL3 = 4015;
    private static final Subband[] EXTRAPOLATE_SUBBANDS = {
            new Subband(0, 1023, 7),
            new Subband(1023, 1023, 8),
            new Subband(2046, 961, 9),
            new Subband(3007, 272, 4),
            new Subband(3279, 272, 5),
            new Subband(3551, 256, 6),
            new Subband(3807, 72, 1),
            new Subband(3879, 72, 2),
            new Subband(3951, 64, 3),
            new Subband(OFF_EXTRAPOLATE_LL3, 81, 0)
    };

    private final Map<Long, ProgressiveTileState> tileStates = new HashMap<>();

    List<Region> decode(byte[] data) throws RdesktopException {
        if (data == null || data.length < 6) {
            throw new RdesktopException("RFX Progressive数据过短");
        }
        List<Region> regions = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            require(data, offset, 6, "RFX数据块头");
            int type = u16(data, offset);
            int length = checkedLength(u32(data, offset + 2), "RFX数据块");
            if (length < 6 || length > data.length - offset) {
                throw new RdesktopException("RFX数据块长度无效: " + length);
            }
            int bodyOffset = offset + 6;
            int bodyLength = length - 6;
            switch (type) {
                case BLOCK_SYNC:
                    parseSync(data, bodyOffset, bodyLength);
                    break;
                case BLOCK_CONTEXT:
                    parseContext(data, bodyOffset, bodyLength);
                    break;
                case BLOCK_FRAME_BEGIN:
                case BLOCK_FRAME_END:
                    break;
                case BLOCK_REGION:
                    regions.add(parseRegion(data, bodyOffset, bodyLength));
                    break;
                default:
                    // Unknown extension blocks can be skipped because every block
                    // is length delimited. Unsupported tile types are rejected by
                    // parseRegion, where silently skipping would corrupt a frame.
                    break;
            }
            offset += length;
        }
        return regions;
    }

    private static void parseSync(byte[] data, int offset, int length) throws RdesktopException {
        require(data, offset, Math.min(length, 6), "RFX同步块");
        if (length < 6 || u32(data, offset) != 0xCACCACCAL) {
            throw new RdesktopException("RFX同步标识无效");
        }
    }

    private static void parseContext(byte[] data, int offset, int length) throws RdesktopException {
        require(data, offset, Math.min(length, 4), "RFX上下文块");
        if (length < 4) {
            throw new RdesktopException("RFX上下文块过短");
        }
        int tileSize = data[offset + 1] & 0xFF;
        int flags = u16(data, offset + 2);
        if (tileSize != TILE_SIZE) {
            throw new RdesktopException("不支持的RFX图块尺寸: " + tileSize);
        }
        // DWT extrapolation is selected by the region flag and is supported.
    }

    private Region parseRegion(byte[] data, int offset, int length) throws RdesktopException {
        if (length < 12) {
            throw new RdesktopException("RFX区域头过短");
        }
        require(data, offset, 12, "RFX区域头");
        int end = offset + length;
        int tileSize = data[offset] & 0xFF;
        int rectangleCount = u16(data, offset + 1);
        int quantCount = data[offset + 3] & 0xFF;
        int progressiveQuantCount = data[offset + 4] & 0xFF;
        int flags = data[offset + 5] & 0xFF;
        int tileCount = u16(data, offset + 6);
        long tileDataLength = u32(data, offset + 8);
        if (tileSize != TILE_SIZE) {
            throw new RdesktopException("不支持的RFX区域图块尺寸: " + tileSize);
        }
        boolean extrapolate = (flags & 0x01) != 0;

        int position = offset + 12;
        List<Rectangle> rectangles = new ArrayList<>(rectangleCount);
        for (int i = 0; i < rectangleCount; i++) {
            requireWithin(data, position, 8, end, "RFX更新矩形");
            int x = u16(data, position);
            int y = u16(data, position + 2);
            int width = u16(data, position + 4);
            int height = u16(data, position + 6);
            rectangles.add(new Rectangle(x, y, width, height));
            position += 8;
        }

        int[][] quants = new int[quantCount][];
        for (int i = 0; i < quantCount; i++) {
            requireWithin(data, position, 5, end, "RFX量化表");
            quants[i] = unpackQuantization(data, position);
            position += 5;
        }
        ProgressiveQuant[] progressiveQuants = new ProgressiveQuant[progressiveQuantCount];
        for (int i = 0; i < progressiveQuantCount; i++) {
            requireWithin(data, position, 16, end, "RFX渐进量化表");
            progressiveQuants[i] = new ProgressiveQuant(
                    unpackQuantization(data, position + 1),
                    unpackQuantization(data, position + 6),
                    unpackQuantization(data, position + 11));
            position += 16;
        }

        if (tileDataLength > end - position) {
            throw new RdesktopException("RFX图块数据长度越界: " + tileDataLength);
        }
        int tileDataEnd = position + (int) tileDataLength;
        List<Tile> tiles = new ArrayList<>(tileCount);
        for (int i = 0; i < tileCount; i++) {
            requireWithin(data, position, 6, tileDataEnd, "RFX图块头");
            int tileType = u16(data, position);
            int tileBlockLength = checkedLength(u32(data, position + 2), "RFX图块");
            if (tileBlockLength < 6 || tileBlockLength > tileDataEnd - position) {
                throw new RdesktopException("RFX图块长度无效: " + tileBlockLength);
            }
            switch (tileType) {
                case BLOCK_TILE_SIMPLE:
                    tiles.add(decodeSimpleTile(data, position + 6, tileBlockLength - 6,
                            quants, extrapolate));
                    break;
                case BLOCK_TILE_FIRST:
                    tiles.add(decodeFirstTile(data, position + 6, tileBlockLength - 6,
                            quants, progressiveQuants, extrapolate));
                    break;
                case BLOCK_TILE_UPGRADE:
                    tiles.add(decodeUpgradeTile(data, position + 6, tileBlockLength - 6,
                            quants, progressiveQuants, extrapolate));
                    break;
                default:
                    throw new RdesktopException(String.format(
                            "暂不支持的RFX图块类型: 0x%04x", tileType));
            }
            position += tileBlockLength;
        }
        if (position != tileDataEnd) {
            throw new RdesktopException("RFX区域包含未解析的图块数据: " + (tileDataEnd - position) + "字节");
        }
        return new Region(rectangles, tiles);
    }

    private Tile decodeSimpleTile(byte[] data, int offset, int length, int[][] quants,
            boolean extrapolate)
            throws RdesktopException {
        if (length < 16) {
            throw new RdesktopException("RFX SIMPLE图块头过短");
        }
        require(data, offset, 16, "RFX SIMPLE图块头");
        int end = offset + length;
        int quantY = data[offset] & 0xFF;
        int quantCb = data[offset + 1] & 0xFF;
        int quantCr = data[offset + 2] & 0xFF;
        int tileXIndex = u16(data, offset + 3);
        int tileYIndex = u16(data, offset + 5);
        int flags = data[offset + 7] & 0xFF;
        int yLength = u16(data, offset + 8);
        int cbLength = u16(data, offset + 10);
        int crLength = u16(data, offset + 12);
        int tailLength = u16(data, offset + 14);
        int position = offset + 16;
        int encodedLength = yLength + cbLength + crLength + tailLength;
        requireWithin(data, position, encodedLength, end, "RFX SIMPLE图块数据");
        int[] qY = quantAt(quants, quantY);
        int[] qCb = quantAt(quants, quantCb);
        int[] qCr = quantAt(quants, quantCr);

        short[] y = decodeRlgr1(data, position, yLength);
        position += yLength;
        short[] cb = decodeRlgr1(data, position, cbLength);
        position += cbLength;
        short[] cr = decodeRlgr1(data, position, crLength);
        int[] noProgressiveQuantization = new int[10];
        return decodeInitialTile(tileXIndex, tileYIndex, flags, qY, qCb, qCr,
                noProgressiveQuantization, noProgressiveQuantization, noProgressiveQuantization,
                y, cb, cr, extrapolate);
    }

    private Tile decodeFirstTile(byte[] data, int offset, int length, int[][] quants,
            ProgressiveQuant[] progressiveQuants, boolean extrapolate) throws RdesktopException {
        if (length < 17) {
            throw new RdesktopException("RFX FIRST图块头过短");
        }
        require(data, offset, 17, "RFX FIRST图块头");
        int end = offset + length;
        int[] qY = quantAt(quants, data[offset] & 0xFF);
        int[] qCb = quantAt(quants, data[offset + 1] & 0xFF);
        int[] qCr = quantAt(quants, data[offset + 2] & 0xFF);
        int tileXIndex = u16(data, offset + 3);
        int tileYIndex = u16(data, offset + 5);
        int flags = data[offset + 7] & 0xFF;
        ProgressiveQuant progressive = progressiveQuantAt(progressiveQuants, data[offset + 8] & 0xFF);
        int yLength = u16(data, offset + 9);
        int cbLength = u16(data, offset + 11);
        int crLength = u16(data, offset + 13);
        int tailLength = u16(data, offset + 15);
        int position = offset + 17;
        requireWithin(data, position, yLength + cbLength + crLength + tailLength,
                end, "RFX FIRST图块数据");

        short[] y = decodeRlgr1(data, position, yLength);
        position += yLength;
        short[] cb = decodeRlgr1(data, position, cbLength);
        position += cbLength;
        short[] cr = decodeRlgr1(data, position, crLength);
        return decodeInitialTile(tileXIndex, tileYIndex, flags, qY, qCb, qCr,
                progressive.y, progressive.cb, progressive.cr, y, cb, cr, extrapolate);
    }

    private Tile decodeInitialTile(int tileXIndex, int tileYIndex, int flags,
            int[] qY, int[] qCb, int[] qCr, int[] progressiveY, int[] progressiveCb,
            int[] progressiveCr, short[] encodedY, short[] encodedCb, short[] encodedCr,
            boolean extrapolate) throws RdesktopException {
        short[] signY = encodedY.clone();
        short[] signCb = encodedCb.clone();
        short[] signCr = encodedCr.clone();
        short[] currentY = decodeInitialComponent(encodedY, qY, progressiveY, extrapolate);
        short[] currentCb = decodeInitialComponent(encodedCb, qCb, progressiveCb, extrapolate);
        short[] currentCr = decodeInitialComponent(encodedCr, qCr, progressiveCr, extrapolate);
        long key = tileKey(tileXIndex, tileYIndex);
        if ((flags & 0x01) != 0) {
            ProgressiveTileState previous = tileStates.get(key);
            if (previous == null) {
                throw new RdesktopException("RFX差分图块缺少历史: " + tileXIndex + "," + tileYIndex);
            }
            if (previous.extrapolate != extrapolate) {
                throw new RdesktopException("RFX差分图块的DWT模式已改变");
            }
            addCoefficients(currentY, previous.currentY);
            addCoefficients(currentCb, previous.currentCb);
            addCoefficients(currentCr, previous.currentCr);
        }

        ProgressiveTileState state = new ProgressiveTileState(currentY, currentCb, currentCr,
                signY, signCb, signCr,
                addQuantization(qY, progressiveY), addQuantization(qCb, progressiveCb),
                addQuantization(qCr, progressiveCr), extrapolate);
        tileStates.put(key, state);
        return renderTile(tileXIndex, tileYIndex, state);
    }

    private static short[] decodeInitialComponent(short[] encoded, int[] quantization,
            int[] progressiveQuantization, boolean extrapolate) {
        short[] coefficients = encoded.clone();
        int[] combined = addQuantization(quantization, progressiveQuantization);
        if (extrapolate) {
            dequantize(coefficients, combined, EXTRAPOLATE_SUBBANDS, 0, 9);
            differentialDecode(coefficients, OFF_EXTRAPOLATE_LL3, 81);
            dequantize(coefficients, combined, EXTRAPOLATE_SUBBANDS, 9, 10);
        } else {
            differentialDecode(coefficients, OFF_LL3, 64);
            dequantize(coefficients, combined, STANDARD_SUBBANDS, 0, STANDARD_SUBBANDS.length);
        }
        return coefficients;
    }

    private Tile decodeUpgradeTile(byte[] data, int offset, int length, int[][] quants,
            ProgressiveQuant[] progressiveQuants, boolean extrapolate) throws RdesktopException {
        if (length < 20) {
            throw new RdesktopException("RFX UPGRADE图块头过短");
        }
        require(data, offset, 20, "RFX UPGRADE图块头");
        int end = offset + length;
        int[] qY = quantAt(quants, data[offset] & 0xFF);
        int[] qCb = quantAt(quants, data[offset + 1] & 0xFF);
        int[] qCr = quantAt(quants, data[offset + 2] & 0xFF);
        int tileXIndex = u16(data, offset + 3);
        int tileYIndex = u16(data, offset + 5);
        ProgressiveQuant progressive = progressiveQuantAt(progressiveQuants, data[offset + 7] & 0xFF);
        int ySrlLength = u16(data, offset + 8);
        int yRawLength = u16(data, offset + 10);
        int cbSrlLength = u16(data, offset + 12);
        int cbRawLength = u16(data, offset + 14);
        int crSrlLength = u16(data, offset + 16);
        int crRawLength = u16(data, offset + 18);
        int encodedLength = ySrlLength + yRawLength + cbSrlLength + cbRawLength
                + crSrlLength + crRawLength;
        int position = offset + 20;
        requireWithin(data, position, encodedLength, end, "RFX UPGRADE图块数据");

        ProgressiveTileState state = tileStates.get(tileKey(tileXIndex, tileYIndex));
        if (state == null) {
            throw new RdesktopException("RFX升级图块缺少首层: " + tileXIndex + "," + tileYIndex);
        }
        if (state.extrapolate != extrapolate) {
            throw new RdesktopException("RFX升级图块的DWT模式已改变");
        }

        upgradeComponent(state.currentY, state.signY, state.bitPositionY,
                qY, progressive.y, data, position, ySrlLength, position + ySrlLength, yRawLength,
                extrapolate);
        position += ySrlLength + yRawLength;
        upgradeComponent(state.currentCb, state.signCb, state.bitPositionCb,
                qCb, progressive.cb, data, position, cbSrlLength, position + cbSrlLength, cbRawLength,
                extrapolate);
        position += cbSrlLength + cbRawLength;
        upgradeComponent(state.currentCr, state.signCr, state.bitPositionCr,
                qCr, progressive.cr, data, position, crSrlLength, position + crSrlLength, crRawLength,
                extrapolate);
        return renderTile(tileXIndex, tileYIndex, state);
    }

    private static void upgradeComponent(short[] current, short[] sign, int[] oldBitPosition,
            int[] quantization, int[] progressiveQuantization, byte[] data,
            int srlOffset, int srlLength, int rawOffset, int rawLength, boolean extrapolate)
            throws RdesktopException {
        int[] newBitPosition = addQuantization(quantization, progressiveQuantization);
        for (int i = 0; i < newBitPosition.length; i++) {
            if (newBitPosition[i] > oldBitPosition[i]) {
                throw new RdesktopException("RFX渐进图块的质量次序无效");
            }
        }
        UpgradeBitReader srl = new UpgradeBitReader(data, srlOffset, srlLength, "SRL");
        UpgradeBitReader raw = new UpgradeBitReader(data, rawOffset, rawLength, "RAW");
        SrlState srlState = new SrlState(srl);
        Subband[] subbands = extrapolate ? EXTRAPOLATE_SUBBANDS : STANDARD_SUBBANDS;
        for (Subband subband : subbands) {
            int quantIndex = subband.quantIndex;
            int bitCount = oldBitPosition[quantIndex] - newBitPosition[quantIndex];
            if (bitCount == 0) continue;
            if (bitCount > 15) {
                throw new RdesktopException("RFX渐进位平面数过大: " + bitCount);
            }
            int shift = Math.max(0, newBitPosition[quantIndex] - 1);
            boolean nonLl = quantIndex != 0;
            int end = subband.offset + subband.length;
            for (int coefficientIndex = subband.offset; coefficientIndex < end; coefficientIndex++) {
                int input;
                if (!nonLl || sign[coefficientIndex] != 0) {
                    input = raw.readBits(bitCount);
                    if (nonLl && sign[coefficientIndex] < 0) input = -input;
                } else {
                    input = srlState.read(bitCount);
                    sign[coefficientIndex] = (short) input;
                }
                current[coefficientIndex] = (short) (current[coefficientIndex] + (input << shift));
            }
        }
        System.arraycopy(newBitPosition, 0, oldBitPosition, 0, newBitPosition.length);
    }

    private static Tile renderTile(int tileXIndex, int tileYIndex, ProgressiveTileState state) {
        short[] y = state.currentY.clone();
        short[] cb = state.currentCb.clone();
        short[] cr = state.currentCr.clone();
        short[] temporary = new short[COEFFICIENT_COUNT];
        if (state.extrapolate) {
            inverseDwtExtrapolate(y, temporary);
            inverseDwtExtrapolate(cb, temporary);
            inverseDwtExtrapolate(cr, temporary);
        } else {
            inverseDwt(y, temporary);
            inverseDwt(cb, temporary);
            inverseDwt(cr, temporary);
        }
        return new Tile(tileXIndex * TILE_SIZE, tileYIndex * TILE_SIZE, yCbCrToArgb(y, cb, cr));
    }

    private static void addCoefficients(short[] difference, short[] previous) {
        for (int i = 0; i < difference.length; i++) {
            difference[i] = (short) (difference[i] + previous[i]);
        }
    }

    private static int[] addQuantization(int[] first, int[] second) {
        int[] result = new int[10];
        for (int i = 0; i < result.length; i++) result[i] = first[i] + second[i];
        return result;
    }

    private static long tileKey(int x, int y) {
        return ((long) y << 32) | (x & 0xFFFFFFFFL);
    }

    private static ProgressiveQuant progressiveQuantAt(ProgressiveQuant[] quants, int index)
            throws RdesktopException {
        if (index == 0xFF) return ProgressiveQuant.FULL;
        if (index < 0 || index >= quants.length || quants[index] == null) {
            throw new RdesktopException("RFX图块引用了不存在的渐进量化表: " + index);
        }
        return quants[index];
    }

    private static int[] quantAt(int[][] quants, int index) throws RdesktopException {
        if (index < 0 || index >= quants.length || quants[index] == null) {
            throw new RdesktopException("RFX图块引用了不存在的量化表: " + index);
        }
        return quants[index];
    }

    private static int[] unpackQuantization(byte[] data, int offset) {
        return new int[] {
                data[offset] & 0x0F,
                (data[offset] >>> 4) & 0x0F,
                data[offset + 1] & 0x0F,
                (data[offset + 1] >>> 4) & 0x0F,
                data[offset + 2] & 0x0F,
                (data[offset + 2] >>> 4) & 0x0F,
                data[offset + 3] & 0x0F,
                (data[offset + 3] >>> 4) & 0x0F,
                data[offset + 4] & 0x0F,
                (data[offset + 4] >>> 4) & 0x0F
        };
    }

    private static short[] decodeRlgr1(byte[] data, int offset, int length) {
        short[] output = new short[COEFFICIENT_COUNT];
        BitReader reader = new BitReader(data, offset, length);
        int k = 1;
        int kp = 8;
        int kr = 1;
        int krp = 8;
        int index = 0;
        while (reader.remaining() > 0 && index < output.length) {
            if (k > 0) {
                int quotient = reader.countLeadingZeros();
                int run = 0;
                for (int i = 0; i < quotient; i++) {
                    run += 1 << k;
                    kp = Math.min(80, kp + 4);
                    k = kp >>> 3;
                }
                if (reader.remaining() < k) break;
                run += reader.readBits(k);
                index = Math.min(output.length, index + run);
                if (index >= output.length || reader.remaining() < 1) break;
                int sign = reader.readBit();
                int magnitudeQuotient = reader.countLeadingOnes();
                if (reader.remaining() < kr) break;
                int code = (magnitudeQuotient << kr) | reader.readBits(kr);
                if (magnitudeQuotient == 0) {
                    krp = Math.max(0, krp - 2);
                } else if (magnitudeQuotient != 1) {
                    krp = Math.min(80, krp + magnitudeQuotient);
                }
                kr = krp >>> 3;
                kp = Math.max(0, kp - 6);
                k = kp >>> 3;
                output[index++] = (short) (sign != 0 ? -(code + 1) : code + 1);
            } else {
                int quotient = reader.countLeadingOnes();
                if (reader.remaining() < kr) break;
                int code = (quotient << kr) | reader.readBits(kr);
                if (quotient == 0) {
                    krp = Math.max(0, krp - 2);
                } else if (quotient != 1) {
                    krp = Math.min(80, krp + quotient);
                }
                kr = krp >>> 3;
                if (code == 0) {
                    kp = Math.min(80, kp + 3);
                    output[index] = 0;
                } else {
                    kp = Math.max(0, kp - 3);
                    output[index] = (short) ((code & 1) != 0 ? -((code + 1) >>> 1) : code >>> 1);
                }
                k = kp >>> 3;
                index++;
            }
        }
        return output;
    }

    private static void differentialDecode(short[] coefficients, int offset, int count) {
        for (int i = offset + 1; i < offset + count; i++) {
            coefficients[i] = (short) (coefficients[i] + coefficients[i - 1]);
        }
    }

    private static void dequantize(short[] coefficients, int[] quantization,
            Subband[] subbands, int from, int to) {
        for (int subbandIndex = from; subbandIndex < to; subbandIndex++) {
            Subband subband = subbands[subbandIndex];
            int shift = Math.max(0, quantization[subband.quantIndex] - 1);
            if (shift == 0) continue;
            int end = subband.offset + subband.length;
            for (int i = subband.offset; i < end; i++) {
                coefficients[i] = (short) (coefficients[i] << shift);
            }
        }
    }

    private static void inverseDwtExtrapolate(short[] coefficients, short[] temporary) {
        inverseDwtExtrapolateLevel(coefficients, temporary, 3807, 9, 8);
        inverseDwtExtrapolateLevel(coefficients, temporary, 3007, 17, 16);
        inverseDwtExtrapolateLevel(coefficients, temporary, 0, 33, 31);
    }

    private static void inverseDwtExtrapolateLevel(short[] values, short[] temporary,
            int offset, int lowSize, int highSize) {
        int outputSize = lowSize + highSize;
        int hl = offset;
        int lh = hl + highSize * lowSize;
        int hh = lh + lowSize * highSize;
        int ll = hh + highSize * highSize;
        short[] low = new short[lowSize];
        short[] high = new short[highSize];
        short[] output = new short[outputSize];

        for (int row = 0; row < lowSize; row++) {
            System.arraycopy(values, ll + row * lowSize, low, 0, lowSize);
            System.arraycopy(values, hl + row * highSize, high, 0, highSize);
            inverseDwtExtrapolate1d(output, low, high);
            System.arraycopy(output, 0, temporary, row * outputSize, outputSize);
        }
        for (int row = 0; row < highSize; row++) {
            System.arraycopy(values, lh + row * lowSize, low, 0, lowSize);
            System.arraycopy(values, hh + row * highSize, high, 0, highSize);
            inverseDwtExtrapolate1d(output, low, high);
            System.arraycopy(output, 0, temporary, (lowSize + row) * outputSize, outputSize);
        }

        short[] columnLow = new short[lowSize];
        short[] columnHigh = new short[highSize];
        for (int column = 0; column < outputSize; column++) {
            for (int row = 0; row < lowSize; row++) {
                columnLow[row] = temporary[row * outputSize + column];
            }
            for (int row = 0; row < highSize; row++) {
                columnHigh[row] = temporary[(lowSize + row) * outputSize + column];
            }
            inverseDwtExtrapolate1d(output, columnLow, columnHigh);
            for (int row = 0; row < outputSize; row++) {
                values[offset + row * outputSize + column] = output[row];
            }
        }
    }

    private static void inverseDwtExtrapolate1d(short[] output, short[] low, short[] high) {
        int lowSize = low.length;
        int highSize = high.length;
        if (highSize == 0) {
            System.arraycopy(low, 0, output, 0, lowSize);
            return;
        }

        int lowIndex = 1;
        int highIndex = 1;
        int outputIndex = 0;
        int previousHigh = high[0];
        int previousEven = saturate16(low[0] - previousHigh);
        int nextEven = previousEven;

        for (int i = 0; i < highSize - 1; i++) {
            int nextHigh = high[highIndex++];
            nextEven = saturate16(low[lowIndex++] - ((previousHigh + nextHigh) / 2));
            int odd = saturate16(((previousEven + nextEven) / 2) + (previousHigh << 1));
            output[outputIndex++] = (short) previousEven;
            output[outputIndex++] = (short) odd;
            previousEven = nextEven;
            previousHigh = nextHigh;
        }

        if (lowSize <= highSize + 1) {
            if (lowSize <= highSize) {
                output[outputIndex++] = (short) nextEven;
                output[outputIndex] = (short) saturate16(nextEven + (previousHigh << 1));
            } else {
                int lastEven = saturate16(low[lowIndex] - previousHigh);
                output[outputIndex++] = (short) nextEven;
                output[outputIndex++] = (short) saturate16(
                        ((lastEven + nextEven) / 2) + (previousHigh << 1));
                output[outputIndex] = (short) lastEven;
            }
        } else {
            int penultimateEven = saturate16(low[lowIndex++] - (previousHigh / 2));
            output[outputIndex++] = (short) nextEven;
            output[outputIndex++] = (short) saturate16(
                    ((penultimateEven + nextEven) / 2) + (previousHigh << 1));
            output[outputIndex++] = (short) penultimateEven;
            output[outputIndex] = (short) saturate16((penultimateEven + low[lowIndex]) / 2);
        }
    }

    private static int saturate16(int value) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private static void inverseDwt(short[] coefficients, short[] temporary) {
        inverseDwtLevel(coefficients, temporary, 3840, 8);
        inverseDwtLevel(coefficients, temporary, 3072, 16);
        inverseDwtLevel(coefficients, temporary, 0, 32);
    }

    private static void inverseDwtLevel(short[] values, short[] temporary, int offset, int n) {
        int doubled = n * 2;
        int area = n * n;
        int hl = offset;
        int lh = offset + area;
        int hh = offset + area * 2;
        int ll = offset + area * 3;
        short[] low = new short[n];
        short[] high = new short[n];
        short[] output = new short[doubled];

        for (int row = 0; row < n; row++) {
            System.arraycopy(values, ll + row * n, low, 0, n);
            System.arraycopy(values, hl + row * n, high, 0, n);
            inverseDwt1d(output, low, high, n);
            System.arraycopy(output, 0, temporary, row * doubled, doubled);

            System.arraycopy(values, lh + row * n, low, 0, n);
            System.arraycopy(values, hh + row * n, high, 0, n);
            inverseDwt1d(output, low, high, n);
            System.arraycopy(output, 0, temporary, (n + row) * doubled, doubled);
        }

        for (int column = 0; column < doubled; column++) {
            for (int row = 0; row < n; row++) {
                low[row] = temporary[row * doubled + column];
                high[row] = temporary[(n + row) * doubled + column];
            }
            inverseDwt1d(output, low, high, n);
            for (int row = 0; row < doubled; row++) {
                values[offset + row * doubled + column] = output[row];
            }
        }
    }

    private static void inverseDwt1d(short[] output, short[] low, short[] high, int n) {
        output[0] = (short) (low[0] - ((high[0] + high[0] + 1) >> 1));
        for (int i = 1; i < n; i++) {
            output[i * 2] = (short) (low[i] - ((high[i - 1] + high[i] + 1) >> 1));
        }
        for (int i = 0; i < n - 1; i++) {
            output[i * 2 + 1] = (short) ((high[i] << 1)
                    + ((output[i * 2] + output[i * 2 + 2]) >> 1));
        }
        output[n * 2 - 1] = (short) ((high[n - 1] << 1) + output[(n - 1) * 2]);
    }

    private static int[] yCbCrToArgb(short[] y, short[] cb, short[] cr) {
        int[] output = new int[COEFFICIENT_COUNT];
        for (int i = 0; i < output.length; i++) {
            long luminance = ((long) y[i] + 4096L) << 16;
            int red = clamp((luminance + (long) cr[i] * 91916L) >> 21);
            int green = clamp((luminance - (long) cb[i] * 22527L - (long) cr[i] * 46819L) >> 21);
            int blue = clamp((luminance + (long) cb[i] * 115992L) >> 21);
            output[i] = 0xFF000000 | (red << 16) | (green << 8) | blue;
        }
        return output;
    }

    private static int clamp(long value) {
        return value < 0 ? 0 : value > 255 ? 255 : (int) value;
    }

    private static int checkedLength(long value, String field) throws RdesktopException {
        if (value > Integer.MAX_VALUE) {
            throw new RdesktopException(field + "长度过大: " + value);
        }
        return (int) value;
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

    private static void requireWithin(byte[] data, int offset, int length, int end, String field)
            throws RdesktopException {
        require(data, offset, length, field);
        if (end < offset || length > end - offset) {
            throw new RdesktopException(field + "越过当前数据块边界");
        }
    }

    static final class Region {
        private final List<Rectangle> rectangles;
        private final List<Tile> tiles;

        Region(List<Rectangle> rectangles, List<Tile> tiles) {
            this.rectangles = Collections.unmodifiableList(new ArrayList<>(rectangles));
            this.tiles = Collections.unmodifiableList(new ArrayList<>(tiles));
        }

        List<Rectangle> rectangles() {
            return rectangles;
        }

        List<Tile> tiles() {
            return tiles;
        }
    }

    static final class Rectangle {
        final int x;
        final int y;
        final int width;
        final int height;

        Rectangle(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    static final class Tile {
        final int x;
        final int y;
        final int[] argb;

        Tile(int x, int y, int[] argb) {
            this.x = x;
            this.y = y;
            this.argb = argb;
        }
    }

    private static final class Subband {
        final int offset;
        final int length;
        final int quantIndex;

        Subband(int offset, int length, int quantIndex) {
            this.offset = offset;
            this.length = length;
            this.quantIndex = quantIndex;
        }
    }

    private static final class ProgressiveQuant {
        static final ProgressiveQuant FULL = new ProgressiveQuant(
                new int[10], new int[10], new int[10]);

        final int[] y;
        final int[] cb;
        final int[] cr;

        ProgressiveQuant(int[] y, int[] cb, int[] cr) {
            this.y = y;
            this.cb = cb;
            this.cr = cr;
        }
    }

    private static final class ProgressiveTileState {
        final short[] currentY;
        final short[] currentCb;
        final short[] currentCr;
        final short[] signY;
        final short[] signCb;
        final short[] signCr;
        final int[] bitPositionY;
        final int[] bitPositionCb;
        final int[] bitPositionCr;
        final boolean extrapolate;

        ProgressiveTileState(short[] currentY, short[] currentCb, short[] currentCr,
                short[] signY, short[] signCb, short[] signCr,
                int[] bitPositionY, int[] bitPositionCb, int[] bitPositionCr,
                boolean extrapolate) {
            this.currentY = currentY;
            this.currentCb = currentCb;
            this.currentCr = currentCr;
            this.signY = signY;
            this.signCb = signCb;
            this.signCr = signCr;
            this.bitPositionY = bitPositionY;
            this.bitPositionCb = bitPositionCb;
            this.bitPositionCr = bitPositionCr;
            this.extrapolate = extrapolate;
        }
    }

    private static final class SrlState {
        private final UpgradeBitReader reader;
        private int kp = 8;
        private int zeroes;
        private boolean unaryMode;

        SrlState(UpgradeBitReader reader) {
            this.reader = reader;
        }

        int read(int bitCount) throws RdesktopException {
            if (zeroes > 0) {
                zeroes--;
                return 0;
            }
            int k = kp >>> 3;
            if (!unaryMode) {
                if (reader.readBit() == 0) {
                    zeroes = 1 << k;
                    kp = Math.min(80, kp + 4);
                    zeroes--;
                    return 0;
                }
                zeroes = k == 0 ? 0 : reader.readBits(k);
                unaryMode = true;
                if (zeroes > 0) {
                    zeroes--;
                    return 0;
                }
            }

            unaryMode = false;
            int sign = reader.readBit();
            kp = Math.max(0, kp - 6);
            if (bitCount == 1) return sign == 0 ? 1 : -1;
            int magnitude = 1;
            int maximum = (1 << bitCount) - 1;
            while (magnitude < maximum) {
                if (reader.readBit() != 0) break;
                magnitude++;
            }
            return sign == 0 ? magnitude : -magnitude;
        }
    }

    private static final class UpgradeBitReader {
        private final byte[] data;
        private final int endBit;
        private final String streamName;
        private int bitPosition;

        UpgradeBitReader(byte[] data, int offset, int length, String streamName) {
            this.data = data;
            this.bitPosition = offset * 8;
            this.endBit = (offset + length) * 8;
            this.streamName = streamName;
        }

        int readBit() throws RdesktopException {
            if (bitPosition >= endBit) {
                throw new RdesktopException("RFX " + streamName + "位流数据不足");
            }
            int value = (data[bitPosition >>> 3] >>> (7 - (bitPosition & 7))) & 1;
            bitPosition++;
            return value;
        }

        int readBits(int count) throws RdesktopException {
            if (count < 0 || count > endBit - bitPosition) {
                throw new RdesktopException("RFX " + streamName + "位流数据不足");
            }
            int value = 0;
            for (int i = 0; i < count; i++) value = (value << 1) | readBit();
            return value;
        }
    }

    private static final class BitReader {
        private final byte[] data;
        private final int endBit;
        private int bitPosition;

        BitReader(byte[] data, int offset, int length) {
            this.data = data;
            this.bitPosition = offset * 8;
            this.endBit = (offset + length) * 8;
        }

        int remaining() {
            return endBit - bitPosition;
        }

        int readBit() {
            if (bitPosition >= endBit) return 0;
            int value = (data[bitPosition >>> 3] >>> (7 - (bitPosition & 7))) & 1;
            bitPosition++;
            return value;
        }

        int readBits(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                value = (value << 1) | readBit();
            }
            return value;
        }

        int countLeadingZeros() {
            int count = 0;
            while (remaining() > 0) {
                if (readBit() != 0) break;
                count++;
            }
            return count;
        }

        int countLeadingOnes() {
            int count = 0;
            while (remaining() > 0) {
                if (readBit() == 0) break;
                count++;
            }
            return count;
        }
    }
}
