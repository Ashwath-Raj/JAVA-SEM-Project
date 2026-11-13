package com.glyvex.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * GLYPHX desktop shell. All interaction is native JavaFX; the gesture preview is the
 * real raster written by the inference pipeline, never a generated illustration.
 */
public final class GlyvexDesktopApp extends Application {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern PREDICTION = Pattern.compile("Prediction:\\s*([A-Za-z0-9])");
    private static final Pattern CONFIDENCE = Pattern.compile("Confidence:\\s*([0-9.]+)");
    private static final Pattern DECISION = Pattern.compile("Decision:\\s*(ACCEPT|REJECT)");
    private static final Pattern RASTER = Pattern.compile("Debug Raster:\\s*(.+)$");
    private static final List<String> FALLBACK_CLASSES = List.of("B", "C", "E", "F", "L", "S", "T");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, MappingEditor> editors = new LinkedHashMap<>();
    private final TextArea console = new TextArea();
    private final Label runtimeValue = new Label("Stopped");
    private final Label runtimeHint = new Label("Gesture recognition is not active.");
    private final Label touchpadValue = new Label("Checking device");
    private final Label executionValue = new Label("Dry Run");
    private final Label recognizedCharacter = new Label("—");
    private final Label confidenceValue = new Label("—");
    private final Label decisionValue = new Label("Waiting for a gesture");
    private final Label mappedCommandValue = new Label("No command executed yet");
    private final ImageView gestureImage = new ImageView();
    private final Label gesturePlaceholder = new Label("The actual touch trajectory will appear here after recognition.");
    private final CheckBox liveExecution = new CheckBox("Enable live command execution");
    private final TextField thresholdField = new TextField("0.88");
    private final ComboBox<String> globalProfileSelector = new ComboBox<>();
    private final Button startButton = new Button("Start Runtime");
    private final Button stopButton = new Button("Stop");
    private final VBox contentHost = new VBox();
    private final Map<String, Button> navigation = new LinkedHashMap<>();

    private Path projectRoot;
    private Path configPath;
    private ObjectNode config;
    private ObjectNode profiles;
    private String activeProfile = "Profile 1";
    private List<String> modelClasses = FALLBACK_CLASSES;
    private String currentPage = "Runtime";
    private String currentTheme = "light";
    private BorderPane rootShell;
    private Process runtimeProcess;
    private Thread outputThread;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    @Override
    public void start(Stage primaryStage) {
        projectRoot = Paths.get(System.getProperty("glyvex.root", ".")).toAbsolutePath().normalize();
        configPath = projectRoot.resolve("config/glyvex-config.json");
        try {
            loadConfiguration();
            loadModelClasses();
            ensureProfiles();
            loadSettingsIntoEditors();
            persistConfiguration();
        } catch (Exception ex) {
            showFatal(primaryStage, "Configuration could not be loaded", ex.getMessage());
            return;
        }

        Stage splash = createSplash(primaryStage);
        splash.show();
        PauseTransition pause = new PauseTransition(Duration.millis(650));
        pause.setOnFinished(event -> {
            splash.close();
            showMainWindow(primaryStage);
        });
        pause.play();
    }

