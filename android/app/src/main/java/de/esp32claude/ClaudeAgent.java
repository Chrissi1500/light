package de.esp32claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import org.json.JSONObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Claude als Programmier-Assistent: plant, schreibt MicroPython-Code und fuehrt ihn per Tools auf dem ESP32 aus. */
public class ClaudeAgent {
    public interface Events { void emit(JSONObject ev); }
    public interface LinkProvider { EspLink get() throws Exception; }

    private static final String SYSTEM = String.join("\n",
        "Du bist ein Programmier-Assistent in der Android-App \"ESP32 Claude\". Du programmierst einen echten ESP32,",
        "auf dem MicroPython und der \"ESP32 Claude Agent\" laufen. Ueber deine Tools fuehrst du Code direkt auf dem Geraet aus.",
        "",
        "Arbeitsweise:",
        "- Antworte auf Deutsch, kurz und freundlich. Der Nutzer ist evtl. Einsteiger.",
        "- Rufe zu Beginn esp_info auf, wenn du das Geraet noch nicht kennst.",
        "- Teste kurze Snippets mit run_python (laeuft max. timeout Sekunden mit Ausgabe zurueck).",
        "- Dauerhafte Programme (Endlosschleifen, Webserver, Sensor-Loops) installierst du mit deploy_app: Das speichert",
        "  den Code als app.py und startet den ESP32 neu; die App laeuft dann bei jedem Start im Hintergrund.",
        "  Danach mit get_logs pruefen, ob sie laeuft (nach dem Neustart ein paar Sekunden warten bzw. erneut fragen).",
        "- In app.py KEIN WLAN-Setup, keine Nutzung von Port 80/4210 und kein Bluetooth: das verwaltet der Agent.",
        "  Ueberschreibe niemals main.py, agent.py oder config.json.",
        "- print() in deinem Code landet in den Logs (get_logs) bzw. in der run_python-Ausgabe.",
        "- Pins haengen vom Board ab. Wenn unklar (z. B. Onboard-LED), frag nach oder nutze gaengige Defaults",
        "  (ESP32 DevKit: LED an GPIO2; ESP32-S3/C3 oft RGB-LED via neopixel an GPIO48 bzw. GPIO8) und sag das dazu.",
        "- Zeige am Ende den finalen Code in einem ```python Block und erklaere kurz, was er tut und wie man ihn aendert.",
        "- Sei vorsichtig mit Code, der Hardware beschaedigen koennte (z. B. Kurzschluss durch falsche Pins) und weise darauf hin.");

    private final List<MessageParam> history = new ArrayList<>();
    private volatile boolean cancel = false;
    private final List<Tool> tools = buildTools();

    public synchronized void reset() { history.clear(); }
    public void cancel() { cancel = true; }

    private static Map<String, Object> prop(String type, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        return m;
    }

    private static Tool tool(String name, String desc, Map<String, Map<String, Object>> props, List<String> required) {
        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
        for (Map.Entry<String, Map<String, Object>> e : props.entrySet())
            pb.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        return Tool.builder().name(name).description(desc)
                .inputSchema(Tool.InputSchema.builder().properties(pb.build()).required(required).build())
                .build();
    }

    private static List<Tool> buildTools() {
        List<Tool> t = new ArrayList<>();
        t.add(tool("esp_info", "Infos zum ESP32: Chip, MicroPython-Version, freier Speicher, IP, Status der app.py.",
                Map.of(), List.of()));
        Map<String, Map<String, Object>> run = new LinkedHashMap<>();
        run.put("code", prop("string", "MicroPython-Code, der sofort ausgefuehrt wird."));
        run.put("timeout", prop("integer", "Max. Wartezeit in Sekunden auf das Ende (Standard 10, max 60). Laeuft der Code laenger, laeuft er im Hintergrund weiter."));
        t.add(tool("run_python", "Fuehrt MicroPython-Code auf dem ESP32 aus und liefert die Ausgabe (print) und Fehler zurueck.", run, List.of("code")));
        Map<String, Map<String, Object>> dep = new LinkedHashMap<>();
        dep.put("code", prop("string", "Vollstaendiger Inhalt fuer app.py."));
        t.add(tool("deploy_app", "Speichert Code dauerhaft als app.py und startet den ESP32 neu. app.py laeuft danach bei jedem Start automatisch.", dep, List.of("code")));
        Map<String, Map<String, Object>> logs = new LinkedHashMap<>();
        logs.put("since", prop("integer", "Nur Zeilen nach dieser Sequenznummer (0 = alle)."));
        t.add(tool("get_logs", "Liest die letzten Log-/print-Zeilen des ESP32 (auch von app.py).", logs, List.of()));
        Map<String, Map<String, Object>> ls = new LinkedHashMap<>();
        ls.put("path", prop("string", "Verzeichnis, Standard /"));
        t.add(tool("list_files", "Listet Dateien auf dem ESP32.", ls, List.of()));
        Map<String, Map<String, Object>> rd = new LinkedHashMap<>();
        rd.put("path", prop("string", "Dateipfad"));
        t.add(tool("read_file", "Liest eine Textdatei vom ESP32.", rd, List.of("path")));
        Map<String, Map<String, Object>> wr = new LinkedHashMap<>();
        wr.put("path", prop("string", "Dateipfad, z. B. lib/sensor.py"));
        wr.put("content", prop("string", "Dateiinhalt"));
        t.add(tool("write_file", "Schreibt eine Textdatei auf den ESP32 (z. B. Hilfsmodule).", wr, List.of("path", "content")));
        t.add(tool("stop_app", "Deaktiviert app.py und startet den ESP32 neu (Notbremse bei haengendem Programm).", Map.of(), List.of()));
        t.add(tool("reset_device", "Startet den ESP32 neu.", Map.of(), List.of()));
        return t;
    }

