import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Sets or removes the proxy of the currently connected WiFi network by asking
 * Android's WiFi service to make the change directly (as the Settings app does).
 * No reboot needed: the proxy is saved and applied to the connection right away.
 *
 * Must be run as root through app_process. It only uses reflection, so it
 * compiles with a plain javac, without android.jar.
 *
 * Usage: SetProxy HOST PORT [exclusion1,exclusion2,...]
 *        SetProxy off
 *        SetProxy status
 */
public class SetProxy {

    private static final String PKG = "com.android.shell";

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Throwable t) {
            t = unwrap(t);
            System.err.println("Error: " + t);
            if (t.getCause() != null) {
                System.err.println("Cause: " + unwrap(t.getCause()));
            }
            code = 2;
        }
        System.exit(code);
    }

    private static int run(String[] args) throws Exception {
        boolean off = args.length > 0 && args[0].equals("off");
        boolean status = args.length > 0 && args[0].equals("status");
        if (args.length == 0 || (!off && !status && args.length < 2)) {
            System.err.println("Usage: SetProxy HOST PORT [comma,separated,exclusions] | off | status");
            return 1;
        }

        Object wifi = wifiService();
        Object info = call(wifi, "getConnectionInfo");
        int netId = (Integer) get(info, "getNetworkId");
        if (netId < 0) {
            System.err.println("No WiFi network connected");
            return 3;
        }
        Object config = findConfig(wifi, netId);
        String ssid = (String) config.getClass().getField("SSID").get(config);

        if (status) {
            System.out.println(ssid + ": " + describe(get(config, "getHttpProxy")));
            return 0;
        }

        Class<?> proxyInfoClass = Class.forName("android.net.ProxyInfo");
        Object proxy = null;
        if (!off) {
            int port = Integer.parseInt(args[1]);
            List<String> exclusions = new ArrayList<String>();
            if (args.length > 2) {
                for (String e : args[2].split(",")) {
                    if (!e.trim().isEmpty()) {
                        exclusions.add(e.trim());
                    }
                }
            }
            proxy = proxyInfoClass
                    .getMethod("buildDirectProxy", String.class, int.class, List.class)
                    .invoke(null, args[0], port, exclusions);
            // Android silently ignores a proxy that fails its validation,
            // so it is better to stop right away with a clear error
            if (!isValid(proxy)) {
                System.err.println("Invalid proxy: check host, port and exclusions "
                        + "(letters, digits, hyphens, dots and * only, comma-separated)");
                return 1;
            }
        }
        // setHttpProxy(null) means "no proxy"
        config.getClass().getMethod("setHttpProxy", proxyInfoClass).invoke(config, proxy);

        // "save" is the call used by the Settings app: it saves the network and, if it
        // is the connected one, applies the new proxy right away. Falls back to
        // addOrUpdateNetwork (saves, but a reconnection may be needed) if "save" is missing.
        if (findMethod(wifi, "save") != null) {
            call(wifi, "save", config);
        } else {
            call(wifi, "addOrUpdateNetwork", config);
        }

        // "save" is asynchronous: re-read the configuration to check the result
        String expected = describe(proxy);
        String current = null;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(300);
            current = describe(get(findConfig(wifi, netId), "getHttpProxy"));
            if (current.equalsIgnoreCase(expected)) {
                System.out.println(ssid + ": " + current);
                return 0;
            }
        }
        System.err.println(ssid + ": change not applied (current proxy: " + current + ")");
        return 4;
    }

    private static Object wifiService() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "wifi");
        if (binder == null) {
            throw new IllegalStateException("wifi service not available");
        }
        return Class.forName("android.net.wifi.IWifiManager$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder"))
                .invoke(null, binder);
    }

    private static Object findConfig(Object wifi, int netId) throws Exception {
        Exception last = null;
        // the "privileged" variant is the one used by the Settings app; the other is a fallback
        for (String name : new String[] {"getPrivilegedConfiguredNetworks", "getConfiguredNetworks"}) {
            try {
                Object result = call(wifi, name);
                List<?> list = result instanceof List
                        ? (List<?>) result
                        : (List<?>) get(result, "getList");
                for (Object c : list) {
                    if (c.getClass().getField("networkId").getInt(c) == netId) {
                        return c;
                    }
                }
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("configuration for network " + netId + " not found", last);
    }

    private static String describe(Object proxy) throws Exception {
        if (proxy == null) {
            return "no proxy";
        }
        Object host = get(proxy, "getHost");
        if (host == null || host.toString().isEmpty()) {
            return proxy.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(host).append(':').append(get(proxy, "getPort"));
        String[] exclusions = (String[]) get(proxy, "getExclusionList");
        if (exclusions != null && exclusions.length > 0) {
            sb.append(" (excluded: ");
            for (int i = 0; i < exclusions.length; i++) {
                sb.append(i > 0 ? "," : "").append(exclusions[i]);
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /** Same validation Android applies before using the proxy. */
    private static boolean isValid(Object proxy) {
        try {
            return (Boolean) get(proxy, "isValid");
        } catch (Exception e) {
            return true; // method not available: let the system decide
        }
    }

    /** Calls a no-argument method even if its declaring class is not public. */
    private static Object get(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Method findMethod(Object target, String name) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Calls an IWifiManager method, filling in the parameters based on their type,
     * so it keeps working even if the signature changes between Android versions.
     */
    private static Object call(Object target, String name, Object... values) throws Exception {
        Method m = findMethod(target, name);
        if (m == null) {
            throw new NoSuchMethodException(name);
        }
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        boolean packageGiven = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            for (Object v : values) {
                if (t.isInstance(v)) {
                    args[i] = v;
                }
            }
            if (args[i] != null) {
                continue;
            }
            if (t == String.class) {
                // the first String is the calling package; the others (e.g. featureId) stay null
                if (!packageGiven) {
                    args[i] = PKG;
                }
                packageGiven = true;
            } else if (t == boolean.class) {
                args[i] = false;
            } else if (t == int.class) {
                args[i] = 0;
            } else if (t == long.class) {
                args[i] = 0L;
            } else if (t.getName().equals("android.os.Bundle")) {
                args[i] = extras();
            } else if (t.isInterface() && t.getName().endsWith("Listener")) {
                args[i] = dummyListener(t);
            }
        }
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /** Bundle holding the AttributionSource required by recent Android versions. */
    private static Object extras() throws Exception {
        Object bundle = Class.forName("android.os.Bundle").getConstructor().newInstance();
        try {
            Object uid = Class.forName("android.os.Process").getMethod("myUid").invoke(null);
            Object builder = Class.forName("android.content.AttributionSource$Builder")
                    .getConstructor(int.class)
                    .newInstance(uid);
            builder.getClass().getMethod("setPackageName", String.class).invoke(builder, PKG);
            Object source = builder.getClass().getMethod("build").invoke(builder);
            String key = "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE";
            try {
                key = (String) Class.forName("android.net.wifi.WifiManager").getField(key).get(null);
            } catch (Exception ignored) {
                // use the default name
            }
            bundle.getClass()
                    .getMethod("putParcelable", String.class, Class.forName("android.os.Parcelable"))
                    .invoke(bundle, key, source);
        } catch (Exception ignored) {
            // without AttributionSource, the Bundle is left empty
        }
        return bundle;
    }

    /** Listener that ignores callbacks: the result is checked by re-reading the configuration. */
    private static Object dummyListener(Class<?> iface) throws Exception {
        final Object binder = Class.forName("android.os.Binder").getConstructor().newInstance();
        return Proxy.newProxyInstance(SetProxy.class.getClassLoader(), new Class<?>[] {iface},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object self, Method method, Object[] a) {
                        String n = method.getName();
                        if (n.equals("asBinder")) {
                            return binder;
                        } else if (n.equals("hashCode")) {
                            return System.identityHashCode(self);
                        } else if (n.equals("equals")) {
                            return self == a[0];
                        } else if (n.equals("toString")) {
                            return "listener";
                        }
                        return null;
                    }
                });
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
