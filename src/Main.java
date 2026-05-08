import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// =============================================================
//  ENUMERATIONS
// =============================================================

enum DeviceState  { ON, OFF }
enum StrategyMode { DEFAULT, ECO }
enum AlertLevel   { NORMAL, WARNING, CRITICAL }

// =============================================================
//  GENERICS – thread-safe data store
// =============================================================

class DataStore<T> {
    private final List<T> items = Collections.synchronizedList(new ArrayList<>());

    public void add(T item) { items.add(item); }

    public List<T> getAll() {
        synchronized (items) { return new ArrayList<>(items); }
    }

    public int size()        { return items.size(); }
    public boolean isEmpty() { return items.isEmpty(); }
    public void clear()      { items.clear(); }

    public void forEach(Consumer<T> action) {
        synchronized (items) { items.forEach(action); }
    }
}

// =============================================================
//  SENSOR READING (generic wrapper)
// =============================================================

class SensorReading<T extends Number> implements Serializable {
    private static final long serialVersionUID = 1L;
    private final T value;
    private final String unit;
    private final LocalDateTime timestamp;

    public SensorReading(T value, String unit) {
        this.value     = value;
        this.unit      = unit;
        this.timestamp = LocalDateTime.now();
    }

    public T getValue()                 { return value; }
    public String getUnit()             { return unit; }
    public LocalDateTime getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return value + " " + unit + " @ " +
                timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }
}

// =============================================================
//  ENVIRONMENT
// =============================================================

class Environment implements Serializable {
    private static final long serialVersionUID = 2L;

    private final SensorReading<Double> temperature;
    private final SensorReading<Double> soilMoisture;
    private final SensorReading<Double> humidity;
    private final LocalDateTime timestamp;

    public Environment(double t, double s, double h) {
        temperature  = new SensorReading<>(t, "C");
        soilMoisture = new SensorReading<>(s, "%");
        humidity     = new SensorReading<>(h, "%");
        timestamp    = LocalDateTime.now();
    }

    public double getTemperature()      { return temperature.getValue(); }
    public double getSoilMoisture()     { return soilMoisture.getValue(); }
    public double getHumidity()         { return humidity.getValue(); }
    public LocalDateTime getTimestamp() { return timestamp; }

    public static Environment autoGenerate() {
        Random r = new Random();
        return new Environment(
                15 + r.nextDouble() * 25,
                20 + r.nextDouble() * 60,
                30 + r.nextDouble() * 50
        );
    }

    public AlertLevel getAlertLevel() {
        if (getTemperature() > 40)                                                    return AlertLevel.CRITICAL;
        if (getTemperature() > Config.MAX_TEMP || getTemperature() < Config.MIN_TEMP) return AlertLevel.WARNING;
        return AlertLevel.NORMAL;
    }

    @Override
    public String toString() {
        return String.format("Temp=%.1fC | Soil=%.1f%% | Humidity=%.1f%%",
                getTemperature(), getSoilMoisture(), getHumidity());
    }
}

// =============================================================
//  CONFIG
// =============================================================

class Config {
    static double MAX_TEMP = 28;
    static double MIN_TEMP = 18;
    static double MIN_SOIL = 35;

    public static void load() {
        try (BufferedReader br = new BufferedReader(new FileReader("config.txt"))) {
            Properties p = new Properties();
            p.load(br);
            MAX_TEMP = Double.parseDouble(p.getProperty("MAX_TEMP", "28"));
            MIN_TEMP = Double.parseDouble(p.getProperty("MIN_TEMP", "18"));
            MIN_SOIL = Double.parseDouble(p.getProperty("MIN_SOIL", "35"));
            System.out.println("[CONFIG] Loaded from config.txt");
        } catch (Exception e) {
            System.out.println("[CONFIG] config.txt not found - using defaults.");
        }
    }
}

// =============================================================
//  DEVICE
// =============================================================

abstract class Device {
    protected volatile DeviceState state = DeviceState.OFF;
    private final String name;

    public Device(String name) { this.name = name; }

    public synchronized void turnOn() {
        if (state == DeviceState.OFF) {
            state = DeviceState.ON;
            Logger.log("DEVICE", name + " turned ON");
        }
    }

    public synchronized void turnOff() {
        if (state == DeviceState.ON) {
            state = DeviceState.OFF;
            Logger.log("DEVICE", name + " turned OFF");
        }
    }

