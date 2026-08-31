package com.tangluobo.rdp4j;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.HHOOK;
import com.sun.jna.platform.win32.WinUser.KBDLLHOOKSTRUCT;
import com.sun.jna.platform.win32.WinUser.LowLevelKeyboardProc;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.ptr.IntByReference;

/**
 * Captures keyboard input before JavaFX can apply local traversal shortcuts.
 * The hook remains process-wide but suppresses keys only for the currently
 * focused full-screen RDP owner.
 */
final class WindowsFullScreenKeyboardHook {

    interface KeySink {
        boolean forward(int scanCode, boolean extended, boolean release);
    }

    private static final Logger logger = Logger.getLogger(WindowsFullScreenKeyboardHook.class.getName());
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("windows");
    private static final int WH_KEYBOARD_LL = 13;
    private static final int WM_KEYDOWN = 0x0100;
    private static final int WM_KEYUP = 0x0101;
    private static final int WM_SYSKEYDOWN = 0x0104;
    private static final int WM_SYSKEYUP = 0x0105;
    private static final int LLKHF_EXTENDED = 0x01;
    private static final int VK_TAB = 0x09;
    private static final WindowsFullScreenKeyboardHook INSTANCE = new WindowsFullScreenKeyboardHook();

    private final AtomicReference<KeySink> activeSink = new AtomicReference<>();
    private volatile boolean startAttempted;
    private volatile boolean available;
    private volatile HHOOK hook;
    private LowLevelKeyboardProc callback;

    static WindowsFullScreenKeyboardHook instance() {
        return INSTANCE;
    }

    boolean activate(KeySink sink) {
        if (!WINDOWS || sink == null || !ensureStarted()) {
            return false;
        }
        activeSink.set(sink);
        return true;
    }

    void deactivate(KeySink sink) {
        if (sink != null) {
            activeSink.compareAndSet(sink, null);
        }
    }

    boolean isAvailable() {
        return available;
    }

    private boolean ensureStarted() {
        if (available) {
            return true;
        }
        CountDownLatch started;
        synchronized (this) {
            if (available) {
                return true;
            }
            if (startAttempted) {
                return false;
            }
            startAttempted = true;
            started = new CountDownLatch(1);
            Thread thread = new Thread(() -> runHookLoop(started), "rdp-fullscreen-keyboard-hook");
            thread.setDaemon(true);
            thread.start();
        }
        try {
            started.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return available;
    }

    private void runHookLoop(CountDownLatch started) {
        try {
            callback = this::handleKey;
            hook = User32.INSTANCE.SetWindowsHookEx(WH_KEYBOARD_LL, callback,
                    Kernel32.INSTANCE.GetModuleHandle(null), 0);
            available = hook != null;
            started.countDown();
            if (!available) {
                logger.warning("安装Windows全屏键盘钩子失败，继续使用JavaFX键盘桥");
                return;
            }
            logger.info("Windows全屏键盘钩子已安装");
            MSG message = new MSG();
            while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                User32.INSTANCE.TranslateMessage(message);
                User32.INSTANCE.DispatchMessage(message);
            }
        } catch (Throwable error) {
            available = false;
            logger.log(Level.WARNING, "Windows全屏键盘钩子异常，继续使用JavaFX键盘桥", error);
        } finally {
            started.countDown();
            HHOOK installed = hook;
            if (installed != null) {
                User32.INSTANCE.UnhookWindowsHookEx(installed);
                hook = null;
            }
        }
    }

    private LRESULT handleKey(int code, WPARAM message, KBDLLHOOKSTRUCT data) {
        HHOOK installed = hook;
        LPARAM dataPointer = data == null ? null
                : new LPARAM(Pointer.nativeValue(data.getPointer()));
        if (code < 0 || data == null) {
            return User32.INSTANCE.CallNextHookEx(installed, code, message, dataPointer);
        }

        KeySink sink = activeSink.get();
        int messageId = message.intValue();
        boolean release = messageId == WM_KEYUP || messageId == WM_SYSKEYUP;
        boolean keyboardMessage = release || messageId == WM_KEYDOWN || messageId == WM_SYSKEYDOWN;
        if (sink == null || !keyboardMessage || !isThisProcessInForeground()) {
            return User32.INSTANCE.CallNextHookEx(installed, code, message, dataPointer);
        }

        boolean extended = (data.flags & LLKHF_EXTENDED) != 0;
        boolean forwarded;
        try {
            forwarded = sink.forward(data.scanCode, extended, release);
        } catch (Throwable error) {
            logger.log(Level.WARNING, "转发Windows全屏键盘事件失败", error);
            forwarded = false;
        }
        if (!forwarded) {
            return User32.INSTANCE.CallNextHookEx(installed, code, message, dataPointer);
        }
        if (data.vkCode == VK_TAB) {
            logger.info(() -> "[FULLSCREEN_KEY_NATIVE] code=TAB, scan=" + data.scanCode
                    + ", release=" + release);
        }
        return new LRESULT(1);
    }

    private boolean isThisProcessInForeground() {
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        if (foreground == null) {
            return false;
        }
        IntByReference processId = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(foreground, processId);
        return processId.getValue() == Kernel32.INSTANCE.GetCurrentProcessId();
    }
}
