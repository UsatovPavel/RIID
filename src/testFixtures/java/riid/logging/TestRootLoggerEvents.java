package riid.logging;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * Attaches Logback {@code ListAppender} to the root logger to capture {@code ILoggingEvent}s
 * without a compile dependency on {@code ch.qos.logback.*} (works in integration/moduled source sets).
 */
public final class TestRootLoggerEvents implements AutoCloseable {

    private static final String DEFAULT_APPENDER_NAME = "test-root-list-capture";

    private final Object appender;

    private TestRootLoggerEvents(Object appender) {
        this.appender = appender;
    }

    public static TestRootLoggerEvents attach() throws ReflectiveOperationException {
        return attach(DEFAULT_APPENDER_NAME);
    }

    public static TestRootLoggerEvents attach(String appenderName) throws ReflectiveOperationException {
        Object context = LoggerFactory.getILoggerFactory();
        Object rootLogger = invoke(context, "getLogger", org.slf4j.Logger.ROOT_LOGGER_NAME);
        Class<?> appenderClass = Class.forName("ch.qos.logback.core.read.ListAppender");
        Object listAppender = appenderClass.getDeclaredConstructor().newInstance();
        invoke(listAppender, "setName", appenderName);
        invoke(listAppender, "setContext", context);
        invoke(listAppender, "start");
        invoke(rootLogger, "addAppender", listAppender);
        return new TestRootLoggerEvents(listAppender);
    }

    @Override
    public void close() throws ReflectiveOperationException {
        detach();
    }

    public void detach() throws ReflectiveOperationException {
        Object context = LoggerFactory.getILoggerFactory();
        Object rootLogger = invoke(context, "getLogger", org.slf4j.Logger.ROOT_LOGGER_NAME);
        invoke(rootLogger, "detachAppender", appender);
        invoke(appender, "stop");
    }

    @SuppressWarnings("unchecked")
    public List<Object> events() throws ReflectiveOperationException {
        Field listField = appender.getClass().getField("list");
        return (List<Object>) listField.get(appender);
    }

    public static String keyValue(Object event, String key) {
        List<?> pairs;
        try {
            pairs = (List<?>) invoke(event, "getKeyValuePairs");
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
        if (pairs == null) {
            return null;
        }
        for (Object pairObj : pairs) {
            if (!(pairObj instanceof KeyValuePair pair)) {
                continue;
            }
            if (Objects.equals(key, pair.key)) {
                return pair.value == null ? null : pair.value.toString();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> mdcPropertyMap(Object event) throws ReflectiveOperationException {
        return (Map<String, String>) invoke(event, "getMDCPropertyMap");
    }

    private static Object invoke(Object target, String methodName, Object... args) throws ReflectiveOperationException {
        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i].getClass();
        }
        Method method;
        try {
            method = target.getClass().getMethod(methodName, argTypes);
        } catch (NoSuchMethodException ex) {
            method = findCompatibleMethod(target.getClass(), methodName, args.length);
        }
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Method findCompatibleMethod(Class<?> type, String methodName, int argCount) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == argCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + methodName);
    }
}