    private Stage createSplash(Stage owner) {
        Stage splash = new Stage(StageStyle.UNDECORATED);
        splash.initOwner(owner);
        VBox card = new VBox(14);
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(30, 48, 30, 48));
        card.getStyleClass().addAll("splash", themeClass());
        card.getChildren().addAll(brandLockup(true), label("TOUCHPAD GESTURE RUNTIME", "splash-subtitle"), new ProgressIndicator());
        Scene scene = new Scene(card, 390, 250);
        applyStyles(scene);
        splash.setScene(scene);
        splash.centerOnScreen();
        return splash;
    }

    private void showMainWindow(Stage stage) {
        BorderPane shell = new BorderPane();
        rootShell = shell;
        shell.getStyleClass().addAll("app-shell", themeClass());
        shell.setLeft(buildSidebar());
        contentHost.getStyleClass().add("content-host");
        shell.setCenter(contentHost);
        Scene scene = new Scene(shell, 1320, 820);
        applyStyles(scene);
        stage.setTitle("GLYPHX | Touchpad Gesture Runtime");
        stage.setMinWidth(1120);
        stage.setMinHeight(720);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> stopRuntime(false));
        stage.show();
        refreshDeviceStatus();
        navigate("Runtime");
        appendLog("GLYPHX desktop runtime initialized.");
        appendLog("Native JavaFX interface · Linux evdev · ONNX Runtime");
    }

    private void applyStyles(Scene scene) {
        Path css = projectRoot.resolve("glyvex-linux-onnx-stage/ui/glyvex.css");
        if (Files.isRegularFile(css)) scene.getStylesheets().add(css.toUri().toString());
    }

    private String themeClass() { return "theme-" + currentTheme; }

    private VBox buildSidebar() {
        VBox sidebar = new VBox(12);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(26, 16, 20, 16));
        sidebar.setPrefWidth(226);
        sidebar.setMinWidth(226);
        sidebar.getChildren().add(brandLockup(false));
        Region spacer = new Region();
        spacer.setMinHeight(24);
        sidebar.getChildren().add(spacer);
        addNavigation(sidebar, "Runtime", "play");
        addNavigation(sidebar, "Profiles", "layers");
        addNavigation(sidebar, "Command Mapping", "terminal");
        addNavigation(sidebar, "Settings", "settings");
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        VBox foot = new VBox(5,
                label("GLYPHX  ·  v1.0", "sidebar-foot-title"),
                label("Linux  ·  JavaFX  ·  ONNX", "sidebar-foot"),
                label("Native touchpad runtime", "sidebar-foot"));
        sidebar.getChildren().addAll(grow, foot);
        return sidebar;
    }

    private VBox brandLockup(boolean large) {
        VBox lockup = new VBox(large ? 11 : 7);
        lockup.setAlignment(Pos.CENTER_LEFT);
        HBox row = new HBox(large ? 13 : 10);
        row.setAlignment(Pos.CENTER_LEFT);
        StackPane mark = trackpadMark(large ? 54 : 43);
        Label word = new Label("GLYPHX");
        word.getStyleClass().add(large ? "brand-word-large" : "brand-word");
        word.setStyle("-fx-font-style: italic;");
        row.getChildren().addAll(mark, word);
        Label descriptor = label("TOUCHPAD GESTURE RUNTIME", large ? "splash-subtitle" : "brand-caption");
        lockup.getChildren().addAll(row, descriptor);
        return lockup;
    }

    private StackPane trackpadMark(double size) {
        StackPane frame = new StackPane();
        frame.setMinSize(size, size * 0.78);
        frame.setPrefSize(size, size * 0.78);
        frame.setMaxSize(size, size * 0.78);
        Rectangle outline = new Rectangle(size - 5, size * 0.68);
        outline.getStyleClass().add("brand-mark-outline");
        outline.setArcWidth(9);
        outline.setArcHeight(9);
        outline.setRotate(-8);
        Line swipe = new Line(-8, 0, 8, 0);
        swipe.getStyleClass().add("brand-mark-line");
        swipe.setStrokeWidth(2.7);
        swipe.setTranslateY(size * 0.12);
        swipe.setRotate(-8);
        frame.getChildren().addAll(outline, swipe);
        return frame;
    }

    private void addNavigation(VBox sidebar, String page, String iconName) {
        Label icon = label(iconFor(iconName), "nav-icon");
        Label text = label(page, "nav-text");
        HBox graphic = new HBox(13, icon, text);
        graphic.setAlignment(Pos.CENTER_LEFT);
        Button button = new Button();
        button.setGraphic(graphic);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().add("nav-button");
        button.setOnAction(event -> navigate(page));
        navigation.put(page, button);
        sidebar.getChildren().add(button);
    }

    private String iconFor(String name) {
        return switch (name) {
            case "play" -> "▷";
            case "layers" -> "▱";
            case "terminal" -> "⌘";
            case "settings" -> "⚙";
            default -> "·";
        };
    }

    private void navigate(String page) {
        if ("Command Mapping".equals(currentPage) && !page.equals(currentPage) && runtimeProcess == null) {
            try {
                saveCurrentMappingsSilently();
                persistConfiguration();
            } catch (Exception ex) {
                showError("Could not preserve command edits", ex.getMessage());
                return;
            }
        }
        if (runtimeProcess != null && runtimeProcess.isAlive() && !page.equals("Runtime")) {
            showError("Runtime is active", "Stop the touchpad runtime before changing profiles, command mappings, or runtime settings.");
            return;
        }
        currentPage = page;
        navigation.forEach((name, button) -> {
            button.getStyleClass().remove("nav-active");
            if (name.equals(page)) button.getStyleClass().add("nav-active");
        });
        contentHost.getChildren().clear();
        contentHost.getChildren().add(switch (page) {
            case "Profiles" -> buildProfilesPage();
            case "Command Mapping" -> buildCommandMappingPage();
            case "Settings" -> buildSettingsPage();
            default -> buildRuntimePage();
        });
    }

    private VBox pageFrame(String eyebrow, String title, String subtitle) {
        VBox frame = new VBox(17);
        frame.setPadding(new Insets(27, 30, 28, 30));
        HBox header = new HBox(15);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox titleBlock = new VBox(5, label(eyebrow.toUpperCase(Locale.ROOT), "eyebrow"), label(title, "page-title"), label(subtitle, "page-subtitle"));
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        globalProfileSelector.getItems().setAll(profiles == null ? List.of(activeProfile) : iterableKeys(profiles));
        globalProfileSelector.setValue(activeProfile);
        globalProfileSelector.setOnAction(event -> {
            String selected = globalProfileSelector.getValue();
            if (selected != null && !selected.equals(activeProfile)) switchProfile(selected);
        });
        globalProfileSelector.getStyleClass().add("profile-selector");
        VBox profileWrap = new VBox(4, label("ACTIVE PROFILE", "field-caption"), globalProfileSelector);
        profileWrap.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(titleBlock, push, profileWrap);
        frame.getChildren().add(header);
        return frame;
    }

    private VBox buildRuntimePage() {
        VBox frame = pageFrame("Runtime", "Gesture Runtime", "Capture  ·  Recognize  ·  Execute");
        HBox body = new HBox(18);
        body.setAlignment(Pos.TOP_LEFT);
        VBox left = buildRuntimeControls();
        HBox.setHgrow(left, Priority.SOMETIMES);
        left.setPrefWidth(510);
        VBox right = buildRecognitionCard();
        HBox.setHgrow(right, Priority.ALWAYS);
        body.getChildren().addAll(left, right);
        VBox.setVgrow(body, Priority.ALWAYS);
        frame.getChildren().add(body);
        return frame;
    }

    private VBox buildRuntimeControls() {
        VBox left = new VBox(18);
        left.getStyleClass().add("runtime-left");
        VBox state = new VBox(8,
                label("RUNTIME", "eyebrow"),
                runtimeValue,
                runtimeHint);
        runtimeValue.getStyleClass().setAll("runtime-state");
        runtimeHint.getStyleClass().setAll("page-subtitle");
        HBox buttons = new HBox(10, startButton, stopButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        startButton.getStyleClass().setAll("button-primary", "start-button");
        stopButton.getStyleClass().setAll("button-secondary", "stop-button");
        startButton.setOnAction(event -> startRuntime());
        stopButton.setOnAction(event -> stopRuntime(true));
        stopButton.setDisable(true);
        VBox controls = new VBox(14, label("RUNTIME CONTROL", "eyebrow"), buttons, liveExecution,
                label("Dry Run is the default. Live mode launches the saved command only after an accepted gesture.", "helper-text"));
        controls.getStyleClass().add("surface-card");
        liveExecution.setSelected(false);
        liveExecution.setOnAction(event -> {
            if (liveExecution.isSelected()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Every accepted gesture can launch its assigned command. Commands marked Run as sudo will request administrator authentication through PolicyKit. Continue?",
                        ButtonType.CANCEL, ButtonType.OK);
                confirm.setHeaderText("Enable live command execution?");
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) liveExecution.setSelected(false);
            }
            updateExecutionMode();
        });

        HBox deviceTile = statusTile("TOUCHPAD", touchpadValue, "Detected Linux input event");
        HBox modeTile = statusTile("EXECUTION", executionValue, "Command policy");
        HBox profileTile = statusTile("PROFILE", new Label(activeProfile), "Independent command set");
        HBox statusRow = new HBox(10, deviceTile, profileTile, modeTile);
        for (javafx.scene.Node node : statusRow.getChildren()) HBox.setHgrow(node, Priority.ALWAYS);

        VBox info = new VBox(8, label("CURRENT STATE", "eyebrow"),
                label("The four-corner sequence arms gesture capture.", "info-strong"),
                label("Tap Top-left → Top-right → Bottom-right → Bottom-left, then draw a supported character on the touchpad.", "helper-text"));
        info.getStyleClass().add("surface-card");
        left.getChildren().addAll(state, controls, statusRow, info);
        VBox.setVgrow(info, Priority.ALWAYS);
        return left;
    }

    private HBox statusTile(String eyebrow, Label value, String detail) {
        VBox texts = new VBox(5, label(eyebrow, "field-caption"), value, label(detail, "helper-text"));
        texts.getStyleClass().add("status-tile");
        value.getStyleClass().add("status-value");
        HBox row = new HBox(texts);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(texts, Priority.ALWAYS);
        return row;
    }

    private VBox buildRecognitionCard() {
        VBox card = new VBox(17);
        card.getStyleClass().addAll("recognition-card", "reinforced-card");
        Label heading = label("LAST RECOGNITION", "eyebrow");
        HBox summary = new HBox(22);
        summary.setAlignment(Pos.CENTER_LEFT);
        VBox charBlock = new VBox(2, label("CHARACTER", "field-caption"), recognizedCharacter);
        recognizedCharacter.getStyleClass().setAll("recognized-character");
        Region divider = new Region();
        divider.getStyleClass().add("vertical-rule");
        VBox confidenceBlock = new VBox(2, label("CONFIDENCE", "field-caption"), confidenceValue);
        confidenceValue.getStyleClass().setAll("confidence-value");
        summary.getChildren().addAll(charBlock, divider, confidenceBlock);
        VBox.setVgrow(divider, Priority.ALWAYS);

        StackPane preview = new StackPane();
        preview.getStyleClass().add("gesture-preview");
        preview.setMinHeight(275);
        gestureImage.setPreserveRatio(true);
        gestureImage.setSmooth(true);
        gestureImage.setFitWidth(540);
        gestureImage.setFitHeight(250);
        gestureImage.setVisible(false);
        gesturePlaceholder.getStyleClass().add("gesture-placeholder");
        gesturePlaceholder.setWrapText(true);
        gesturePlaceholder.setMaxWidth(320);
        preview.getChildren().addAll(gestureImage, gesturePlaceholder);
        VBox decision = new VBox(7, label("DECISION", "field-caption"), decisionValue, mappedCommandValue);
        decision.getStyleClass().add("decision-block");
        decisionValue.getStyleClass().setAll("decision-value", "decision-idle");
        mappedCommandValue.getStyleClass().setAll("mapped-command");
        card.getChildren().addAll(heading, summary, preview, decision);
        VBox.setVgrow(preview, Priority.ALWAYS);
        return card;
    }

    private VBox buildCommandMappingPage() {
        VBox frame = pageFrame("Profiles · Commands", "Command Mapping", "Assign a Linux command to each character the current model can recognize.");
        VBox card = new VBox(12);
        card.getStyleClass().add("surface-card");
        HBox profileBar = new HBox(10);
        profileBar.setAlignment(Pos.CENTER_LEFT);
        VBox labelBlock = new VBox(4, label("COMMAND SET", "eyebrow"), label("Editing: " + activeProfile, "section-title"));
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        Button manage = new Button("Manage profiles");
        manage.getStyleClass().add("button-secondary");
        manage.setOnAction(event -> navigate("Profiles"));
        profileBar.getChildren().addAll(labelBlock, push, manage);

        GridPane table = new GridPane();
        table.setHgap(11);
        table.setVgap(7);
        ColumnConstraints keyCol = new ColumnConstraints(54);
        ColumnConstraints actionCol = new ColumnConstraints(112);
        ColumnConstraints commandCol = new ColumnConstraints(260, 520, Double.MAX_VALUE);
        commandCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints sudoCol = new ColumnConstraints(98);
        ColumnConstraints shellCol = new ColumnConstraints(94);
        table.getColumnConstraints().addAll(keyCol, actionCol, commandCol, sudoCol, shellCol);
        addTableHeader(table, "KEY", 0);
        addTableHeader(table, "ACTION", 1);
        addTableHeader(table, "LINUX COMMAND", 2);
        addTableHeader(table, "RUN AS SUDO", 3);
        addTableHeader(table, "SHELL", 4);
        Map<String, String> names = Map.of("B", "Browser", "C", "Calculator", "E", "Editor", "F", "Files", "L", "Lock", "S", "Settings", "T", "Terminal");
        int row = 1;
        for (String symbol : modelClasses) {
            MappingEditor editor = editors.computeIfAbsent(symbol, key -> new MappingEditor(defaultCommand(key)));
            Label key = new Label(symbol);
            key.getStyleClass().add("key-chip");
            Label action = label(names.getOrDefault(symbol, "Custom action"), "mapping-action");
            editor.commandField.getStyleClass().add("command-field");
            editor.commandField.setPromptText("Paste a Linux command and arguments");
            editor.adminCheck.setText("sudo");
            editor.shellCheck.setText("shell");
            editor.adminCheck.getStyleClass().add("mapping-check");
            editor.shellCheck.getStyleClass().add("mapping-check");
            table.add(key, 0, row);
            table.add(action, 1, row);
            table.add(editor.commandField, 2, row);
            table.add(editor.adminCheck, 3, row);
            table.add(editor.shellCheck, 4, row);
            row++;
        }
        HBox.setHgrow(table, Priority.ALWAYS);
        VBox quickMap = buildQuickMappingForm();
        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Button reset = new Button("Restore profile defaults");
        reset.getStyleClass().add("button-secondary");
        reset.setOnAction(event -> restoreProfileDefaults());
        Button save = new Button("Save mappings");
        save.getStyleClass().add("button-primary");
        save.setOnAction(event -> saveCurrentMappings());
        actions.getChildren().addAll(reset, save);
        card.getChildren().addAll(profileBar, thinDivider(), table, thinDivider(), quickMap, actions);
        frame.getChildren().add(card);
        VBox.setVgrow(card, Priority.ALWAYS);
        return frame;
    }

    private void addTableHeader(GridPane grid, String text, int col) {
        Label label = label(text, "table-head");
        grid.add(label, col, 0);
    }

    private VBox buildQuickMappingForm() {
        VBox form = new VBox(9);
        Label title = label("PASTE A COMMAND", "eyebrow");
        Label note = label("Choose one recognized character, paste the command, then apply it to this profile.", "helper-text");
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER_LEFT);
        ComboBox<String> symbol = new ComboBox<>();
        symbol.getItems().setAll(modelClasses);
        if (!modelClasses.isEmpty()) symbol.setValue(modelClasses.get(0));
        symbol.setPrefWidth(82);
        TextField command = new TextField();
        command.setPromptText("e.g. sudo systemctl restart bluetooth  ·  xdg-open https://example.com");
        HBox.setHgrow(command, Priority.ALWAYS);
        CheckBox admin = new CheckBox("Run as sudo");
        CheckBox shell = new CheckBox("Shell syntax");
        Button apply = new Button("Apply command");
        apply.getStyleClass().add("button-primary");
        apply.setOnAction(event -> {
            String key = symbol.getValue();
            String raw = command.getText().trim();
            if (key == null || raw.isBlank()) {
                showError("Command is incomplete", "Choose a recognized character and paste a Linux command.");
                return;
            }
            MappingEditor editor = editors.get(key);
            editor.commandField.setText(raw);
            editor.adminCheck.setSelected(admin.isSelected() || startsWithSudo(raw));
            editor.shellCheck.setSelected(shell.isSelected() || containsShellOperators(raw));
            saveCurrentMappings();
            command.clear();
            admin.setSelected(false);
            shell.setSelected(false);
        });
        controls.getChildren().addAll(symbol, command, admin, shell, apply);
        form.getChildren().addAll(title, note, controls,
                label("Shell syntax enables Bash pipes, redirects and chaining. Run as sudo uses pkexec / PolicyKit for an authentication prompt.", "helper-text"));
        form.getStyleClass().add("quick-map-panel");
        return form;
    }

    private VBox buildProfilesPage() {
        VBox frame = pageFrame("Command profiles", "Profiles", "Keep separate command sets for different workflows.");
        HBox body = new HBox(16);
        body.setAlignment(Pos.TOP_LEFT);
        VBox listCard = new VBox(12);
        listCard.getStyleClass().add("surface-card");
        HBox listHead = new HBox(10, label("SAVED PROFILES", "eyebrow"));
        Region listPush = new Region(); HBox.setHgrow(listPush, Priority.ALWAYS);
        Button newProfile = smallButton("+ New");
        newProfile.setOnAction(event -> createProfile());
        listHead.getChildren().addAll(listPush, newProfile);
        VBox profileList = new VBox(8);
        for (String profile : iterableKeys(profiles)) {
            Button item = new Button(profile + (profile.equals(activeProfile) ? "   ✓" : ""));
            item.setMaxWidth(Double.MAX_VALUE);
            item.setAlignment(Pos.CENTER_LEFT);
            item.getStyleClass().add("profile-list-item");
            if (profile.equals(activeProfile)) item.getStyleClass().add("profile-list-active");
            item.setOnAction(event -> selectProfileInManager(profile));
            profileList.getChildren().add(item);
        }
        listCard.getChildren().addAll(listHead, profileList);
        listCard.setPrefWidth(310);
        listCard.setMinWidth(280);

        VBox detailCard = new VBox(16);
        detailCard.getStyleClass().addAll("surface-card", "profile-detail-card");
        Label detailTitle = label(activeProfile, "section-title");
        Label description = label("This profile stores its own independent set of character-to-command mappings.", "page-subtitle");
        GridPane stats = new GridPane();
        stats.setHgap(12); stats.setVgap(12);
        stats.add(profileStat("PROFILE NAME", activeProfile), 0, 0);
        stats.add(profileStat("MAPPED CHARACTERS", String.valueOf(commandCount(profileCommands(activeProfile)))), 1, 0);
        stats.add(profileStat("STATUS", "".equals(activeProfile) ? "—" : (activeProfile.equals(this.activeProfile) ? "Active" : "Saved")), 0, 1);
        stats.add(profileStat("STORAGE", "config/glyvex-config.json"), 1, 1);
        HBox actions = new HBox(9);
        Button activate = smallButton("Use profile");
        activate.setOnAction(event -> {
            if (ensureStoppedForProfileChange()) {
                activateProfile(activeProfile);
                navigate("Runtime");
            }
        });
        Button duplicate = smallButton("Duplicate"); duplicate.setOnAction(event -> duplicateProfile(activeProfile));
        Button rename = smallButton("Rename"); rename.setOnAction(event -> renameProfile(activeProfile));
        Button delete = smallButton("Delete"); delete.getStyleClass().add("button-danger"); delete.setOnAction(event -> deleteProfile(activeProfile));
        actions.getChildren().addAll(activate, duplicate, rename, delete);
        Label note = label("Switching profiles updates the active command table in the runtime configuration. Stop the runtime before switching so an active process never uses stale mappings.", "helper-text");
        note.setWrapText(true);
        detailCard.getChildren().addAll(detailTitle, description, thinDivider(), stats, actions, note);
        HBox.setHgrow(detailCard, Priority.ALWAYS);
        body.getChildren().addAll(listCard, detailCard);
        frame.getChildren().add(body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return frame;
    }

    private VBox profileStat(String title, String value) {
        VBox card = new VBox(6, label(title, "field-caption"), label(value, "info-strong"));
        card.getStyleClass().add("profile-stat");
        return card;
    }

    private VBox buildSettingsPage() {
        VBox frame = pageFrame("Preferences", "Settings", "Adjust the runtime without changing its recognition pipeline.");
        VBox card = new VBox(18);
        card.getStyleClass().add("surface-card");
        HBox themeRow = new HBox(20);
        themeRow.setAlignment(Pos.CENTER_LEFT);
        VBox themeText = new VBox(5, label("Appearance", "section-title"), label("Choose the monochrome interface treatment.", "helper-text"));
        Region push = new Region(); HBox.setHgrow(push, Priority.ALWAYS);
        ComboBox<String> themeChoice = new ComboBox<>();
        themeChoice.getItems().setAll("Light", "Dark");
        themeChoice.setValue(currentTheme.equals("dark") ? "Dark" : "Light");
        themeChoice.setOnAction(event -> {
            currentTheme = "Dark".equals(themeChoice.getValue()) ? "dark" : "light";
            config.with("ui").put("theme", currentTheme);
            try { persistConfiguration(); } catch (Exception ex) { showError("Could not save theme", ex.getMessage()); }
            refreshTheme();
        });
        themeRow.getChildren().addAll(themeText, push, themeChoice);

        HBox thresholdRow = new HBox(20);
        thresholdRow.setAlignment(Pos.CENTER_LEFT);
        VBox thresholdText = new VBox(5, label("Recognition threshold", "section-title"), label("Predictions below this confidence are rejected.", "helper-text"));
        Region thresholdPush = new Region(); HBox.setHgrow(thresholdPush, Priority.ALWAYS);
        thresholdField.setPrefWidth(110);
        thresholdField.setPromptText("0.88");
        thresholdRow.getChildren().addAll(thresholdText, thresholdPush, thresholdField);

        HBox notificationRow = new HBox(20);
        notificationRow.setAlignment(Pos.CENTER_LEFT);
        VBox notificationText = new VBox(5, label("Desktop notifications", "section-title"), label("The Linux notification daemon reports activation and recognition results.", "helper-text"));
        Region notificationPush = new Region(); HBox.setHgrow(notificationPush, Priority.ALWAYS);
        CheckBox notifications = new CheckBox("Enabled");
        notifications.setSelected(config.path("notificationsEnabled").asBoolean(true));
        notificationRow.getChildren().addAll(notificationText, notificationPush, notifications);

        Button save = new Button("Save settings");
        save.getStyleClass().add("button-primary");
        save.setOnAction(event -> {
            try {
                double value = Double.parseDouble(thresholdField.getText().trim());
                if (!(value > 0.0 && value <= 1.0)) throw new IllegalArgumentException("Threshold must be greater than 0 and at most 1.");
                config.with("ml").put("overrideThreshold", value);
                config.put("notificationsEnabled", notifications.isSelected());
                config.put("dryRun", !liveExecution.isSelected());
                persistConfiguration();
                appendLog("Settings saved.");
                showInfo("Settings saved", "Changes are saved. Restart the runtime to apply model and notification settings.");
            } catch (Exception ex) { showError("Could not save settings", ex.getMessage()); }
        });
        Label safety = label("Administrator commands are launched through pkexec / PolicyKit. GLYPHX itself never runs as root.", "helper-text");
        safety.setWrapText(true);
        card.getChildren().addAll(themeRow, thinDivider(), thresholdRow, thinDivider(), notificationRow, thinDivider(), safety, save);
        frame.getChildren().add(card);
        VBox.setVgrow(card, Priority.ALWAYS);
        return frame;
    }

    private void refreshTheme() {
        if (rootShell != null) {
            rootShell.getStyleClass().removeIf(value -> value.startsWith("theme-"));
            rootShell.getStyleClass().add(themeClass());
        }
        navigate(currentPage);
    }

    private void loadConfiguration() throws Exception {
        if (!Files.isRegularFile(configPath)) throw new IllegalStateException("Missing project configuration: " + configPath);
        JsonNode parsed = mapper.readTree(configPath.toFile());
        if (!(parsed instanceof ObjectNode object)) throw new IllegalArgumentException("Project config must be a JSON object.");
        config = object;
        currentTheme = config.path("ui").path("theme").asText("light").equalsIgnoreCase("dark") ? "dark" : "light";
    }

    private void loadModelClasses() {
        Path classesFile = projectRoot.resolve("Dataset/GLYVEX/ml/demo_retrain/onnx/classes.json");
        try {
            JsonNode names = mapper.readTree(classesFile.toFile()).path("class_names");
            List<String> result = new ArrayList<>();
            if (names.isArray()) names.forEach(node -> result.add(node.asText().trim().toUpperCase(Locale.ROOT)));
            if (!result.isEmpty()) modelClasses = List.copyOf(result);
        } catch (Exception ignored) { modelClasses = FALLBACK_CLASSES; }
    }

    private void ensureProfiles() {
        JsonNode node = config.path("profiles");
        if (node instanceof ObjectNode existing && existing.size() > 0) {
            profiles = existing;
        } else {
            profiles = mapper.createObjectNode();
            ObjectNode first = mapper.createObjectNode();
            JsonNode current = config.path("commands");
            first.set("commands", current.isObject() ? current.deepCopy() : defaultCommands());
            profiles.set("Profile 1", first);
            ObjectNode second = mapper.createObjectNode();
            ObjectNode secondCommands = (ObjectNode) first.path("commands").deepCopy();
            // A practical alternate starting profile: same tools, different browser destination/editor command.
            if (secondCommands.path("B").isObject()) {
                ((ObjectNode) secondCommands.path("B")).set("command", mapper.createArrayNode().add("xdg-open").add("https://github.com"));
            } else {
                secondCommands.set("B", commandEntry(List.of("xdg-open", "https://github.com"), false, false, "Browser"));
            }
            second.set("commands", secondCommands);
            profiles.set("Profile 2", second);
        }
        activeProfile = config.path("activeProfile").asText("Profile 1");
        if (!profiles.has(activeProfile)) activeProfile = iterableKeys(profiles).stream().findFirst().orElse("Profile 1");
        config.set("profiles", profiles);
        config.put("activeProfile", activeProfile);
        config.set("commands", profileCommands(activeProfile).deepCopy());
    }

    private ObjectNode defaultCommands() {
        ObjectNode commands = mapper.createObjectNode();
        commands.set("B", commandEntry(List.of("xdg-open", "https://google.com"), false, false, "Browser"));
        commands.set("C", commandEntry(List.of("gnome-calculator"), false, false, "Calculator"));
        commands.set("E", commandEntry(List.of("gedit"), false, false, "Editor"));
        commands.set("F", commandEntry(List.of("xdg-open", System.getProperty("user.home")), false, false, "Files"));
        commands.set("L", commandEntry(List.of("loginctl", "lock-session"), false, false, "Lock screen"));
        commands.set("S", commandEntry(List.of("gnome-control-center"), false, false, "Settings"));
        commands.set("T", commandEntry(List.of("x-terminal-emulator"), false, false, "Terminal"));
        return commands;
    }

    private ObjectNode commandEntry(List<String> command, boolean sudo, boolean shell, String name) {
        ObjectNode entry = mapper.createObjectNode();
        ArrayNode args = mapper.createArrayNode(); command.forEach(args::add);
        entry.set("command", args);
        entry.put("sudo", sudo);
        entry.put("shell", shell);
        entry.put("name", name);
        return entry;
    }

    private void loadSettingsIntoEditors() {
        editors.clear();
        ObjectNode commands = profileCommands(activeProfile);
        for (String symbol : modelClasses) {
            JsonNode value = commands.path(symbol);
            MappingEditor editor = new MappingEditor(defaultCommand(symbol));
            if (!value.isMissingNode() && !value.isNull()) {
                boolean admin = value.path("sudo").asBoolean(false);
                boolean shell = value.path("shell").asBoolean(false);
                JsonNode commandNode = value.isObject() ? value.path("command") : value;
                String command = "";
                if (commandNode.isArray()) {
                    List<String> args = new ArrayList<>(); commandNode.forEach(n -> args.add(n.asText()));
                    command = shell && args.size() == 1 ? args.get(0) : joinCommand(args);
                } else if (commandNode.isTextual()) command = commandNode.asText();
                if (!command.isBlank()) editor.commandField.setText(command);
                editor.adminCheck.setSelected(admin);
                editor.shellCheck.setSelected(shell);
            }
            editors.put(symbol, editor);
        }
        thresholdField.setText(config.path("ml").path("overrideThreshold").isNumber()
                ? config.path("ml").path("overrideThreshold").asText() : "0.88");
    }

    private String defaultCommand(String symbol) {
        return switch (symbol) {
            case "B" -> "xdg-open https://google.com";
            case "C" -> "gnome-calculator";
            case "E" -> "gedit";
            case "F" -> "xdg-open \"" + System.getProperty("user.home") + "\"";
            case "L" -> "loginctl lock-session";
            case "S" -> "gnome-control-center";
            case "T" -> "x-terminal-emulator";
            default -> "";
        };
    }

    private void saveCurrentMappings() {
        try {
            ObjectNode commands = mapper.createObjectNode();
            for (String symbol : modelClasses) {
                MappingEditor editor = editors.get(symbol);
                if (editor == null) continue;
                String raw = editor.commandField.getText().trim();
                if (raw.isBlank()) throw new IllegalArgumentException("Command for character " + symbol + " cannot be blank.");
                boolean shell = editor.shellCheck.isSelected();
                boolean admin = editor.adminCheck.isSelected();
                List<String> parsed;
                if (shell) {
                    if (containsSudoPrefix(raw)) { raw = stripSudoPrefixes(raw); admin = true; }
                    parsed = List.of(raw);
                } else {
                    parsed = parseCommandLine(raw);
                    if (!parsed.isEmpty() && basename(parsed.get(0)).equals("sudo")) {
                        parsed = new ArrayList<>(parsed.subList(1, parsed.size()));
                        admin = true;
                    }
                }
                if (parsed.isEmpty() || parsed.get(0).isBlank()) throw new IllegalArgumentException("Command for " + symbol + " is invalid.");
                editor.adminCheck.setSelected(admin);
                commands.set(symbol, commandEntry(parsed, admin, shell, symbol));
            }
            profiles.with(activeProfile).set("commands", commands);
            config.set("profiles", profiles);
            config.put("activeProfile", activeProfile);
            config.set("commands", commands.deepCopy());
            persistConfiguration();
            appendLog("Saved " + commands.size() + " command mappings to " + activeProfile + ".");
            showInfo("Mappings saved", "The command set for " + activeProfile + " has been saved.");
        } catch (Exception ex) { showError("Could not save mappings", ex.getMessage()); }
    }

    private void restoreProfileDefaults() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Restore the built-in Linux command set for this profile? Existing edits in this profile will be replaced.",
                ButtonType.CANCEL, ButtonType.OK);
        alert.setHeaderText("Restore profile defaults");
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        ObjectNode defaults = defaultCommands();
        for (String symbol : modelClasses) {
            MappingEditor editor = editors.get(symbol);
            if (editor == null) continue;
            JsonNode entry = defaults.path(symbol);
            editor.commandField.setText(joinCommand(nodeArgs(entry.path("command"))));
            editor.adminCheck.setSelected(false);
            editor.shellCheck.setSelected(false);
        }
        saveCurrentMappings();
    }

    private void createProfile() {
        if (!ensureStoppedForProfileChange()) return;
        TextInputDialog dialog = new TextInputDialog("Profile " + (profiles.size() + 1));
        dialog.setTitle("New profile");
        dialog.setHeaderText("Create an independent command profile");
        dialog.setContentText("Profile name:");
        dialog.showAndWait().ifPresent(name -> {
            String clean = cleanProfileName(name);
            if (clean.isBlank() || profiles.has(clean)) { showError("Invalid profile name", "Choose a non-empty name that is not already used."); return; }
            ObjectNode profile = mapper.createObjectNode();
            profile.set("commands", defaultCommands());
            profiles.set(clean, profile);
            activateProfile(clean);
            navigate("Profiles");
        });
    }

    private void duplicateProfile(String source) {
        if (!ensureStoppedForProfileChange()) return;
        TextInputDialog dialog = new TextInputDialog(source + " Copy");
        dialog.setTitle("Duplicate profile");
        dialog.setHeaderText("Copy " + source + " and its command mappings");
        dialog.setContentText("New profile name:");
        dialog.showAndWait().ifPresent(name -> {
            String clean = cleanProfileName(name);
            if (clean.isBlank() || profiles.has(clean)) { showError("Invalid profile name", "Choose a unique, non-empty profile name."); return; }
            profiles.set(clean, profiles.path(source).deepCopy());
            persistProfilesOnly();
            navigate("Profiles");
        });
    }

    private void renameProfile(String source) {
        if (!ensureStoppedForProfileChange()) return;
        TextInputDialog dialog = new TextInputDialog(source);
        dialog.setTitle("Rename profile"); dialog.setHeaderText("Rename command profile"); dialog.setContentText("New name:");
        dialog.showAndWait().ifPresent(name -> {
            String clean = cleanProfileName(name);
            if (clean.isBlank() || (!clean.equals(source) && profiles.has(clean))) { showError("Invalid profile name", "Choose a unique, non-empty profile name."); return; }
            if (!clean.equals(source)) {
                JsonNode data = profiles.get(source).deepCopy();
                profiles.remove(source); profiles.set(clean, data);
                if (activeProfile.equals(source)) activeProfile = clean;
                config.put("activeProfile", activeProfile);
                persistProfilesOnly();
            }
            navigate("Profiles");
        });
    }

    private void deleteProfile(String target) {
        if (!ensureStoppedForProfileChange()) return;
        if (profiles.size() <= 1) { showError("Cannot delete last profile", "GLYPHX must retain at least one command profile."); return; }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + target + " and all of its command mappings? This cannot be undone.", ButtonType.CANCEL, ButtonType.OK);
        alert.setHeaderText("Delete profile");
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        profiles.remove(target);
        if (activeProfile.equals(target)) activeProfile = iterableKeys(profiles).get(0);
        activateProfile(activeProfile);
        navigate("Profiles");
    }

    private void selectProfileInManager(String profile) {
        if (!ensureStoppedForProfileChange()) return;
        activeProfile = profile;
        loadSettingsIntoEditors();
        persistProfilesOnly();
        navigate("Profiles");
    }

    private void activateProfile(String profile) {
        if (!profiles.has(profile)) return;
        activeProfile = profile;
        config.put("activeProfile", activeProfile);
        config.set("commands", profileCommands(activeProfile).deepCopy());
        loadSettingsIntoEditors();
        persistProfilesOnly();
        appendLog("Active command profile: " + activeProfile);
    }

    private boolean ensureStoppedForProfileChange() {
        if (runtimeProcess != null && runtimeProcess.isAlive()) {
            showError("Stop runtime first", "Stop the touchpad runtime before changing profiles or mappings.");
            return false;
        }
        return true;
    }

    private void persistProfilesOnly() {
        try {
            config.set("profiles", profiles);
            config.put("activeProfile", activeProfile);
            config.set("commands", profileCommands(activeProfile).deepCopy());
            persistConfiguration();
            refreshProfileSelector();
        } catch (Exception ex) { showError("Could not save profiles", ex.getMessage()); }
    }

    private void switchProfile(String selected) {
        if (!profiles.has(selected)) { refreshProfileSelector(); return; }
        if (!ensureStoppedForProfileChange()) { refreshProfileSelector(); return; }
        try {
            // Preserve the visible editor before changing the active profile.
            saveCurrentMappingsSilently();
            activeProfile = selected;
            config.put("activeProfile", activeProfile);
            config.set("commands", profileCommands(activeProfile).deepCopy());
            loadSettingsIntoEditors();
            persistConfiguration();
            refreshProfileSelector();
            appendLog("Switched active command profile to " + activeProfile + ".");
            navigate(currentPage);
        } catch (Exception ex) {
            showError("Could not switch profile", ex.getMessage());
            refreshProfileSelector();
        }
    }

    private void refreshProfileSelector() {
        globalProfileSelector.getItems().setAll(iterableKeys(profiles));
        globalProfileSelector.setValue(activeProfile);
    }

    private ObjectNode profileCommands(String name) {
        JsonNode commands = profiles.path(name).path("commands");
        if (commands instanceof ObjectNode object) return object;
        ObjectNode blank = defaultCommands();
        ObjectNode profile = profiles.with(name);
        profile.set("commands", blank);
        return blank;
    }

    private List<String> iterableKeys(ObjectNode object) {
        List<String> keys = new ArrayList<>();
        object.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private int commandCount(ObjectNode commands) {
        int count = 0;
        for (String symbol : modelClasses) if (!commands.path(symbol).isMissingNode()) count++;
        return count;
    }

    private void persistConfiguration() throws Exception {
        config.set("profiles", profiles);
        config.put("activeProfile", activeProfile);
        config.set("commands", profileCommands(activeProfile).deepCopy());
        Path temp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), config);
        try {
            Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void updateExecutionMode() {
        String mode = liveExecution.isSelected() ? "Live" : "Dry Run";
        executionValue.setText(mode);
        config.put("dryRun", !liveExecution.isSelected());
    }

    private void startRuntime() {
        if (runtimeProcess != null && runtimeProcess.isAlive()) return;
        if (liveExecution.isSelected()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Live mode allows every accepted gesture to execute its mapped Linux command. Continue?",
                    ButtonType.CANCEL, ButtonType.OK);
            confirm.setHeaderText("Start in live execution mode?");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }
        Path device = findReadableTouchpad();
        if (device == null) {
            showError("Touchpad unavailable", "No readable Linux touchpad event device was found. Verify the glyvex udev rule and your group membership, then refresh the device.");
            refreshDeviceStatus();
            return;
        }
        try {
            // Persist editor values before launching the child runtime.
            saveCurrentMappingsSilently();
            config.put("dryRun", !liveExecution.isSelected());
            config.with("ml").put("overrideThreshold", Double.parseDouble(thresholdField.getText().trim()));
            persistConfiguration();
            Path runScript = projectRoot.resolve("scripts/run.sh");
            if (!Files.isRegularFile(runScript)) throw new IllegalStateException("Missing runtime launcher: " + runScript);
            List<String> command = List.of("/bin/bash", runScript.toString(), liveExecution.isSelected() ? "--execute" : "--dry-run");
            ProcessBuilder builder = new ProcessBuilder(command).directory(projectRoot.toFile()).redirectErrorStream(true);
            runtimeProcess = builder.start();
            Process launched = runtimeProcess;
            stopping.set(false);
            runtimeValue.setText("Starting");
            runtimeHint.setText("Opening the native Linux touchpad input stream…");
            startButton.setDisable(true); stopButton.setDisable(false);
            appendLog("Starting GLYPHX using " + device.getFileName());
            outputThread = new Thread(() -> readRuntimeOutput(launched), "glyphyx-runtime-output");
            outputThread.setDaemon(true); outputThread.start();
        } catch (Exception ex) {
            runtimeValue.setText("Error");
            startButton.setDisable(false); stopButton.setDisable(true);
            showError("Runtime could not start", ex.getMessage());
        }
    }

    private void saveCurrentMappingsSilently() throws Exception {
        ObjectNode commands = mapper.createObjectNode();
        for (String symbol : modelClasses) {
            MappingEditor editor = editors.get(symbol);
            if (editor == null) continue;
            String raw = editor.commandField.getText().trim();
            if (raw.isBlank()) throw new IllegalArgumentException("Command for " + symbol + " cannot be blank.");
            boolean shell = editor.shellCheck.isSelected();
            boolean admin = editor.adminCheck.isSelected();
            List<String> args;
            if (shell) {
                if (containsSudoPrefix(raw)) { raw = stripSudoPrefixes(raw); admin = true; }
                args = List.of(raw);
            } else {
                args = parseCommandLine(raw);
                if (!args.isEmpty() && basename(args.get(0)).equals("sudo")) { args = new ArrayList<>(args.subList(1, args.size())); admin = true; }
            }
            if (args.isEmpty() || args.get(0).isBlank()) throw new IllegalArgumentException("Invalid command for " + symbol);
            editor.adminCheck.setSelected(admin);
            commands.set(symbol, commandEntry(args, admin, shell, symbol));
        }
        profiles.with(activeProfile).set("commands", commands);
        config.set("profiles", profiles);
        config.set("commands", commands.deepCopy());
    }

    private void readRuntimeOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String captured = line;
                Platform.runLater(() -> handleRuntimeLine(captured));
            }
        } catch (Exception ex) {
            Platform.runLater(() -> appendLog("Runtime output reader stopped: " + ex.getMessage()));
        }
        try { int exit = process.waitFor(); Platform.runLater(() -> runtimeExited(exit)); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private void handleRuntimeLine(String line) {
        appendLog(line);
        if (line.contains("STARTING ON PHYSICAL TOUCHPAD")) {
            runtimeValue.setText("Listening");
            runtimeHint.setText("Touchpad stream active. Complete TL → TR → BR → BL to arm capture.");
        }
        if (line.contains("CORNER") || line.contains("ACTIVATION") || line.contains("CAPTURE") || line.contains("GESTURE COMPLETE")) runtimeHint.setText(line.trim());
        Matcher p = PREDICTION.matcher(line);
        if (p.find()) recognizedCharacter.setText(p.group(1).toUpperCase(Locale.ROOT));
        Matcher c = CONFIDENCE.matcher(line);
        if (c.find()) confidenceValue.setText(String.format(Locale.ROOT, "%.2f%%", Double.parseDouble(c.group(1)) * 100.0));
        Matcher d = DECISION.matcher(line);
        if (d.find()) {
            decisionValue.setText(d.group(1));
            decisionValue.getStyleClass().setAll("decision-value", d.group(1).equals("ACCEPT") ? "decision-accept" : "decision-reject");
        }
        Matcher raster = RASTER.matcher(line);
        if (raster.find()) loadGestureRaster(raster.group(1).trim());
        if (line.contains("Action:") || line.contains("Would execute:") || line.contains("EXECUTOR] Launching")) mappedCommandValue.setText(line.trim());
        if (line.contains("DRY RUN")) executionValue.setText("Dry Run");
        if (line.contains("FATAL") || line.contains("ERROR")) runtimeValue.setText("Error");
    }

    private void loadGestureRaster(String rawPath) {
        try {
            Path path = Paths.get(rawPath);
            if (!path.isAbsolute()) path = projectRoot.resolve(path).normalize();
            if (!Files.isRegularFile(path)) return;
            Image image = new Image(path.toUri().toString(), true);
            image.progressProperty().addListener((obs, oldValue, newValue) -> {
                if (newValue.doubleValue() >= 1.0 && !image.isError()) {
                    Platform.runLater(() -> {
                        gestureImage.setImage(image);
                        gestureImage.setVisible(true);
                        gesturePlaceholder.setVisible(false);
                    });
                }
            });
            if (image.getProgress() >= 1.0 && !image.isError()) {
                gestureImage.setImage(image); gestureImage.setVisible(true); gesturePlaceholder.setVisible(false);
            }
        } catch (Exception ex) { appendLog("Could not load the captured gesture raster: " + ex.getMessage()); }
    }

    private void runtimeExited(int exitCode) {
        if (stopping.get()) return;
        runtimeValue.setText(exitCode == 0 ? "Stopped" : "Exited " + exitCode);
        runtimeHint.setText(exitCode == 0 ? "Gesture recognition is not active." : "Runtime stopped unexpectedly. Check the terminal output.");
        startButton.setDisable(false); stopButton.setDisable(true); runtimeProcess = null;
        appendLog("Runtime process exited with code " + exitCode);
    }

    private void stopRuntime(boolean announce) {
        Process process = runtimeProcess;
        if (process == null || !process.isAlive()) return;
        stopping.set(true);
        try {
            process.toHandle().descendants().forEach(handle -> { try { handle.destroy(); } catch (Exception ignored) {} });
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.toHandle().descendants().forEach(handle -> { try { handle.destroyForcibly(); } catch (Exception ignored) {} });
                process.destroyForcibly();
            }
        } catch (Exception ignored) { process.destroyForcibly(); }
        runtimeProcess = null;
        runtimeValue.setText("Stopped"); runtimeHint.setText("Gesture recognition is not active.");
        startButton.setDisable(false); stopButton.setDisable(true);
        if (announce) appendLog("Runtime stopped by user.");
    }

    private void refreshDeviceStatus() {
        Path device = findReadableTouchpad();
        if (device == null) {
            touchpadValue.setText("Not ready");
            touchpadValue.getStyleClass().setAll("status-value", "status-warning");
        } else {
            touchpadValue.setText(device.getFileName().toString());
            touchpadValue.getStyleClass().setAll("status-value", "status-good");
        }
    }

    private Path findReadableTouchpad() {
        Path input = Paths.get("/dev/input");
        if (!Files.isDirectory(input)) return null;
        try (Stream<Path> paths = Files.list(input)) {
            return paths.filter(path -> path.getFileName().toString().matches("event\\d+"))
                    .filter(path -> {
                        Path name = Paths.get("/sys/class/input", path.getFileName().toString(), "device/name");
                        try {
                            String value = Files.readString(name).toLowerCase(Locale.ROOT);
                            return value.contains("touchpad") || value.contains("trackpad") || value.contains("synaptics") || value.contains("elan") || value.contains("alps");
                        } catch (Exception ex) { return false; }
                    }).filter(Files::isReadable).findFirst().orElse(null);
        } catch (Exception ex) { return null; }
    }

    private List<String> parseCommandLine(String raw) {
        List<String> output = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0; boolean escaping = false; boolean started = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (escaping) { token.append(ch); escaping = false; started = true; continue; }
            if (ch == '\\' && quote != '\'') { escaping = true; started = true; continue; }
            if (quote != 0) { if (ch == quote) quote = 0; else token.append(ch); started = true; continue; }
            if (ch == '\'' || ch == '"') { quote = ch; started = true; continue; }
            if (Character.isWhitespace(ch)) {
                if (started) { output.add(token.toString()); token.setLength(0); started = false; }
            } else { token.append(ch); started = true; }
        }
        if (escaping) token.append('\\');
        if (quote != 0) throw new IllegalArgumentException("Unclosed quote in command string.");
        if (started) output.add(token.toString());
        return output;
    }

    private String joinCommand(List<String> args) {
        List<String> quoted = new ArrayList<>();
        for (String arg : args) quoted.add(arg.matches(".*\\s+.*") ? "\"" + arg.replace("\"", "\\\"") + "\"" : arg);
        return String.join(" ", quoted);
    }

    private List<String> nodeArgs(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private boolean startsWithSudo(String text) { return text.matches("(?is)^\\s*(?:/usr/bin/)?sudo\\s+.*"); }
    private boolean containsSudoPrefix(String text) { return text.matches("(?is).*(?:^|&&\\s*|\\|\\|\\s*|;\\s*|\\|\\s*)(?:/usr/bin/)?sudo\\s+.*"); }
    private String stripSudoPrefixes(String text) {
        return text.replaceAll("(?i)(^|&&\\s*|\\|\\|\\s*|;\\s*|\\|\\s*)(?:/usr/bin/)?sudo\\s+", "$1");
    }
    private boolean containsShellOperators(String text) {
        return text.contains("&&") || text.contains("||") || text.contains("|")
                || text.contains(">") || text.contains("<") || text.contains(";")
                || text.contains("$(") || text.contains("`") || text.contains("\n");
    }

    private String basename(String path) { return path == null ? "" : path.substring(path.lastIndexOf('/') + 1); }
    private String cleanProfileName(String input) { return input == null ? "" : input.trim().replaceAll("\\s+", " "); }

    private HBox thinDivider() {
        HBox line = new HBox(); line.getStyleClass().add("horizontal-rule"); line.setMinHeight(1); line.setPrefHeight(1); return line;
    }

    private Button smallButton(String text) {
        Button button = new Button(text); button.getStyleClass().add("button-secondary"); return button;
    }

    private Label label(String text, String style) {
        Label label = new Label(text); label.getStyleClass().add(style); return label;
    }

    private void appendLog(String text) {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> appendLog(text)); return; }
        // Retain a bounded diagnostic history in memory, not on the dashboard.
        String line = "[" + LocalTime.now().format(CLOCK) + "] " + text;
        console.appendText(line + System.lineSeparator());
        if (console.getLength() > 60000) console.deleteText(0, console.getLength() - 45000);
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle("GLYPHX"); alert.setHeaderText(title); alert.showAndWait();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "Unknown error" : message, ButtonType.OK);
        alert.setTitle("GLYPHX"); alert.setHeaderText(title); alert.showAndWait();
    }

    private void showFatal(Stage owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message == null ? "Unknown error" : message, ButtonType.OK);
        alert.initOwner(owner); alert.setTitle("GLYPHX"); alert.setHeaderText(title); alert.showAndWait();
        Platform.exit();
    }

    @Override public void stop() { stopRuntime(false); }

    private static final class MappingEditor {
        final TextField commandField = new TextField();
        final CheckBox adminCheck = new CheckBox();
        final CheckBox shellCheck = new CheckBox();
        MappingEditor(String initial) { commandField.setText(initial); }
    }
}
