package com.tangluobo.tomato.rdp.sound;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * RDP远程音频播放器。
 *
 * 独立播放线程 + 有界队列，避免阻塞RDP接收主线程（图形/输入保持响应）：
 * - 通道线程收到音频块后仅入队（队列满时丢弃当前块，防止延迟无限累积）；
 * - 播放线程按块顺序写SourceDataLine（阻塞写即天然限流）；
 * - 格式号变化时重开Line（服务器中途切换采样率等）。
 *
 * 仅支持PCM（RDP服务器必然提供PCM格式，Windows默认通告22050/44100Hz 16bit）。
 */
public class RdpAudioPlayer {

    private static final Logger logger = Logger.getLogger(RdpAudioPlayer.class.getName());

    /** 播放队列容量（音频块数，典型块为40ms音频） */
    private static final int QUEUE_CAPACITY = 24;

    /** 播放线程 */
    private Thread thread;
    private volatile boolean running;
    /** 队列：播放线程消费，通道线程生产 */
    private final BlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /** 当前打开的Line及其对应格式号 */
    private SourceDataLine line;
    private int lineFormatNo = -1;

    /** 待播放的音频块 */
    private static class Chunk {
        final int formatNo;
        final AudioFormat format;
        final byte[] data;

        Chunk(int formatNo, AudioFormat format, byte[] data) {
            this.formatNo = formatNo;
            this.format = format;
            this.data = data;
        }
    }

    /**
     * 启动播放线程
     */
    public synchronized void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "RDP-Audio-Player");
        thread.setDaemon(true);
        thread.start();
        logger.info("音频播放线程已启动");
    }

    /**
     * 提交音频块（通道线程调用，非阻塞）。
     * 队列满时丢弃当前块（播放已落后太多，丢弃保持延迟有界）。
     */
    public void submit(int formatNo, AudioFormat format, byte[] data) {
        if (!running) {
            return;
        }
        if (!queue.offer(new Chunk(formatNo, format, data))) {
            logger.fine("音频队列已满，丢弃音频块(" + data.length + "字节)");
        }
    }

    /**
     * 设置音量（0x0000-0xFFFF，0为静音）
     */
    public void setVolume(int volume) {
        SourceDataLine l = line;
        if (l == null || !l.isOpen()) {
            return;
        }
        try {
            if (l.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl ctrl = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
                if (volume <= 0) {
                    ctrl.setValue(ctrl.getMinimum());
                } else {
                    // 线性音量→分贝增益
                    float gain = 20f * (float) Math.log10(Math.min(volume, 0xFFFF) / 65535f);
                    ctrl.setValue(Math.max(ctrl.getMinimum(), Math.min(ctrl.getMaximum(), gain)));
                }
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "设置音量失败: " + e.getMessage());
        }
    }

    /**
     * 停止播放并释放资源
     */
    public synchronized void stop() {
        running = false;
        queue.clear();
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        closeLine();
        thread = null;
        logger.info("音频播放器已停止");
    }

    private void run() {
        while (running) {
            Chunk chunk;
            try {
                chunk = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (chunk == null) {
                continue;
            }
            try {
                ensureLine(chunk.formatNo, chunk.format);
                if (line != null) {
                    line.write(chunk.data, 0, chunk.data.length);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "音频播放失败: " + e.getMessage());
                closeLine();
            }
        }
        closeLine();
    }

    /**
     * 确保Line以指定格式打开（格式变化时重开）
     */
    private void ensureLine(int formatNo, AudioFormat format) {
        if (line != null && formatNo == lineFormatNo) {
            return;
        }
        closeLine();
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                logger.warning("音频格式不受支持: " + format);
                return;
            }
            line = (SourceDataLine) AudioSystem.getLine(info);
            // 缓冲区约200ms，兼顾低延迟与抗抖动
            int bufferBytes = Math.max((int) (format.getFrameRate() * format.getFrameSize() * 0.2),
                    format.getFrameSize() * 64);
            line.open(format, bufferBytes);
            line.start();
            lineFormatNo = formatNo;
            logger.info("音频设备已打开: " + format + " (formatNo=" + formatNo + ")");
        } catch (LineUnavailableException e) {
            logger.log(Level.WARNING, "打开音频设备失败: " + e.getMessage());
            line = null;
            lineFormatNo = -1;
        }
    }

    private void closeLine() {
        if (line != null) {
            try {
                line.drain();
                line.stop();
                line.close();
            } catch (Exception e) {
                logger.log(Level.FINE, "关闭音频设备出错: " + e.getMessage());
            }
            line = null;
        }
        lineFormatNo = -1;
    }
}
