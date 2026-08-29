package com.tangluobo.tomato.rdp;

import java.util.logging.Logger;

import com.tangluobo.tomato.rdp.Options;
import com.tangluobo.tomato.rdp.State;

/**
 * 修复版State，阻止processGeneralCaps错误禁用RDP5。
 *
 * 原始State.setRDP5(false)会被Rdp.processGeneralCaps错误调用：
 * 当服务器General Caps的extraFlags字段非零（Windows返回0x40d）时，
 * processGeneralCaps误判为不支持RDP5而调用setRDP5(false)。
 * 但extraFlags非零恰恰表示服务器支持RDP5+，不应降级。
 *
 * 降级RDP5的后果：
 * 1. sendGeneralCaps发送extraFlags=0给服务器，告诉服务器客户端不支持RDP5
 * 2. 服务器停止发送fast-path画面数据
 * 3. 客户端mainLoop阻塞等待slow-path数据，永远收不到画面
 */
public class RdpState extends State {

    private static final Logger logger = Logger.getLogger(RdpState.class.getName());

    private boolean rdp5Locked = false;

    public RdpState(Options options) {
        super(options);
    }

    /**
     * 锁定RDP5状态，阻止processGeneralCaps错误降级。
     * 在RDP连接建立后调用（options已设置rdp5=true）。
     */
    public void lockRdp5() {
        this.rdp5Locked = true;
        logger.info("RDP5状态已锁定为: " + isRDP5());
    }

    @Override
    public void setRDP5(boolean rdp5) {
        if (rdp5Locked && !rdp5 && isRDP5()) {
            // 阻止processGeneralCaps的错误降级
            logger.warning("阻止RDP5降级（processGeneralCaps bug）：extraFlags非零不应降级RDP5");
            return;
        }
        super.setRDP5(rdp5);
    }
}
