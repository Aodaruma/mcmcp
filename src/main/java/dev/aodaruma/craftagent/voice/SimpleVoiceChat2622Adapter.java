package dev.aodaruma.craftagent.voice;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Reflection adapter for exactly Simple Voice Chat {@value #SUPPORTED_MOD_VERSION}.
 *
 * <p>The optional mod's internal API has no compatibility promise. For that
 * reason this class names and validates the complete reflection surface, never
 * calls {@code setAccessible}, and rejects every other version. It does not
 * stop/restart Voice Chat or mutate any global audio setting.</p>
 */
public final class SimpleVoiceChat2622Adapter implements VoiceChatAdapter {
    public static final String SUPPORTED_MOD_VERSION = "2.6.22+26.2";
    public static final String ADAPTER_VERSION = "simple-voice-chat-2.6.22+26.2";

    static final String CLIENT_MANAGER_CLASS = "de.maxhenkel.voicechat.voice.client.ClientManager";
    static final String PLAYER_STATE_MANAGER_CLASS =
            "de.maxhenkel.voicechat.voice.client.ClientPlayerStateManager";
    static final String GET_PLAYER_STATE_MANAGER = "getPlayerStateManager";
    static final String IS_DISCONNECTED = "isDisconnected";
    static final String IS_MUTED = "isMuted";
    static final String SET_MUTED = "setMuted";

    private final VoiceChatInstallationProbe installationProbe;
    private final ClassLoader classLoader;
    private final BooleanSupplier clientThread;

    private volatile BoundMethods boundMethods;
    private volatile String bindingFailure;

    public SimpleVoiceChat2622Adapter(
            VoiceChatInstallationProbe installationProbe,
            ClassLoader classLoader,
            BooleanSupplier clientThread) {
        this.installationProbe = Objects.requireNonNull(installationProbe, "installationProbe");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
    }

    /**
     * Creates the production adapter using the optional mod's own FML game-layer
     * loader. If Voice Chat is installed but its defining module cannot be
     * proven, the rejecting loader makes {@link #probe()} fail closed.
     */
    public static SimpleVoiceChat2622Adapter forNeoForge(BooleanSupplier clientThread) {
        var installationProbe = new NeoForgeVoiceChatInstallationProbe();
        var resolvedLoader = installationProbe.resolveInternalClassLoader();
        if (resolvedLoader == null) {
            try {
                resolvedLoader = installationProbe.inspect().installed()
                        ? RejectingVoiceChatClassLoader.INSTANCE
                        : SimpleVoiceChat2622Adapter.class.getClassLoader();
            } catch (RuntimeException | LinkageError failure) {
                resolvedLoader = RejectingVoiceChatClassLoader.INSTANCE;
            }
        }
        return new SimpleVoiceChat2622Adapter(
                installationProbe,
                resolvedLoader,
                clientThread);
    }

    @Override
    public Probe probe() {
        final VoiceChatInstallationProbe.Installation installation;
        try {
            installation = Objects.requireNonNull(installationProbe.inspect(), "installation");
        } catch (RuntimeException | LinkageError failure) {
            return unavailable("mod_probe_" + failure.getClass().getSimpleName());
        }
        if (!installation.installed()) {
            return new Probe(Availability.NOT_INSTALLED, null, ADAPTER_VERSION, null);
        }
        if (!SUPPORTED_MOD_VERSION.equals(installation.version())) {
            return new Probe(
                    Availability.INCOMPATIBLE,
                    installation.version(),
                    ADAPTER_VERSION,
                    "unsupported_voicechat_version");
        }
        if (resolveMethods() == null) {
            return new Probe(
                    Availability.UNAVAILABLE,
                    installation.version(),
                    ADAPTER_VERSION,
                    bindingFailure == null ? "adapter_binding_failed" : bindingFailure);
        }
        return new Probe(Availability.READY, installation.version(), ADAPTER_VERSION, null);
    }

    @Override
    public ReadResult readState() {
        var probe = probe();
        if (!probe.ready()) {
            return ReadResult.failure(probe.failureCode() == null
                    ? "voicechat_not_ready"
                    : probe.failureCode());
        }
        if (!onClientThread()) {
            return ReadResult.failure("wrong_client_thread");
        }
        try {
            var methods = Objects.requireNonNull(boundMethods, "boundMethods");
            var manager = methods.getPlayerStateManager().invoke(null);
            if (manager == null) {
                return ReadResult.failure("player_state_manager_unavailable");
            }
            var disconnected = invokeBoolean(methods.isDisconnected(), manager);
            var muted = invokeBoolean(methods.isMuted(), manager);
            return ReadResult.success(new State(!disconnected, muted));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return ReadResult.failure(invocationFailureCode(failure));
        }
    }

    @Override
    public WriteResult setMuted(boolean muted) {
        var probe = probe();
        if (!probe.ready()) {
            return WriteResult.failure(probe.failureCode() == null
                    ? "voicechat_not_ready"
                    : probe.failureCode());
        }
        if (!onClientThread()) {
            return WriteResult.failure("wrong_client_thread");
        }
        try {
            var methods = Objects.requireNonNull(boundMethods, "boundMethods");
            var manager = methods.getPlayerStateManager().invoke(null);
            if (manager == null) {
                return WriteResult.failure("player_state_manager_unavailable");
            }
            methods.setMuted().invoke(manager, muted);
            return WriteResult.succeeded();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return WriteResult.failure(invocationFailureCode(failure));
        }
    }

    private boolean onClientThread() {
        try {
            return clientThread.getAsBoolean();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private BoundMethods resolveMethods() {
        var existing = boundMethods;
        if (existing != null) {
            return existing;
        }
        if (bindingFailure != null) {
            return null;
        }
        synchronized (this) {
            if (boundMethods != null || bindingFailure != null) {
                return boundMethods;
            }
            try {
                var clientManager = Class.forName(CLIENT_MANAGER_CLASS, false, classLoader);
                var playerStateManager = Class.forName(PLAYER_STATE_MANAGER_CLASS, false, classLoader);

                var getManager = clientManager.getMethod(GET_PLAYER_STATE_MANAGER);
                requirePublicStatic(getManager, playerStateManager);
                var disconnected = playerStateManager.getMethod(IS_DISCONNECTED);
                requirePublicInstance(disconnected, boolean.class);
                var muted = playerStateManager.getMethod(IS_MUTED);
                requirePublicInstance(muted, boolean.class);
                var setMuted = playerStateManager.getMethod(SET_MUTED, boolean.class);
                requirePublicInstance(setMuted, void.class, boolean.class);

                boundMethods = new BoundMethods(getManager, disconnected, muted, setMuted);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                bindingFailure = "adapter_binding_" + failure.getClass().getSimpleName();
            }
            return boundMethods;
        }
    }

    private static void requirePublicStatic(Method method, Class<?> returnType, Class<?>... parameters) {
        requireExactSignature(method, returnType, parameters);
        if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("expected public static method: " + method.getName());
        }
    }

    private static void requirePublicInstance(Method method, Class<?> returnType, Class<?>... parameters) {
        requireExactSignature(method, returnType, parameters);
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("expected public instance method: " + method.getName());
        }
    }

    private static void requireExactSignature(Method method, Class<?> returnType, Class<?>... parameters) {
        if (method.getReturnType() != returnType
                || !java.util.Arrays.equals(method.getParameterTypes(), parameters)) {
            throw new IllegalStateException("unexpected method signature: " + method.getName());
        }
    }

    private static boolean invokeBoolean(Method method, Object target)
            throws InvocationTargetException, IllegalAccessException {
        var value = method.invoke(target);
        if (!(value instanceof Boolean result)) {
            throw new IllegalStateException("non-boolean result: " + method.getName());
        }
        return result;
    }

    private static String invocationFailureCode(Throwable failure) {
        var effective = failure;
        if (failure instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            effective = invocation.getCause();
        }
        return "adapter_invocation_" + effective.getClass().getSimpleName();
    }

    private static Probe unavailable(String failureCode) {
        return new Probe(Availability.UNAVAILABLE, null, ADAPTER_VERSION, failureCode);
    }

    private record BoundMethods(
            Method getPlayerStateManager,
            Method isDisconnected,
            Method isMuted,
            Method setMuted) {
    }

    private static final class RejectingVoiceChatClassLoader extends ClassLoader {
        private static final RejectingVoiceChatClassLoader INSTANCE =
                new RejectingVoiceChatClassLoader();

        private RejectingVoiceChatClassLoader() {
            super(null);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            throw new ClassNotFoundException("Voice Chat game-layer module loader was unavailable");
        }
    }
}
