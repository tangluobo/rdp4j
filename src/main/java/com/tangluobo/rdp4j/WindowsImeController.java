package com.tangluobo.rdp4j;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Temporarily detaches the local Windows IME from the JavaFX host window while
 * an RDP canvas owns keyboard focus. The original HIMC is restored verbatim so
 * this does not change the user's selected input method or language.
 */
final class WindowsImeController {

    private static final Logger logger = Logger.getLogger(WindowsImeController.class.getName());
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("windows");
    private static final Object LOCK = new Object();

    private static WindowsImeController owner;
    private static Pointer disabledWindow;
    private static Pointer originalContext;
    private static int windowThreadId;

    void disableForFocusedWindow() {
        if (!WINDOWS) {
            return;
        }

        try {
            Pointer target = findImeWindow();
            if (target == null) {
                logger.fine("未找到属于当前线程且带输入法上下文的RDP窗口");
                return;
            }

            synchronized (LOCK) {
                if (sameWindow(disabledWindow, target)) {
                    // JavaFX normally reports focus loss before focus gain, but
                    // transferring ownership also handles the reverse ordering.
                    owner = this;
                    return;
                }

                if (!restoreLocked()) {
                    return;
                }
                Pointer current = Imm32.INSTANCE.ImmGetContext(target);
                if (current == null) {
                    return;
                }
                try {
                    Pointer previous = Imm32.INSTANCE.ImmAssociateContext(target, null);
                    if (previous == null) {
                        logger.warning("解除本地Windows输入法上下文失败");
                        return;
                    }
                    Pointer remaining = Imm32.INSTANCE.ImmGetContext(target);
                    if (remaining != null) {
                        Imm32.INSTANCE.ImmReleaseContext(target, remaining);
                        logger.warning("本地Windows输入法上下文仍处于关联状态");
                        return;
                    }
                    disabledWindow = target;
                    originalContext = previous;
                    windowThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
                    owner = this;
                } finally {
                    Imm32.INSTANCE.ImmReleaseContext(target, current);
                }
            }
            logger.info("RDP获得焦点，已暂时解除本地Windows输入法上下文");
        } catch (Throwable e) {
            logger.log(Level.WARNING, "解除本地Windows输入法上下文失败，继续使用键盘事件桥", e);
        }
    }

    void restore() {
        if (!WINDOWS) {
            return;
        }
        try {
            synchronized (LOCK) {
                if (owner != this) {
                    return;
                }
                if (!restoreLocked()) {
                    return;
                }
            }
            logger.info("RDP失去焦点，已恢复本地Windows输入法上下文");
        } catch (Throwable e) {
            logger.log(Level.WARNING, "恢复本地Windows输入法上下文失败", e);
        }
    }

    private static boolean restoreLocked() {
        if (disabledWindow == null || originalContext == null) {
            clearState();
            return true;
        }
        if (Kernel32.INSTANCE.GetCurrentThreadId() != windowThreadId) {
            logger.warning("无法从非窗口线程恢复本地Windows输入法上下文");
            return false;
        }

        Imm32.INSTANCE.ImmAssociateContext(disabledWindow, originalContext);
        Pointer restored = Imm32.INSTANCE.ImmGetContext(disabledWindow);
        if (restored == null) {
            logger.warning("本地Windows输入法上下文尚未恢复，将在下次焦点切换时重试");
            return false;
        }
        boolean matchesOriginal = sameWindow(restored, originalContext);
        Imm32.INSTANCE.ImmReleaseContext(disabledWindow, restored);
        if (!matchesOriginal) {
            logger.warning("恢复后的Windows输入法上下文与原上下文不一致");
            return false;
        }
        clearState();
        return true;
    }

    private static void clearState() {
        owner = null;
        disabledWindow = null;
        originalContext = null;
        windowThreadId = 0;
    }

    private static Pointer findImeWindow() {
        int currentProcess = Kernel32.INSTANCE.GetCurrentProcessId();
        int currentThread = Kernel32.INSTANCE.GetCurrentThreadId();
        Set<Pointer> candidates = new LinkedHashSet<>();
        Pointer disabledSnapshot;
        synchronized (LOCK) {
            disabledSnapshot = disabledWindow;
        }

        Pointer focused = User32.INSTANCE.GetFocus();
        addCandidate(candidates, focused);
        if (focused != null) {
            addCandidate(candidates, User32.INSTANCE.GetAncestor(focused, 2)); // GA_ROOT
        }
        addCandidate(candidates, User32.INSTANCE.GetForegroundWindow());

        for (Pointer candidate : candidates) {
            IntByReference process = new IntByReference();
            int thread = User32.INSTANCE.GetWindowThreadProcessId(candidate, process);
            if (thread != currentThread || process.getValue() != currentProcess) {
                continue;
            }
            if (sameWindow(disabledSnapshot, candidate)) {
                return candidate;
            }
            Pointer context = Imm32.INSTANCE.ImmGetContext(candidate);
            if (context != null) {
                Imm32.INSTANCE.ImmReleaseContext(candidate, context);
                return candidate;
            }
        }
        return null;
    }

    private static void addCandidate(Set<Pointer> candidates, Pointer candidate) {
        if (candidate != null) {
            candidates.add(candidate);
        }
    }

    private static boolean sameWindow(Pointer left, Pointer right) {
        return left != null && right != null
                && Pointer.nativeValue(left) == Pointer.nativeValue(right);
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer GetFocus();

        Pointer GetForegroundWindow();

        Pointer GetAncestor(Pointer window, int flags);

        int GetWindowThreadProcessId(Pointer window, IntByReference processId);
    }

    private interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);

        int GetCurrentProcessId();

        int GetCurrentThreadId();
    }

    private interface Imm32 extends StdCallLibrary {
        Imm32 INSTANCE = Native.load("imm32", Imm32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer ImmGetContext(Pointer window);

        boolean ImmReleaseContext(Pointer window, Pointer context);

        Pointer ImmAssociateContext(Pointer window, Pointer context);
    }
}