    public DeviceState getState() { return state; }
    public boolean isRunning()    { return state == DeviceState.ON; }
    public String getName()       { return name; }
}

class Fan        extends Device { public Fan()        { super("Fan"); } }
class Heater     extends Device { public Heater()     { super("Heater"); } }
class Irrigation extends Device { public Irrigation() { super("Irrigation"); } }

// =============================================================
//  STRATEGY
// =============================================================

@FunctionalInterface
interface ControlStrategy {
    void apply(Environment env, Fan fan, Heater heater, Irrigation irrigation);
}

class Strategies {

    public static ControlStrategy defaultStrategy() {
        return (env, fan, heater, irrigation) -> {
            if (env.getTemperature() > Config.MAX_TEMP) {
                fan.turnOn();  heater.turnOff();
            } else if (env.getTemperature() < Config.MIN_TEMP) {
                heater.turnOn(); fan.turnOff();
            } else {
                fan.turnOff(); heater.turnOff();
            }
            if (env.getHumidity() > 80) fan.turnOn();
            if (env.getSoilMoisture() < Config.MIN_SOIL) irrigation.turnOn();
            else irrigation.turnOff();
        };
    }

    public static ControlStrategy ecoStrategy() {
        return (env, fan, heater, irrigation) -> {
            if (env.getTemperature() > Config.MAX_TEMP + 2) fan.turnOn();
            else fan.turnOff();
            if (env.getSoilMoisture() < Config.MIN_SOIL - 10) irrigation.turnOn();
            else irrigation.turnOff();
            heater.turnOff();
        };
    }
}

// =============================================================
//  EXCEPTION
// =============================================================

class SensorException extends Exception {
    public SensorException(String msg) { super(msg); }
}

// =============================================================
//  LOGGER
// =============================================================

class Logger {
    private static final Object lock = new Object();

    public static void log(String level, String msg) {
        synchronized (lock) {
            try (FileWriter fw = new FileWriter("greenhouse_log.txt", true)) {
                fw.write(LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        + " [" + level + "] " + msg + "\n");
            } catch (Exception ignored) {}
        }
    }
}

// =============================================================
//  FILE MANAGER
// =============================================================