    private JSONObject runTool(String name, JSONObject in, LinkProvider lp) throws Exception {
        EspLink link = lp.get();
        JSONObject cmd = new JSONObject();
        int timeout = 15000;
        switch (name) {
            case "esp_info": cmd.put("cmd", "info"); break;
            case "run_python": {
                int t = Math.max(1, Math.min(60, in.optInt("timeout", 10)));
                cmd.put("cmd", "run").put("code", in.optString("code")).put("timeout", t);
                timeout = (t + 10) * 1000;
                break;
            }
            case "deploy_app": cmd.put("cmd", "deploy").put("code", in.optString("code")); break;
            case "get_logs": cmd.put("cmd", "logs").put("since", in.optInt("since", 0)); break;
            case "list_files": cmd.put("cmd", "ls").put("path", in.optString("path", "/")); break;
            case "read_file": cmd.put("cmd", "read").put("path", in.optString("path")); break;
            case "write_file": {
                String p = in.optString("path");
                if (p.matches("/?(main|agent)\\.py|/?config\\.json"))
                    return new JSONObject().put("ok", false).put("err", "Diese Systemdatei darf nicht ueberschrieben werden.");
                cmd.put("cmd", "write").put("path", p).put("data", in.optString("content"));
                break;
            }
            case "stop_app": cmd.put("cmd", "stop"); break;
            case "reset_device": cmd.put("cmd", "reset"); break;
            default: return new JSONObject().put("ok", false).put("err", "Unbekanntes Tool " + name);
        }
        return link.call(cmd, timeout);
    }

    private static JSONObject ev(String type) {
        try { return new JSONObject().put("type", type); } catch (Exception e) { return new JSONObject(); }
    }

    /** Laeuft blockierend im Hintergrund-Thread. */
    public synchronized void chat(String apiKey, String model, String effort, String boardNote, String userText,
                                  LinkProvider lp, Events events) {
        cancel = false;
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofMinutes(5))
                .build();
        String system = SYSTEM + (boardNote == null || boardNote.isBlank() ? "" : "\n\nHinweis des Nutzers zum Board: " + boardNote);
        int mark = history.size();
        history.add(MessageParam.ofUser(userText));
        try {
            for (int step = 0; step < 25; step++) {
                if (cancel) { events.emit(ev("done").put("text", "Abgebrochen.")); return; }
                MessageCreateParams.Builder b = MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(16000L)
                        .system(system)
                        .messages(history)
                        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build());
                for (Tool t : tools) b.addTool(t);
                if (model.startsWith("claude-opus-5-5") || model.startsWith("claude-sonnet-5-5")) {
                    // Bei einer Sicherheits-Ablehnung serverseitig automatisch auf ein passendes Modell ausweichen
                    b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
                    b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
                }
                events.emit(ev("thinking"));
                Message resp = client.messages().create(b.build());
                history.add(resp.toParam());

                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : resp.content()) {
                    block.text().ifPresent(t -> {
                        try { events.emit(ev("text").put("text", t.text())); } catch (Exception ignored) { }
                    });
                    if (block.toolUse().isPresent()) {
                        ToolUseBlock tu = block.toolUse().get();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = tu._input().convert(Map.class);
                        JSONObject input = new JSONObject(m == null ? Map.of() : m);
                        events.emit(ev("tool").put("name", tu.name()).put("input", input));
                        String out;
                        boolean err = false;
                        try {
                            JSONObject r = runTool(tu.name(), input, lp);
                            out = r.toString();
                            err = !r.optBoolean("ok", true);
                            events.emit(ev("tool_result").put("name", tu.name()).put("result", r));
                        } catch (Exception e) {
                            out = "Fehler: " + e.getMessage();
                            err = true;
                            events.emit(ev("tool_result").put("name", tu.name()).put("error", e.getMessage()));
                        }
                        results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                                .toolUseId(tu.id()).content(out).isError(err).build()));
                    }
                }
                StopReason sr = resp.stopReason().orElse(null);
                if (!results.isEmpty()) {
                    history.add(MessageParam.ofUserOfContentBlockParams(results));
                    continue;
                }
                if (StopReason.REFUSAL.equals(sr)) {
                    events.emit(ev("error").put("text", "Claude hat diese Anfrage abgelehnt."));
                } else if (StopReason.MAX_TOKENS.equals(sr)) {
                    events.emit(ev("error").put("text", "Antwort wurde wegen Laengenlimit abgeschnitten."));
                }
                events.emit(ev("done"));
                return;
            }
            events.emit(ev("done").put("text", "Schrittlimit erreicht - schreib 'weiter', damit Claude fortfaehrt."));
        } catch (Throwable e) {
            // Unvollstaendigen Gespraechsteil verwerfen, damit der Verlauf gueltig bleibt
            while (history.size() > mark) history.remove(history.size() - 1);
            try { events.emit(ev("error").put("text", "Fehler: " + e.getMessage())); } catch (Exception ignored) { }
            events.emit(ev("done"));
        }
    }
}
