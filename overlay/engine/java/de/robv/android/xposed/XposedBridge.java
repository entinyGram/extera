package de.robv.android.xposed;

import android.util.Log;

import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import desu.inugram.helpers.plugins.QuickJs;
import desu.inugram.helpers.plugins.platform.PluginXposed;

public class XposedBridge {
    private static final String TAG = "XposedBridge";
    private static final Object[] EMPTY_ARRAY = new Object[0];
    private static final Map<Member, HookInfo> hookRecords = new HashMap<>();
    private static final Method callbackMethod;
    private static final Map<Member, PluginXposed.Layer> layers = new HashMap<>();
    private static final ThreadLocal<java.util.function.Function<Object[], Object>> currentNext = new ThreadLocal<>();

    static {
        try {
            callbackMethod = HookInfo.class.getMethod("callback", Object[].class);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to initialize", t);
        }
    }

    public XposedBridge() {
    }

    public static boolean isNativeReady() {
        try {
            QuickJs.Companion.ensureLoaded();
        } catch (Throwable t) {
            return false;
        }
        return PluginXposed.INSTANCE.ensureReady();
    }

    private static Method hook0(Object owner, Member target, Method callback) {
        if (!isNativeReady()) {
            return null;
        }
        PluginXposed.Layer layer = (receiver, args, next) -> {
            boolean isStatic = Modifier.isStatic(target.getModifiers());
            Object[] full = args;
            if (!isStatic) {
                full = new Object[args.length + 1];
                full[0] = receiver;
                System.arraycopy(args, 0, full, 1, args.length);
            }
            java.util.function.Function<Object[], Object> previous = currentNext.get();
            currentNext.set(next);
            try {
                return callback.invoke(owner, (Object) full);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            } finally {
                currentNext.set(previous);
            }
        };
        Method backup = PluginXposed.INSTANCE.attachLayer(target, layer);
        if (backup != null) {
            synchronized (layers) {
                layers.put(target, layer);
            }
        }
        return backup;
    }

    private static boolean unhook0(Member target) {
        PluginXposed.Layer layer;
        synchronized (layers) {
            layer = layers.remove(target);
        }
        return layer != null && PluginXposed.INSTANCE.detachLayer(target, layer);
    }

    private static boolean deoptimize0(Member target) {
        return false;
    }

    private static boolean makeClassInheritable0(Class<?> clazz) {
        return false;
    }

    private static Object allocateInstance0(Class<?> clazz) {
        return isNativeReady() ? PluginXposed.Native.INSTANCE.nativeAllocateInstance(clazz) : null;
    }

    private static boolean invokeConstructor0(Object thisObject, Constructor<?> constructor, Object[] args) {
        return false;
    }

    private static boolean isHooked0(Member target) {
        return isNativeReady() && PluginXposed.Native.INSTANCE.nativeIsHooked(target);
    }

    public static boolean disableProfileSaver() {
        return isNativeReady() && PluginXposed.Native.INSTANCE.nativeDisableProfileSaver();
    }

    public static boolean disableHiddenApiRestrictions() {
        try {
            return org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void checkMethod(Member method) {
        if (method == null) {
            throw new NullPointerException("method must not be null");
        }
        if (!(method instanceof Method) && !(method instanceof Constructor)) {
            throw new IllegalArgumentException("method must be a Method or Constructor");
        }
        if (Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalArgumentException("method must not be abstract");
        }
    }

    public static boolean isHooked(Member method) {
        return hookRecords.containsKey(method);
    }

    public static boolean makeClassInheritable(Class<?> clazz) {
        if (clazz == null) {
            throw new NullPointerException("class must not be null");
        }
        return makeClassInheritable0(clazz);
    }

    public static boolean deoptimizeMethod(Member method) {
        checkMethod(method);
        return deoptimize0(method);
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        checkMethod(method);
        if (callback == null) {
            throw new NullPointerException("callback must not be null");
        }
        HookInfo info;
        synchronized (hookRecords) {
            info = hookRecords.get(method);
            if (info == null) {
                info = new HookInfo(method);
                Method backup = hook0(info, method, callbackMethod);
                if (backup == null) {
                    throw new IllegalStateException("Failed to hook method");
                }
                info.backup = backup;
                hookRecords.put(method, info);
            }
        }
        info.callbacks.add(callback);
        return callback.new Unhook(method);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Member method : hookClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                unhooks.add(hookMethod(method, callback));
            }
        }
        return unhooks;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
        for (Member constructor : hookClass.getDeclaredConstructors()) {
            unhooks.add(hookMethod(constructor, callback));
        }
        return unhooks;
    }

    public static void unhookMethod(Member method, XC_MethodHook callback) {
        synchronized (hookRecords) {
            HookInfo info = hookRecords.get(method);
            if (info != null) {
                info.callbacks.remove(callback);
                if (info.callbacks.size() == 0) {
                    hookRecords.remove(method);
                    unhook0(method);
                }
            }
        }
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (args == null) {
            args = EMPTY_ARRAY;
        }
        try {
            HookInfo info = hookRecords.get(method);
            if (info != null) {
                return invokeMethod(info.backup, thisObject, args);
            }
            checkMethod(method);
            return invokeMethod(method, thisObject, args);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException("The class this Constructor belongs to is abstract and cannot be instantiated");
        }
    }

