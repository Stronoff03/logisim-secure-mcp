package logisimmcp;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point and Java Agent for Logisim MCP Bridge v2.0.0.
 */
public final class Launcher {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private Launcher() {}

    public static void premain(String args, Instrumentation inst) {
        startBridge();
    }

    public static void agentmain(String args, Instrumentation inst) {
        startBridge();
    }

    private static void startBridge() {
        if (STARTED.compareAndSet(false, true)) {
            BridgeServer.ensureStarted();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args != null && args.length >= 3 && "--attach".equals(args[0])) {
            attachToPid(args[1], args[2]);
            return;
        }
        startBridge();
        com.cburch.logisim.Main.main(args != null ? args : new String[0]);
    }

    private static void attachToPid(String pid, String agentJarPath) throws Exception {
        Class<?> vmClass;
        try {
            vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "JDK Attach API not present in this JVM. Run attach with JDK java.exe.", e);
        }

        Method attachMethod = vmClass.getMethod("attach", String.class);
        Method loadAgentMethod = vmClass.getMethod("loadAgent", String.class);
        Method detachMethod = vmClass.getMethod("detach");

        Object vm = null;
        try {
            vm = attachMethod.invoke(null, pid);
        } catch (Exception firstEx) {
            // Workaround for HotSpot JRE8 vs JDK Attach protocol quirk
            String msg = firstEx.getCause() != null ? firstEx.getCause().getMessage() : firstEx.getMessage();
            if (msg != null && (msg.contains("AttachNotSupportedException") || msg.contains(": 0"))) {
                vm = attachMethod.invoke(null, pid);
            } else {
                throw firstEx;
            }
        }
        try {
            loadAgentMethod.invoke(vm, agentJarPath);
        } finally {
            try {
                detachMethod.invoke(vm);
            } catch (Throwable ignored) {}
        }
    }
}
