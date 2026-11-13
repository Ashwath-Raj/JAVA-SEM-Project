package com.glyvex.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Native JavaFX desktop control panel for the existing GLYVEX Linux runtime. */
public final class GlyvexDesktopApp extends Application {
    private static final List<String> SYMBOLS = List.of("B", "C", "E", "F", "L", "S", "T");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern PREDICTION = Pattern.compile("Prediction:\\s*([A-Z])");
    private static final Pattern CONFIDENCE = Pattern.compile("Confidence:\\s*([0-9.]+)");
    private static final Pattern DECISION = Pattern.compile("Decision:\\s*(ACCEPT|REJECT)");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, TextField> commandFields = new LinkedHashMap<>();
    private final TextArea logArea = new TextArea();
    private final Label runtimeState = new Label("STOPPED");
    private final Label deviceState = new Label("Not checked");
    private final Label modeState = new Label("DRY RUN");
    private final Label predictedChar = new Label("—");
    private final Label confidenceValue = new Label("—");
    private final Label decisionValue = new Label("Waiting for a gesture");
    private final Label stateDetail = new Label("Start the runtime to listen to the touchpad.");
    private final CheckBox liveExecution = new CheckBox("Enable live command execution");
    private final TextField thresholdField = new TextField("0.88");
    private final Button startButton = new Button("Start runtime");
    private final Button stopButton = new Button("Stop runtime");
    private Path root;
    private Path configPath;
    private Process runtimeProcess;
    private Thread outputThread;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    @Override
    public void start(Stage stage) {
        String configuredRoot = System.getProperty("glyvex.root", ".");
        root = Paths.get(configuredRoot).toAbsolutePath().normalize();
        configPath = root.resolve("config/glyvex-config.json");
        loadSettingsIntoUi();

        BorderPane shell = new BorderPane();
        shell.getStyleClass().add("app-shell");
        shell.setLeft(buildSidebar());
        shell.setCenter(buildMainContent());

        Scene scene = new Scene(shell, 1240, 820);
        Path css = root.resolve("glyvex-linux-onnx-stage/ui/glyvex.css");
        if (Files.isRegularFile(css)) scene.getStylesheets().add(css.toUri().toString());
        stage.setTitle("GLYVEX | Touchpad Gesture Runtime");
        stage.setMinWidth(1000);
        stage.setMinHeight(700);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> stopRuntime(false));
        stage.show();
        refreshDeviceStatus();
        appendLog("GLYVEX desktop console ready.");
        appendLog("Commands run as your desktop user. Shell operators are not interpreted implicitly.");
    }

    private VBox buildSidebar() {
        VBox sidebar = new VBox(18);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(28, 20, 22, 20));
        sidebar.setPrefWidth(238);

        Label brand = new Label("GLYVEX");
        brand.getStyleClass().add("brand");
        Label subtitle = new Label("GESTURE RUNTIME");
        subtitle.getStyleClass().add("eyebrow");
        VBox brandBox = new VBox(3, brand, subtitle);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label runtimeLabel = new Label("LOCAL LINUX ENGINE");
        runtimeLabel.getStyleClass().add("sidebar-caption");
        Label runtimeInfo = new Label("C evdev  ·  Java 17+\nONNX Runtime  ·  JavaFX");
        runtimeInfo.getStyleClass().add("sidebar-info");
        sidebar.getChildren().addAll(brandBox, navHint("01", "Dashboard"), navHint("02", "Command mappings"), navHint("03", "Activity console"), spacer, runtimeLabel, runtimeInfo);
        return sidebar;
    }

    private HBox navHint(String number, String title) {
        Label n = new Label(number);
        n.getStyleClass().add("nav-number");
        Label t = new Label(title);
        t.getStyleClass().add("nav-title");
        HBox row = new HBox(12, n, t);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("nav-hint");
        return row;
    }

    private VBox buildMainContent() {
        VBox content = new VBox(18);
        content.getStyleClass().add("main-content");
        content.setPadding(new Insets(28, 30, 30, 30));

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox heading = new VBox(4, styledLabel("DESKTOP CONTROL CENTER", "eyebrow"), styledLabel("Gesture command workspace", "page-title"));
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        Button refresh = new Button("Refresh device");
        refresh.getStyleClass().add("button-secondary");
        refresh.setOnAction(e -> refreshDeviceStatus());
        header.getChildren().addAll(heading, push, refresh);

        HBox statusCards = new HBox(12,
                metricCard("RUNTIME", runtimeState, "Process state"),
                metricCard("TOUCHPAD", deviceState, "Detected Linux input device"),
                metricCard("EXECUTION MODE", modeState, "Safe by default"));
        HBox.setHgrow(statusCards.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(statusCards.getChildren().get(1), Priority.ALWAYS);
        HBox.setHgrow(statusCards.getChildren().get(2), Priority.ALWAYS);

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("main-tabs");
        Tab dashboard = new Tab("Overview", buildDashboard());
        Tab commands = new Tab("Command mappings", buildCommands());
        Tab activity = new Tab("Activity", buildActivity());
        Tab settings = new Tab("Settings", buildSettings());
        for (Tab t : List.of(dashboard, commands, activity, settings)) t.setClosable(false);
        tabs.getTabs().addAll(dashboard, commands, activity, settings);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        content.getChildren().addAll(header, statusCards, tabs);
        return content;
    }

    private VBox metricCard(String title, Label value, String foot) {
        VBox card = new VBox(8, styledLabel(title, "metric-title"), value, styledLabel(foot, "muted"));
        card.getStyleClass().add("metric-card");
        value.getStyleClass().add("metric-value");
        card.setMinHeight(112);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private VBox buildDashboard() {
        VBox panel = new VBox(18);
        panel.setPadding(new Insets(18, 2, 2, 2));

        HBox controls = new HBox(10, startButton, stopButton);
        startButton.getStyleClass().add("button-primary");
        stopButton.getStyleClass().add("button-secondary");
        stopButton.setDisable(true);
        startButton.setOnAction(e -> startRuntime());
        stopButton.setOnAction(e -> stopRuntime(true));

        VBox runtimeCard = new VBox(12,
                styledLabel("RUNTIME CONTROL", "eyebrow"),
                styledLabel("Listen for a character gesture", "section-title"),
                new Label("Start the Linux evdev reader, four-corner activation state machine, rasterizer and ONNX classifier."),
                controls,
                liveExecution,
                styledLabel("Dry-run is the default. Live mode launches the saved command for an accepted character.", "muted"));
        runtimeCard.getStyleClass().add("panel-card");
        liveExecution.setOnAction(e -> {
            if (liveExecution.isSelected()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Accepted gestures will launch the commands saved in Command mappings as your logged-in Linux user. Only enable this for commands you trust.",
                        ButtonType.CANCEL, ButtonType.OK);
                confirm.setHeaderText("Enable live command execution?");
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) liveExecution.setSelected(false);
            }
            modeState.setText(liveExecution.isSelected() ? "LIVE" : "DRY RUN");
        });

        GridPane results = new GridPane();
        results.setHgap(12);
        results.setVgap(12);
        results.add(resultCard("LAST CHARACTER", predictedChar), 0, 0);
        results.add(resultCard("CONFIDENCE", confidenceValue), 1, 0);
        results.add(resultCard("DECISION", decisionValue), 2, 0);
        for (int i = 0; i < 3; i++) GridPane.setHgrow(results.getChildren().get(i), Priority.ALWAYS);

        VBox stateCard = new VBox(8, styledLabel("CURRENT STATE", "eyebrow"), stateDetail);
        stateCard.getStyleClass().add("panel-card");
        panel.getChildren().addAll(runtimeCard, results, stateCard);
        return panel;
    }

    private VBox resultCard(String title, Label value) {
        VBox card = new VBox(8, styledLabel(title, "metric-title"), value);
        card.getStyleClass().add("result-card");
        value.getStyleClass().add("result-value");
        return card;
    }

    private VBox buildCommands() {
        VBox rootBox = new VBox(15);
        rootBox.setPadding(new Insets(18, 2, 2, 2));
        Label title = styledLabel("Character → Linux command", "section-title");
        Label note = styledLabel("Paste an executable and its arguments. Commands are stored in config/glyvex-config.json and applied on the next runtime start.", "muted");
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(11);
        ColumnConstraints c1 = new ColumnConstraints(64);
        ColumnConstraints c2 = new ColumnConstraints(190);
        ColumnConstraints c3 = new ColumnConstraints();
        c3.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c1, c2, c3);
        grid.add(styledLabel("KEY", "table-head"), 0, 0);
        grid.add(styledLabel("ACTION", "table-head"), 1, 0);
        grid.add(styledLabel("COMMAND + ARGUMENTS", "table-head"), 2, 0);
        Map<String, String> names = Map.of("B", "Browser", "C", "Calculator", "E", "Text editor", "F", "File manager", "L", "Lock screen", "S", "System settings", "T", "Terminal");
        for (int i = 0; i < SYMBOLS.size(); i++) {
            String symbol = SYMBOLS.get(i);
            Label key = new Label(symbol);
            key.getStyleClass().add("key-chip");
            grid.add(key, 0, i + 1);
            grid.add(new Label(names.get(symbol)), 1, i + 1);
            TextField field = commandFields.getOrDefault(symbol, new TextField());
            field.setPromptText("e.g. xdg-open https://example.com");
            field.getStyleClass().add("command-field");
            commandFields.put(symbol, field);
            grid.add(field, 2, i + 1);
        }
        Button save = new Button("Save mappings");
        save.getStyleClass().add("button-primary");
        save.setOnAction(e -> saveSettings());
        Label safe = styledLabel("No implicit shell expansion: pipes, redirects and ; are not interpreted. For an intentional shell command, explicitly use /bin/bash -lc \"your command\".", "warning-note");
        VBox box = new VBox(16, title, note, grid, new HBox(12, save), safe);
        box.getStyleClass().add("panel-card");
        rootBox.getChildren().add(box);
        return rootBox;
    }

    private VBox buildActivity() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(18, 2, 2, 2));
        HBox bar = new HBox(10, styledLabel("RUNTIME OUTPUT", "eyebrow"));
        Region push = new Region(); HBox.setHgrow(push, Priority.ALWAYS);
        Button clear = new Button("Clear log");
        clear.getStyleClass().add("button-secondary");
        clear.setOnAction(e -> logArea.clear());
        bar.getChildren().addAll(push, clear);
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.getStyleClass().add("log-area");
        VBox.setVgrow(logArea, Priority.ALWAYS);
        box.getChildren().addAll(bar, logArea);
        return box;
    }

    private VBox buildSettings() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(18, 2, 2, 2));
        VBox card = new VBox(10,
                styledLabel("MODEL CONFIGURATION", "eyebrow"),
                styledLabel("Confidence threshold override", "section-title"),
                new Label("The runtime rejects a prediction below this threshold. Keep it calibrated against labelled touchpad samples."),
                thresholdField,
                styledLabel("A threshold override of 0.88 is currently configured in the project JSON.", "muted"));
        card.getStyleClass().add("panel-card");
        Button save = new Button("Save settings"); save.getStyleClass().add("button-primary"); save.setOnAction(e -> saveSettings());
        box.getChildren().addAll(card, save);
        return box;
    }

    private void loadSettingsIntoUi() {
        try {
            JsonNode rootNode = mapper.readTree(configPath.toFile());
            JsonNode commands = rootNode.path("commands");
            for (String symbol : SYMBOLS) {
                JsonNode value = commands.path(symbol);
                String command = "";
                if (value.isArray()) {
                    List<String> args = new ArrayList<>(); value.forEach(n -> args.add(n.asText()));
                    command = joinCommand(args);
                } else if (value.isTextual()) command = value.asText();
                if (command.isBlank()) command = defaultCommand(symbol);
                commandFields.putIfAbsent(symbol, new TextField());
                commandFields.get(symbol).setText(command);
            }
            String threshold = rootNode.path("ml").path("overrideThreshold").isNumber()
                    ? rootNode.path("ml").path("overrideThreshold").asText() : "0.88";
            thresholdField.setText(threshold);
            // The desktop UI always opens in safe mode. Live execution requires
            // the user to opt in during this session and confirm the action.
            liveExecution.setSelected(false);
            modeState.setText("DRY RUN");
        } catch (Exception e) {
            appendLog("Could not load config; using default command mappings: " + e.getMessage());
            for (String s : SYMBOLS) commandFields.putIfAbsent(s, new TextField(defaultCommand(s)));
        }
    }

    private String defaultCommand(String symbol) {
        return switch (symbol) {
            case "B" -> "xdg-open https://google.com";
            case "C" -> "gnome-calculator";
            case "E" -> "gedit";
            case "F" -> "xdg-open .";
            case "L" -> "loginctl lock-session";
            case "S" -> "gnome-control-center";
            case "T" -> "x-terminal-emulator";
            default -> "";
        };
    }

    private void saveSettings() {
        try {
            ObjectNode document = (ObjectNode) mapper.readTree(configPath.toFile());
            ObjectNode commands = document.with("commands");
            for (String symbol : SYMBOLS) {
                List<String> parsed = parseCommandLine(commandFields.get(symbol).getText());
                if (parsed.isEmpty()) throw new IllegalArgumentException("Command for " + symbol + " cannot be empty.");
                ArrayNode array = mapper.createArrayNode(); parsed.forEach(array::add);
                commands.set(symbol, array);
            }
            double threshold = Double.parseDouble(thresholdField.getText().trim());
            if (!(threshold > 0.0 && threshold <= 1.0)) throw new IllegalArgumentException("Confidence threshold must be > 0 and <= 1.");
            document.with("ml").put("overrideThreshold", threshold);
            document.put("dryRun", !liveExecution.isSelected());
            Path temp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), document);
            Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
            modeState.setText(liveExecution.isSelected() ? "LIVE" : "DRY RUN");
            appendLog("Saved command mappings and settings to " + configPath);
            showInfo("Settings saved", "Mappings are saved. Restart the runtime to apply changes.");
        } catch (Exception e) {
            showError("Could not save settings", e.getMessage());
        }
    }

    private void startRuntime() {
        if (runtimeProcess != null && runtimeProcess.isAlive()) return;
        if (liveExecution.isSelected()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Live mode is enabled. Every accepted character can launch its mapped command. Continue?",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setHeaderText("Start GLYVEX in LIVE mode?");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        Path input = findReadableTouchpad();
        if (input == null) {
            showError("Touchpad unavailable", "No readable Linux touchpad event device was found.\n\nRun this from a terminal:\ncd \"" + root + "\"\nsudo ./scripts/setup_udev_rules.sh\n\nThen log out and back in, and reopen GLYVEX.");
            refreshDeviceStatus();
            return;
        }
        try {
            saveSettingsSilently();
            Path runScript = root.resolve("scripts/run.sh");
            if (!Files.isRegularFile(runScript)) throw new IllegalStateException("Missing " + runScript);
            List<String> command = new ArrayList<>(List.of("/bin/bash", runScript.toString()));
            command.add(liveExecution.isSelected() ? "--execute" : "--dry-run");
            ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
            runtimeProcess = builder.start();
            Process launchedProcess = runtimeProcess;
            stopping.set(false);
            runtimeState.setText("RUNNING");
            stateDetail.setText("Touchpad runtime started. Complete TL → TR → BR → BL to arm capture.");
            startButton.setDisable(true); stopButton.setDisable(false);
            appendLog("Starting: " + String.join(" ", command));
            outputThread = new Thread(() -> readRuntimeOutput(launchedProcess), "glyvex-ui-runtime-output");
            outputThread.setDaemon(true); outputThread.start();
        } catch (Exception e) {
            runtimeState.setText("ERROR");
            showError("Runtime could not start", e.getMessage());
        }
    }

    private void saveSettingsSilently() throws Exception {
        ObjectNode document = (ObjectNode) mapper.readTree(configPath.toFile());
        ObjectNode commands = document.with("commands");
        for (String symbol : SYMBOLS) {
            List<String> parsed = parseCommandLine(commandFields.get(symbol).getText());
            if (parsed.isEmpty()) throw new IllegalArgumentException("Command for " + symbol + " cannot be empty.");
            ArrayNode array = mapper.createArrayNode(); parsed.forEach(array::add); commands.set(symbol, array);
        }
        double threshold = Double.parseDouble(thresholdField.getText().trim());
        if (!(threshold > 0.0 && threshold <= 1.0)) throw new IllegalArgumentException("Confidence threshold must be > 0 and <= 1.");
        document.with("ml").put("overrideThreshold", threshold);
        document.put("dryRun", !liveExecution.isSelected());
        Path temp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), document);
        Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private void readRuntimeOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String captured = line;
                Platform.runLater(() -> handleRuntimeLine(captured));
            }
        } catch (Exception e) {
            Platform.runLater(() -> appendLog("Output reader stopped: " + e.getMessage()));
        }
        try { int exit = process.waitFor(); Platform.runLater(() -> runtimeExited(exit)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void handleRuntimeLine(String line) {
        appendLog(line);
        if (line.contains("STARTING ON PHYSICAL TOUCHPAD")) {
            runtimeState.setText("LISTENING"); stateDetail.setText("Touchpad input stream is active.");
        }
        if (line.contains("CORNER") || line.contains("ACTIVATION") || line.contains("CAPTURE") || line.contains("GESTURE COMPLETE")) stateDetail.setText(line.trim());
        Matcher p = PREDICTION.matcher(line); if (p.find()) predictedChar.setText(p.group(1));
        Matcher c = CONFIDENCE.matcher(line); if (c.find()) confidenceValue.setText(String.format(Locale.ROOT, "%.2f%%", Double.parseDouble(c.group(1)) * 100));
        Matcher d = DECISION.matcher(line); if (d.find()) decisionValue.setText(d.group(1));
        if (line.contains("DRY RUN") || line.contains("--dry-run")) modeState.setText("DRY RUN");
        if (line.contains("FATAL") || line.contains("ERROR")) runtimeState.setText("ERROR");
    }

    private void runtimeExited(int exit) {
        if (stopping.get()) return;
        runtimeState.setText(exit == 0 ? "STOPPED" : "EXITED " + exit);
        stateDetail.setText(exit == 0 ? "Runtime stopped." : "Runtime exited unexpectedly. See Activity for details.");
        startButton.setDisable(false); stopButton.setDisable(true); runtimeProcess = null;
        appendLog("Runtime process exited with code " + exit);
    }

    private void stopRuntime(boolean announce) {
        Process p = runtimeProcess;
        if (p == null || !p.isAlive()) return;
        stopping.set(true);
        try {
            p.toHandle().descendants().forEach(ph -> { try { ph.destroy(); } catch (Exception ignored) {} });
            p.destroy();
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.toHandle().descendants().forEach(ph -> { try { ph.destroyForcibly(); } catch (Exception ignored) {} });
                p.destroyForcibly();
            }
        } catch (Exception ignored) { p.destroyForcibly(); }
        runtimeProcess = null;
        runtimeState.setText("STOPPED");
        stateDetail.setText("Runtime stopped by user.");
        startButton.setDisable(false); stopButton.setDisable(true);
        if (announce) appendLog("Runtime stop requested.");
    }

    private Path findReadableTouchpad() {
        Path dev = Paths.get("/dev/input");
        if (!Files.isDirectory(dev)) return null;
        try (Stream<Path> paths = Files.list(dev)) {
            return paths.filter(p -> p.getFileName().toString().matches("event\\d+"))
                    .filter(p -> {
                        Path name = Paths.get("/sys/class/input", p.getFileName().toString(), "device/name");
                        try {
                            String value = Files.readString(name).toLowerCase(Locale.ROOT);
                            return value.contains("touchpad") || value.contains("trackpad") || value.contains("synaptics") || value.contains("elan") || value.contains("alps");
                        } catch (Exception e) { return false; }
                    }).filter(Files::isReadable).findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    private void refreshDeviceStatus() {
        Path device = findReadableTouchpad();
        if (device == null) {
            deviceState.setText("Not ready");
            deviceState.setTextFill(Color.web("#f0a35e"));
        } else {
            deviceState.setText(device.getFileName().toString());
            deviceState.setTextFill(Color.web("#56c79a"));
        }
    }

    private List<String> parseCommandLine(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0; boolean escaping = false; boolean started = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (escaping) { token.append(ch); escaping = false; started = true; continue; }
            if (ch == '\\' && quote != '\'') { escaping = true; started = true; continue; }
            if (quote != 0) {
                if (ch == quote) quote = 0; else token.append(ch);
                started = true; continue;
            }
            if (ch == '\'' || ch == '"') { quote = ch; started = true; continue; }
            if (Character.isWhitespace(ch)) {
                if (started) { out.add(token.toString()); token.setLength(0); started = false; }
            } else { token.append(ch); started = true; }
        }
        if (escaping) token.append('\\');
        if (quote != 0) throw new IllegalArgumentException("Unclosed quote in command.");
        if (started) out.add(token.toString());
        return out;
    }

    private String joinCommand(List<String> args) {
        List<String> quoted = new ArrayList<>();
        for (String arg : args) quoted.add(arg.matches(".*\\s+.*") ? "\"" + arg.replace("\"", "\\\"") + "\"" : arg);
        return String.join(" ", quoted);
    }

    private void appendLog(String line) {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> appendLog(line)); return; }
        String time = LocalTime.now().format(CLOCK);
        logArea.appendText("[" + time + "] " + line + System.lineSeparator());
        logArea.positionCaret(logArea.getLength());
    }

    private Label styledLabel(String text, String style) { Label label = new Label(text); label.getStyleClass().add(style); return label; }
    private void showInfo(String title, String message) { Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK); a.setHeaderText(title); a.showAndWait(); }
    private void showError(String title, String message) { Alert a = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK); a.setHeaderText(title); a.showAndWait(); }

    @Override public void stop() { stopRuntime(false); }

}
