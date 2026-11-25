package com.bobbybot.gemini;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A connection to the Gemini Live API (voice in, voice out, function calls) over a WebSocket.
 * <p>
 * It reconnects by itself: Google ends each connection after about 10 minutes, and we resume the same
 * conversation with the "session resumption" handle Gemini gives us. It can also be put to {@link #sleep()}
 * when nobody is talking (to save free quota) and {@link #wake()} up later with its memory intact.
 */
public final class LiveSession implements WebSocket.Listener {

    /** Everything Gemini can tell us. These are called on a background thread! */
    public interface Handler {
        void onReady(boolean firstTime);

        void onAudio(byte[] pcm24k);

        void onInterrupted();

        void onInputTranscript(String text);

        void onOutputTranscript(String text);

        void onTurnComplete();

        void onToolCall(String id, String name, JsonObject args);

        void onProblem(String message, boolean gaveUp);
    }

    private static final Logger LOG = LoggerFactory.getLogger("Gemini");
    private static final String ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private static final int MAX_FAILURES = 5;
    private static final int MAX_OUTBOX = 20;

    private final String label;
    private final Handler handler;
    private final Supplier<JsonObject> setupFactory;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ExecutorService sendQueue;
    private final ScheduledExecutorService timer;

    private final StringBuilder textFrame = new StringBuilder();
    private final ByteArrayOutputStream binaryFrame = new ByteArrayOutputStream();
    /** Text messages waiting for the connection to be ready. */
    private final Deque<JsonObject> outbox = new ArrayDeque<>();

    private volatile WebSocket socket;
    private volatile boolean connecting;
    private volatile boolean ready;
    private volatile boolean sleeping = true;
    private volatile boolean shutdown;
    private volatile boolean everReady;
    private volatile boolean gaveUp;
    private volatile String resumeHandle;
    private volatile String lastProblem = "";
    private int failures;

    public LiveSession(String label, String apiKey, Supplier<JsonObject> setupFactory, Handler handler) {
        this.label = label;
        this.apiKey = apiKey;
        this.setupFactory = setupFactory;
        this.handler = handler;
        this.sendQueue = Executors.newSingleThreadExecutor(r -> daemon(r, "Gemini-Send-" + label));
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "Gemini-Timer-" + label));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Connect if we aren't already (also after giving up or sleeping). */
    public synchronized void wake() {
        if (shutdown) {
            return;
        }
        boolean wasSleeping = sleeping;
        sleeping = false;
        if (gaveUp || wasSleeping) {
            gaveUp = false;
            failures = 0;
        }
        if (socket == null && !connecting) {
            connect();
        }
    }

    /** Disconnect to save quota. The conversation is remembered (for about 2 hours) and continues on wake(). */
    public synchronized void sleep() {
        if (sleeping || shutdown) {
            return;
        }
        sleeping = true;
        ready = false;
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            closeQuietly(ws, "sleeping");
        }
        LOG.info("[{}] Disconnected from Gemini to save quota (nobody talked for a while).", label);
    }

    /** Forget the conversation and start a brand new one. */
    public synchronized void reset() {
        resumeHandle = null;
        synchronized (outbox) {
            outbox.clear();
        }
        WebSocket ws = socket;
        socket = null;
        ready = false;
        if (ws != null) {
            closeQuietly(ws, "reset");
        }
        sleeping = false;
        gaveUp = false;
        failures = 0;
        if (!connecting) {
            connect();
        }
    }

    public void shutdown() {
        shutdown = true;
        ready = false;
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            closeQuietly(ws, "bye");
        }
        sendQueue.shutdownNow();
        timer.shutdownNow();
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isAwake() {
        return !sleeping && !shutdown;
    }

    public boolean hasGivenUp() {
        return gaveUp;
    }

    public String lastProblem() {
        return lastProblem;
    }

    // ------------------------------------------------------------------ connection

    private synchronized void connect() {
        if (shutdown || sleeping || connecting || socket != null) {
            return;
        }
        connecting = true;
        ready = false;
        String url = ENDPOINT + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), this)
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        connecting = false;
                        if (!shutdown && !sleeping) {
                            problem("Couldn't connect to Gemini: " + rootMessage(error));
                            scheduleReconnect();
                        }
                    }
                });
    }

    @Override
    public void onOpen(WebSocket ws) {
        synchronized (this) {
            connecting = false;
            if (shutdown || sleeping || socket != null) {
                closeQuietly(ws, "not needed");
                return;
            }
            socket = ws;
        }
        JsonObject message = setupFactory.get();
        JsonObject setup = message.getAsJsonObject("setup");
        JsonObject resumption = new JsonObject();
        if (resumeHandle != null) {
            resumption.addProperty("handle", resumeHandle);
        }
        setup.add("sessionResumption", resumption);
        sendRaw(ws, message.toString());
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        textFrame.append(data);
        if (last) {
            String json = textFrame.toString();
            textFrame.setLength(0);
            if (ws == socket) {
                handle(json);
            }
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        binaryFrame.write(chunk, 0, chunk.length);
        if (last) {
            String json = binaryFrame.toString(StandardCharsets.UTF_8);
            binaryFrame.reset();
            if (ws == socket) {
                handle(json);
            }
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
        if (ws == socket) {
            socket = null;
            boolean wasReady = ready;
            ready = false;
            if (!shutdown && !sleeping) {
                if (code != WebSocket.NORMAL_CLOSURE || !wasReady) {
                    problem("Gemini closed the connection (" + code + ")"
                            + (reason == null || reason.isBlank() ? "" : ": " + reason));
                }
                scheduleReconnect();
            }
        }
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        if (ws == socket) {
            socket = null;
            ready = false;
            if (!shutdown && !sleeping) {
                problem("Connection error: " + rootMessage(error));
                scheduleReconnect();
            }
        }
    }

    private void scheduleReconnect() {
        if (shutdown || sleeping) {
            return;
        }
        int attempt;
        synchronized (this) {
            attempt = ++failures;
        }
        if (resumeHandle != null && attempt >= 2) {
            resumeHandle = null; // the old conversation probably expired, start fresh
        }
        if (attempt > MAX_FAILURES) {
            gaveUp = true;
            lastProblem = "Gave up after " + MAX_FAILURES + " failed attempts. " + lastProblem;
            handler.onProblem("Gave up connecting to Gemini after " + MAX_FAILURES
                    + " tries. Check the problem above, then talk to Bobby again or use /bobby-admin reset.", true);
            return;
        }
        long delay = Math.min(30, 1L << Math.min(attempt, 5));
        try {
            timer.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    /** Close the current connection and open a new one that continues the same conversation. */
    private void reconnectNow() {
        synchronized (this) {
            WebSocket old = socket;
            socket = null;
            ready = false;
            if (old != null) {
                closeQuietly(old, "reconnecting");
            }
        }
        connect();
    }

    // ------------------------------------------------------------------ incoming

    private void handle(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception ex) {
            LOG.warn("[{}] Gemini sent something unreadable: {}", label, abbreviate(json));
            return;
        }

        if (root.has("setupComplete")) {
            ready = true;
            boolean first = !everReady;
            everReady = true;
            synchronized (this) {
                failures = 0;
            }
            lastProblem = "";
            handler.onReady(first);
            flushOutbox();
        }

        JsonObject content = obj(root, "serverContent");
        if (content != null) {
            if (bool(content, "interrupted")) {
                handler.onInterrupted();
            }
            JsonObject turn = obj(content, "modelTurn");
            if (turn != null && turn.has("parts")) {
                for (JsonElement el : turn.getAsJsonArray("parts")) {
                    JsonObject part = el.getAsJsonObject();
                    JsonObject inline = obj(part, "inlineData");
                    if (inline != null && inline.has("data")) {
                        String mime = inline.has("mimeType") ? inline.get("mimeType").getAsString() : "audio/pcm";
                        if (mime.startsWith("audio/pcm")) {
                            handler.onAudio(Base64.getDecoder().decode(inline.get("data").getAsString()));
                        }
                    }
                }
            }
            JsonObject in = obj(content, "inputTranscription");
            if (in != null && in.has("text")) {
                handler.onInputTranscript(in.get("text").getAsString());
            }
            JsonObject out = obj(content, "outputTranscription");
            if (out != null && out.has("text")) {
                handler.onOutputTranscript(out.get("text").getAsString());
            }
            if (bool(content, "turnComplete")) {
                handler.onTurnComplete();
            }
        }

        JsonObject toolCall = obj(root, "toolCall");
        if (toolCall != null && toolCall.has("functionCalls")) {
            for (JsonElement el : toolCall.getAsJsonArray("functionCalls")) {
                JsonObject call = el.getAsJsonObject();
                String id = call.has("id") ? call.get("id").getAsString() : "";
                String name = call.has("name") ? call.get("name").getAsString() : "";
                JsonObject args = obj(call, "args");
                handler.onToolCall(id, name, args == null ? new JsonObject() : args);
            }
        }

        JsonObject resume = obj(root, "sessionResumptionUpdate");
        if (resume != null && bool(resume, "resumable") && resume.has("newHandle")) {
            resumeHandle = resume.get("newHandle").getAsString();
        }

        if (root.has("goAway")) {
            // Google is about to end this connection: hop to a new one and keep the conversation
            try {
                timer.execute(this::reconnectNow);
            } catch (RejectedExecutionException ignored) {
                // shutting down
            }
        }

        JsonObject error = obj(root, "error");
        if (error != null) {
            problem("Gemini error: " + (error.has("message") ? error.get("message").getAsString() : error.toString()));
        }
    }

    // ------------------------------------------------------------------ outgoing

    /** Microphone audio: 16-bit little-endian PCM at 16 kHz. Dropped when not connected. */
    public void sendAudio(byte[] pcm16k) {
        if (!ready) {
            return;
        }
        JsonObject audio = new JsonObject();
        audio.addProperty("data", Base64.getEncoder().encodeToString(pcm16k));
        audio.addProperty("mimeType", "audio/pcm;rate=16000");
        JsonObject realtime = new JsonObject();
        realtime.add("audio", audio);
        JsonObject msg = new JsonObject();
        msg.add("realtimeInput", realtime);
        send(msg);
    }

    /** Tell Gemini the microphone stream paused (someone stopped talking). */
    public void endAudioStream() {
        if (!ready) {
            return;
        }
        JsonObject realtime = new JsonObject();
        realtime.addProperty("audioStreamEnd", true);
        JsonObject msg = new JsonObject();
        msg.add("realtimeInput", realtime);
        send(msg);
    }

    /**
     * Add a text message to the conversation. Waits in a queue if the connection isn't ready yet.
     *
     * @param respond true = the AI should answer it now, false = just remember it
     */
    public void sendText(String text, boolean respond) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject turn = new JsonObject();
        turn.addProperty("role", "user");
        turn.add("parts", parts);
        JsonArray turns = new JsonArray();
        turns.add(turn);
        JsonObject clientContent = new JsonObject();
        clientContent.add("turns", turns);
        clientContent.addProperty("turnComplete", respond);
        JsonObject msg = new JsonObject();
        msg.add("clientContent", clientContent);
        if (ready) {
            send(msg);
        } else {
            synchronized (outbox) {
                if (outbox.size() >= MAX_OUTBOX) {
                    outbox.pollFirst();
                }
                outbox.addLast(msg);
            }
        }
    }

    private void flushOutbox() {
        synchronized (outbox) {
            JsonObject msg;
            while ((msg = outbox.pollFirst()) != null) {
                send(msg);
            }
        }
    }

    /** Answer a function call. scheduling = SILENT, WHEN_IDLE or INTERRUPT (null = default). */
    public void sendToolResponse(String id, String name, JsonObject response, String scheduling) {
        if (!ready) {
            return;
        }
        JsonObject fr = new JsonObject();
        if (id != null && !id.isEmpty()) {
            fr.addProperty("id", id);
        }
        fr.addProperty("name", name);
        fr.add("response", response);
        if (scheduling != null) {
            fr.addProperty("scheduling", scheduling);
        }
        JsonArray list = new JsonArray();
        list.add(fr);
        JsonObject toolResponse = new JsonObject();
        toolResponse.add("functionResponses", list);
        JsonObject msg = new JsonObject();
        msg.add("toolResponse", toolResponse);
        send(msg);
    }

    private void send(JsonObject msg) {
        sendRaw(socket, msg.toString());
    }

    private void sendRaw(WebSocket ws, String json) {
        if (ws == null || sendQueue.isShutdown()) {
            return;
        }
        try {
            sendQueue.execute(() -> {
                try {
                    // The WebSocket only allows one send at a time, so wait for each to finish.
                    ws.sendText(json, true).get(15, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    if (!shutdown && ws == socket) {
                        LOG.debug("[{}] Send to Gemini failed", label, ex);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    // ------------------------------------------------------------------ helpers

    private void problem(String message) {
        lastProblem = message;
        handler.onProblem(message, false);
    }

    private static void closeQuietly(WebSocket ws, String reason) {
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, reason)
                    .orTimeout(3, TimeUnit.SECONDS)
                    .whenComplete((w, e) -> ws.abort());
        } catch (Exception ex) {
            ws.abort();
        }
    }

    private static JsonObject obj(JsonObject parent, String key) {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static boolean bool(JsonObject parent, String key) {
        JsonElement el = parent.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsBoolean();
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
