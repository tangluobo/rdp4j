package com.tangluobo.tomato.rdp;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.tangluobo.tomato.rdp.Packet;
import com.tangluobo.tomato.rdp.RdesktopException;
import com.tangluobo.tomato.rdp.State;
import com.tangluobo.tomato.rdp.rdp5.VChannel;
import com.tangluobo.tomato.rdp.rdp5.VChannels;

/**
 * 修复版虚拟通道集合（VChannels）。
 *
 * javardp 3.0.0 的{@link VChannels#channel_process}在处理多分片虚拟通道消息时
 * 存在致命bug：分片数据通过Utilities.concatenateBytes({fragment_buffer, data})
 * 拼接，而fragment_buffer初始为null（且每次重组完成后被置回null），首次分片
 * 即对null元素读取length抛NullPointerException（"cannot read the array length
 * because 'b' is null"），异常沿RDP接收线程上传导致连接断开。
 *
 * 库内通道（cliprdr文本、DisplayControl）消息都很小，永远单分片
 * （CHANNEL_FLAG_FIRST|CHANNEL_FLAG_LAST同时置位直接process），从未触发过
 * 分片路径，因此bug一直未暴露。文件剪贴板的大消息（FILEGROUPDESW描述列表、
 * FILECONTENTS_RESPONSE数据块）超过单个MCS PDU（约1590字节）必然分片，一触即崩。
 *
 * 本类覆盖channel_process，修复点：
 * 1. 分片缓冲null安全拼接（System.arraycopy手工合并）；
 * 2. 缓冲按MCS通道隔离，避免多通道并发分片消息互相污染；
 * 3. 收到CHANNEL_FLAG_FIRST分片时重置缓冲，防止丢包/乱序后拼入残留数据。
 */
public class FixedVChannels extends VChannels {

    private static final Logger logger = Logger.getLogger(FixedVChannels.class.getName());

    /** 分片重组缓冲（null表示无进行中的分片消息） */
    private byte[] fragmentBuffer;
    /** 当前缓冲所属的MCS通道ID（防多通道交叉污染） */
    private int fragmentMcsId = -1;
    /** 各通道收到的数据包计数（诊断：确认音频通道是否有数据到达） */
    private final java.util.Map<Integer, Integer> channelPacketCounts = new java.util.concurrent.ConcurrentHashMap<>();

    public FixedVChannels(State state) {
        super(state);
    }

    @Override
    public void channel_process(Packet packet, int mcsChannel) throws RdesktopException, IOException {
        VChannel channel = null;
        for (int i = 0; i < num_channels(); i++) {
            if (mcs_id(i) == mcsChannel) {
                channel = channel(i);
                break;
            }
        }
        if (channel == null) {
            logger.warning("Data from unknown channel " + mcsChannel);
            return;
        }

        // 诊断：记录各通道数据包计数（每通道前2包和每100包打印一次）
        int count = channelPacketCounts.merge(mcsChannel, 1, Integer::sum);
        if (count <= 2 || count % 100 == 0) {
            logger.info(String.format("[VCHAN] 通道%s(%s) 收到第%d个数据包, %d字节",
                    mcsChannel, channel.name(), count, packet.getEnd() - packet.getPosition()));
        }

        // 虚拟通道chunk头：length(4，忽略) + flags(4)
        packet.getLittleEndian32();
        int flags = packet.getLittleEndian32();
        // 诊断：记录到达的通道数据，确认服务器是否在给rdpsnd通道推送数据
        logger.finest("channel_process: mcs=" + mcsChannel + " channel=" + channel.name()
                + " flags=0x" + Integer.toHexString(flags) + " remain="
                + (packet.getEnd() - packet.getPosition()));

        if ((flags & CHANNEL_FLAG_FIRST) != 0 && (flags & CHANNEL_FLAG_LAST) != 0) {
            // 单分片完整消息：直接处理（position已跳过chunk头）
            channel.process(packet);
            return;
        }

        // 多分片消息：取出当前chunk数据（position到end）
        if ((flags & CHANNEL_FLAG_FIRST) != 0) {
            // 新消息的第一个分片：重置缓冲（防上次残留）
            fragmentBuffer = null;
            fragmentMcsId = -1;
        }
        int avail = packet.getEnd() - packet.getPosition();
        byte[] data = new byte[Math.max(avail, 0)];
        if (avail > 0) {
            packet.copyToByteArray(data, 0, packet.getPosition(), avail);
        }

        // null安全拼接 + 通道隔离
        if (fragmentBuffer == null || fragmentMcsId != mcsChannel) {
            fragmentBuffer = data;
            fragmentMcsId = mcsChannel;
        } else {
            byte[] merged = new byte[fragmentBuffer.length + data.length];
            System.arraycopy(fragmentBuffer, 0, merged, 0, fragmentBuffer.length);
            System.arraycopy(data, 0, merged, fragmentBuffer.length, data.length);
            fragmentBuffer = merged;
        }

        if ((flags & CHANNEL_FLAG_LAST) != 0) {
            // 最后一个分片：重组完整消息并处理
            // Packet(int)构造后position=0，copyFromByteArray不推进position，
            // process()内从position 0读消息头，与单分片路径一致
            Packet whole = new Packet(fragmentBuffer.length);
            whole.copyFromByteArray(fragmentBuffer, 0, 0, fragmentBuffer.length);
            fragmentBuffer = null;
            fragmentMcsId = -1;
            channel.process(whole);
        }
    }
}