    private static Object invokeBackup(Member backup, boolean isStatic, Object thisObject, Object[] args)
            throws IllegalAccessException, InvocationTargetException, InstantiationException {
        java.util.function.Function<Object[], Object> next = currentNext.get();
        if (next == null) {
            return invokeMethod(backup, thisObject, args);
        }
        currentNext.set(null);
        try {
            return next.apply(args);
        } catch (Throwable e) {
            throw new InvocationTargetException(e);
        } finally {
            currentNext.set(next);
        }
    }

    private static Object invokeMethod(Member member, Object thisObject, Object[] args)
            throws IllegalAccessException, InvocationTargetException, InstantiationException {
        if (member instanceof Method) {
            Method method = (Method) member;
            method.setAccessible(true);
            return method.invoke(thisObject, args);
        }
        Constructor<?> constructor = (Constructor<?>) member;
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    @SuppressWarnings("unchecked")
    public static <T> T allocateInstance(Class<T> clazz) {
        if (clazz == null) {
            throw new NullPointerException();
        }
        return (T) allocateInstance0(clazz);
    }

    @SafeVarargs
    public static <S, T extends S> boolean invokeConstructor(T thisObject, Constructor<S> constructor, Object... args) {
        if (thisObject == null || constructor == null) {
            throw new NullPointerException();
        }
        if (constructor.isVarArgs()) {
            throw new IllegalArgumentException("varargs parameters are not supported");
        }
        return invokeConstructor0(thisObject, constructor, args.length == 0 ? null : args);
    }

    private static void log(Throwable t) {
        Log.e(TAG, "Uncaught Exception", t);
    }

    public static class HookInfo {
        Member backup;
        private final Member method;
        final CopyOnWriteSortedSet<XC_MethodHook> callbacks = new CopyOnWriteSortedSet<>();
        private final boolean isStatic;
        @Nullable
        private final Class<?> returnType;

        public HookInfo(Member method) {
            this.method = method;
            this.isStatic = Modifier.isStatic(method.getModifiers());
            this.returnType = method instanceof Method && !((Method) method).getReturnType().isPrimitive()
                    ? ((Method) method).getReturnType() : null;
        }

        public Object callback(Object[] args) throws Throwable {
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.method = method;
            if (isStatic) {
                param.thisObject = null;
                param.args = args;
            } else {
                param.thisObject = args[0];
                param.args = new Object[args.length - 1];
                System.arraycopy(args, 1, param.args, 0, args.length - 1);
            }
            Object[] snapshot = callbacks.getSnapshot();
            int count = snapshot.length;
            if (count == 0) {
                try {
                    return invokeBackup(backup, isStatic, param.thisObject, param.args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            int beforeIndex = 0;
            do {
                try {
                    ((XC_MethodHook) snapshot[beforeIndex]).beforeHookedMethod(param);
                } catch (Throwable t) {
                    log(t);
                    param.setResult(null);
                    param.returnEarly = false;
                    continue;
                }
                if (param.returnEarly) {
                    beforeIndex++;
                    break;
                }
            } while (++beforeIndex < count);
            if (!param.returnEarly) {
                try {
                    param.setResult(invokeBackup(backup, isStatic, param.thisObject, param.args));
                } catch (InvocationTargetException e) {
                    param.setThrowable(e.getCause());
                }
            }
            int afterIndex = beforeIndex - 1;
            do {
                Object lastResult = param.getResult();
                Throwable lastThrowable = param.getThrowable();
                try {
                    ((XC_MethodHook) snapshot[afterIndex]).afterHookedMethod(param);
                } catch (Throwable t) {
                    log(t);
                    if (lastThrowable == null) {
                        param.setResult(lastResult);
                    } else {
                        param.setThrowable(lastThrowable);
                    }
                }
            } while (--afterIndex >= 0);
            Object result = param.getResultOrThrowable();
            return returnType != null ? returnType.cast(result) : result;
        }
    }

    public static final class CopyOnWriteSortedSet<E> {
        private volatile Object[] elements = EMPTY_ARRAY;

        public int size() {
            return elements.length;
        }

        public synchronized boolean add(E element) {
            int index = indexOf(element);
            if (index >= 0) {
                return false;
            }
            Object[] updated = new Object[elements.length + 1];
            System.arraycopy(elements, 0, updated, 0, elements.length);
            updated[elements.length] = element;
            Arrays.sort(updated);
            elements = updated;
            return true;
        }

        public synchronized boolean remove(E element) {
            int index = indexOf(element);
            if (index == -1) {
                return false;
            }
            Object[] updated = new Object[elements.length - 1];
            System.arraycopy(elements, 0, updated, 0, index);
            System.arraycopy(elements, index + 1, updated, index, elements.length - index - 1);
            elements = updated;
            return true;
        }

        private int indexOf(Object element) {
            for (int i = 0; i < elements.length; i++) {
                if (element.equals(elements[i])) {
                    return i;
                }
            }
            return -1;
        }

        public Object[] getSnapshot() {
            return elements;
        }
    }
}