class FileManager {
    @SuppressWarnings("unchecked")
    public static List<Environment> load() {
        try (ObjectInputStream in =
                     new ObjectInputStream(new FileInputStream("history.dat"))) {
            Object obj = in.readObject();
            if (obj instanceof List<?>) return (List<Environment>) obj;
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    public static void save(List<Environment> history) {
        try (ObjectOutputStream out =
                     new ObjectOutputStream(new FileOutputStream("history.dat"))) {
            out.writeObject(new ArrayList<>(history));
            System.out.println("[FILE] History saved to history.dat");
        } catch (Exception e) {
            Logger.log("ERROR", "Save failed: " + e.getMessage());
        }
    }
}

// =============================================================
//  GREENHOUSE CONTROLLER
// =============================================================

class GreenhouseController {

    private final Fan fan        = new Fan();
    private final Heater heater  = new Heater();
    private final Irrigation irr = new Irrigation();

    private volatile StrategyMode    strategyMode = StrategyMode.DEFAULT;
    private volatile ControlStrategy strategy     = Strategies.defaultStrategy();

    private final DataStore<Environment> history = new DataStore<>();

    public GreenhouseController() {
        FileManager.load().forEach(history::add);
        if (!history.isEmpty())
            System.out.println("[INIT] Loaded " + history.size() + " historical readings.");
    }

    public synchronized void regulate(Environment env) throws SensorException {
        if (env.getTemperature() < -20 || env.getTemperature() > 60)
            throw new SensorException("Temperature out of sensor range: " + env.getTemperature());

        history.add(env);

        DeviceState prevFan    = fan.getState();
        DeviceState prevHeater = heater.getState();
        DeviceState prevIrr    = irr.getState();

        strategy.apply(env, fan, heater, irr);

        if (fan.getState()    != prevFan)
            System.out.println("  [DEVICE] Fan        -> " + fan.getState());
        if (heater.getState() != prevHeater)
            System.out.println("  [DEVICE] Heater     -> " + heater.getState());
        if (irr.getState()    != prevIrr)
            System.out.println("  [DEVICE] Irrigation -> " + irr.getState());

        if (env.getAlertLevel() == AlertLevel.CRITICAL)
            System.out.println("  *** CRITICAL: Extreme temperature " + env.getTemperature() + "C! ***");
        else if (env.getAlertLevel() == AlertLevel.WARNING)
            System.out.println("  !! WARNING: Temperature out of optimal range.");

        Logger.log("INFO", env.toString());
    }

    public void toggleStrategy() {
        if (strategyMode == StrategyMode.DEFAULT) {
            strategyMode = StrategyMode.ECO;
            strategy     = Strategies.ecoStrategy();
        } else {
            strategyMode = StrategyMode.DEFAULT;
            strategy     = Strategies.defaultStrategy();
        }
        Logger.log("STRATEGY", "Switched to " + strategyMode);
        System.out.println("[STRATEGY] Switched to " + strategyMode);
    }

    public void printDeviceStatus() {
        System.out.println("  Fan=" + fan.getState() +
                " | Heater=" + heater.getState() +
                " | Irrigation=" + irr.getState());
    }

    public void printStats() {
        if (history.isEmpty()) { System.out.println("  No data yet."); return; }
        System.out.printf("  Avg Temp: %.1fC | Max: %.1fC | Min: %.1fC%n",
                getAvgTemp(), getMaxTemp(), getMinTemp());
    }

    public void exportCSV() {
        try (PrintWriter pw = new PrintWriter("data.csv")) {
            pw.println("Timestamp,Temperature,Soil,Humidity");
            history.forEach(e -> pw.println(
                    e.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "," +
                            String.format("%.2f,%.2f,%.2f",
                                    e.getTemperature(), e.getSoilMoisture(), e.getHumidity())
            ));
            System.out.println("[EXPORT] data.csv written (" + history.size() + " rows).");
            Logger.log("EXPORT", "CSV exported.");
        } catch (Exception e) {
            System.out.println("[ERROR] CSV export failed: " + e.getMessage());
        }
    }

    public void printHistory(int last) {
        List<Environment> all = history.getAll();
        int start = Math.max(0, all.size() - last);
        System.out.println("  --- Last " + Math.min(last, all.size()) + " readings ---");
        for (int i = start; i < all.size(); i++) {
            Environment e = all.get(i);
            System.out.printf("  [%s] %s  [%s]%n",
                    e.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    e, e.getAlertLevel());
        }
    }

    public void saveHistory()              { FileManager.save(history.getAll()); }
    public StrategyMode getStrategyMode()  { return strategyMode; }
    public DataStore<Environment> getHistory() { return history; }

    public double getAvgTemp() {
        return history.getAll().stream().mapToDouble(Environment::getTemperature).average().orElse(0);
    }
    public double getMaxTemp() {
        return history.getAll().stream().mapToDouble(Environment::getTemperature).max().orElse(0);
    }
    public double getMinTemp() {
        return history.getAll().stream().mapToDouble(Environment::getTemperature).min().orElse(0);
    }
}

// =============================================================
//  SENSOR MONITOR THREAD
// =============================================================

class SensorMonitor implements Runnable {
    private final GreenhouseController controller;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int intervalSeconds;

    public SensorMonitor(GreenhouseController controller, int intervalSeconds) {
        this.controller      = controller;
        this.intervalSeconds = intervalSeconds;
    }

    public void stop() { running.set(false); }

    @Override
    public void run() {
        Logger.log("MONITOR", "Sensor monitor started (interval=" + intervalSeconds + "s)");
        while (running.get()) {
            try {
                Environment env = Environment.autoGenerate();
                System.out.println("[AUTO] " + env);
                controller.regulate(env);
                TimeUnit.SECONDS.sleep(intervalSeconds);
            } catch (SensorException e) {
                System.out.println("[ERROR] Monitor: " + e.getMessage());
                Logger.log("ERROR", "Monitor: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Logger.log("MONITOR", "Sensor monitor stopped.");
    }
}

// =============================================================
//  MAIN – Console Menu
// =============================================================

class GreenhouseSystem {

    public static void main(String[] args) {
        Config.load();
        GreenhouseController controller = new GreenhouseController();
        Scanner scanner = new Scanner(System.in);

        SensorMonitor monitor       = null;
        Thread        monitorThread = null;
        boolean       autoRunning   = false;

        printBanner();

        boolean running = true;
        while (running) {
            printMenu(controller.getStrategyMode(), autoRunning);
            System.out.print("  Enter choice: ");
            String input = scanner.nextLine().trim();

            switch (input) {
                case "1" -> {
                    try {
                        System.out.print("  Temperature (-20 to 60): ");
                        double t = Double.parseDouble(scanner.nextLine().trim());
                        System.out.print("  Soil Moisture (0-100):   ");
                        double s = Double.parseDouble(scanner.nextLine().trim());
                        System.out.print("  Humidity (0-100):        ");
                        double h = Double.parseDouble(scanner.nextLine().trim());
                        Environment env = new Environment(t, s, h);
                        controller.regulate(env);
                        System.out.println("  Reading processed: " + env);
                        controller.printDeviceStatus();
                    } catch (NumberFormatException ex) {
                        System.out.println("  [ERROR] Invalid number entered.");
                    } catch (SensorException ex) {
                        System.out.println("  [ERROR] " + ex.getMessage());
                    }
                }
                case "2" -> {
                    try {
                        Environment env = Environment.autoGenerate();
                        System.out.println("  Generated: " + env);
                        controller.regulate(env);
                        controller.printDeviceStatus();
                    } catch (SensorException ex) {
                        System.out.println("  [ERROR] " + ex.getMessage());
                    }
                }
                case "3" -> {
                    if (!autoRunning) {
                        monitor       = new SensorMonitor(controller, 3);
                        monitorThread = new Thread(monitor, "SensorMonitor");
                        monitorThread.setDaemon(true);
                        monitorThread.start();
                        autoRunning = true;
                        System.out.println("  [MONITOR] Auto-monitoring STARTED (every 3s). Select 3 again to stop.");
                    } else {
                        monitor.stop();
                        monitorThread.interrupt();
                        autoRunning = false;
                        System.out.println("  [MONITOR] Auto-monitoring STOPPED.");
                    }
                }
                case "4" -> controller.toggleStrategy();
                case "5" -> {
                    System.out.println("  --- STATISTICS ---");
                    controller.printStats();
                }
                case "6" -> controller.printHistory(10);
                case "7" -> controller.exportCSV();
                case "8" -> {
                    System.out.println("  --- CONFIG THRESHOLDS ---");
                    System.out.printf("  MAX_TEMP=%.1fC | MIN_TEMP=%.1fC | MIN_SOIL=%.1f%%%n",
                            Config.MAX_TEMP, Config.MIN_TEMP, Config.MIN_SOIL);
                }
                case "0" -> {
                    System.out.println("  Saving and exiting...");
                    if (monitor != null) monitor.stop();
                    if (monitorThread != null) monitorThread.interrupt();
                    controller.saveHistory();
                    Logger.log("SYSTEM", "Application closed.");
                    running = false;
                }
                default -> System.out.println("  [!] Unknown option. Try again.");
            }
            System.out.println();
        }
        scanner.close();
    }

    private static void printBanner() {
        System.out.println("============================================");
        System.out.println("   SMART GREENHOUSE SYSTEM  -  Sprint 3    ");
        System.out.println("============================================");
        System.out.printf("  Config: MAX=%.0fC | MIN=%.0fC | SOIL=%.0f%%%n",
                Config.MAX_TEMP, Config.MIN_TEMP, Config.MIN_SOIL);
        System.out.println("============================================");
        System.out.println();
    }

    private static void printMenu(StrategyMode mode, boolean autoRunning) {
        System.out.println("--------------------------------------------");
        System.out.println("  Strategy : " + mode +
                "  |  Monitor : " + (autoRunning ? "RUNNING" : "STOPPED"));
        System.out.println("--------------------------------------------");
        System.out.println("  1. Manual sensor input");
        System.out.println("  2. Auto-generate one reading");
        System.out.println("  3. " + (autoRunning ? "Stop" : "Start") + " auto monitor (every 3s)");
        System.out.println("  4. Switch strategy (DEFAULT / ECO)");
        System.out.println("  5. Show statistics");
        System.out.println("  6. Show last 10 readings");
        System.out.println("  7. Export CSV");
        System.out.println("  8. Show config thresholds");
        System.out.println("  0. Save & Exit");
    }
}