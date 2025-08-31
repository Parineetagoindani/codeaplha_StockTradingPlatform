import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Single-file Java app:
 * - Simulates market prices for a few symbols
 * - Shows market data
 * - Buy/Sell with cash balance and portfolio holdings
 * - Tracks transactions and portfolio performance over time
 * - Save/Load portfolio to a local file (Java serialization)
 *
 * How to run:
 *   javac StockTradingApp.java
 *   java StockTradingApp
 */
public class StockTradingplatform {

    /* ======================= DOMAIN MODELS ======================= */

    enum OrderType { BUY, SELL }

    static class Stock implements Serializable {
        private final String symbol;
        private final String name;
        private double price;         // current price
        private double dayOpen;       // opening price (for % change)

        public Stock(String symbol, String name, double initial) {
            this.symbol = symbol.toUpperCase(Locale.ROOT);
            this.name = name;
            this.price = initial;
            this.dayOpen = initial;
        }

        public String getSymbol() { return symbol; }
        public String getName() { return name; }
        public double getPrice() { return price; }
        public double getDayOpen() { return dayOpen; }

        /** Random walk price update with mild drift/volatility */
        public void tick(Random rng) {
            double pctMove = rng.nextGaussian() * 0.005; // ~0.5% std dev per tick
            price = Math.max(0.01, price * (1.0 + pctMove));
        }

        /** Reset opening price (e.g., new "day") */
        public void newSession() {
            dayOpen = price;
        }

        public double pctChangeFromOpen() {
            return (price - dayOpen) / dayOpen * 100.0;
        }
    }

    static class Holding implements Serializable {
        String symbol;
        int shares;
        double avgCost;

        public Holding(String symbol) {
            this.symbol = symbol;
        }

        public double marketValue(double price) {
            return shares * price;
        }
    }

    static class Transaction implements Serializable {
        LocalDateTime time;
        OrderType type;
        String symbol;
        int shares;
        double price;
        double cashAfter;

        public Transaction(LocalDateTime time, OrderType type, String symbol, int shares, double price, double cashAfter) {
            this.time = time; this.type = type; this.symbol = symbol;
            this.shares = shares; this.price = price; this.cashAfter = cashAfter;
        }

        @Override public String toString() {
            return String.format("%s | %-4s | %-4s x %d @ %.2f | Cash: %.2f",
                    time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    type, symbol, shares, price, cashAfter);
        }
    }

    static class PerformancePoint implements Serializable {
        LocalDateTime time;
        double totalValue;
        public PerformancePoint(LocalDateTime time, double totalValue) {
            this.time = time; this.totalValue = totalValue;
        }
    }

    static class Portfolio implements Serializable {
        private double cash = 10_000.00; // starting cash
        private final Map<String, Holding> holdings = new HashMap<>();
        private final List<Transaction> transactions = new ArrayList<>();
        private final List<PerformancePoint> performance = new ArrayList<>();

        public double getCash() { return cash; }
        public Map<String, Holding> getHoldings() { return holdings; }
        public List<Transaction> getTransactions() { return transactions; }
        public List<PerformancePoint> getPerformance() { return performance; }

        public void recordPerformance(Market market) {
            performance.add(new PerformancePoint(LocalDateTime.now(), totalValue(market)));
        }

        public double totalValue(Market market) {
            double value = cash;
            for (Holding h : holdings.values()) {
                double p = market.getPrice(h.symbol);
                value += h.marketValue(p);
            }
            return value;
        }

        public void buy(String symbol, int shares, Market market) {
            double price = market.getPrice(symbol);
            if (price <= 0) throw new IllegalArgumentException("Invalid price.");
            if (shares <= 0) throw new IllegalArgumentException("Shares must be positive.");
            double cost = price * shares;
            if (cost > cash + 1e-9) throw new IllegalArgumentException("Insufficient cash.");

            Holding h = holdings.computeIfAbsent(symbol, Holding::new);

            // Update average cost
            double totalCostBefore = h.avgCost * h.shares;
            h.shares += shares;
            h.avgCost = (totalCostBefore + cost) / h.shares;

            cash -= cost;
            transactions.add(new Transaction(LocalDateTime.now(), OrderType.BUY, symbol, shares, price, cash));
        }

        public void sell(String symbol, int shares, Market market) {
            Holding h = holdings.get(symbol);
            if (h == null || h.shares < shares) throw new IllegalArgumentException("Not enough shares to sell.");
            if (shares <= 0) throw new IllegalArgumentException("Shares must be positive.");

            double price = market.getPrice(symbol);
            double proceeds = price * shares;

            h.shares -= shares;
            if (h.shares == 0) h.avgCost = 0;

            cash += proceeds;
            transactions.add(new Transaction(LocalDateTime.now(), OrderType.SELL, symbol, shares, price, cash));
        }
    }

    static class Market implements Serializable {
        private final Map<String, Stock> stocks = new LinkedHashMap<>();
        private transient Random rng = new Random();

        public Market() { }

        public void addStock(Stock s) { stocks.put(s.getSymbol(), s); }

        public Set<String> symbols() { return stocks.keySet(); }

        public double getPrice(String symbol) {
            Stock s = stocks.get(symbol.toUpperCase(Locale.ROOT));
            if (s == null) throw new IllegalArgumentException("Unknown symbol: " + symbol);
            return s.getPrice();
        }

        public Stock get(String symbol) { return stocks.get(symbol.toUpperCase(Locale.ROOT)); }

        /** advance market one tick */
        public void tickAll() {
            // Recreate RNG if deserialized
            if (rng == null) rng = new Random();
            for (Stock s : stocks.values()) s.tick(rng);
        }

        /** Simulate a new "session" (resets day open) */
        public void newSession() {
            for (Stock s : stocks.values()) s.newSession();
        }
    }

    /* ======================= PERSISTENCE ======================= */

    static class SaveData implements Serializable {
        Portfolio portfolio;
        Market market;
        public SaveData(Portfolio p, Market m) { this.portfolio = p; this.market = m; }
    }

    static class Storage {
        public static void save(String filename, SaveData data) throws IOException {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filename))) {
                oos.writeObject(data);
            }
        }
        public static SaveData load(String filename) throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filename))) {
                return (SaveData) ois.readObject();
            }
        }
    }

    /* ======================= APP / UI ======================= */

    private final Scanner in = new Scanner(System.in);
    private Portfolio portfolio = new Portfolio();
    private Market market = defaultMarket();
    private final String SAVE_FILE = "portfolio.dat";

    private static Market defaultMarket() {
        Market m = new Market();
        m.addStock(new Stock("AAPL", "Apple Inc.", 200.00));
        m.addStock(new Stock("GOOG", "Alphabet Inc.", 2800.00));
        m.addStock(new Stock("MSFT", "Microsoft Corp.", 420.00));
        m.addStock(new Stock("AMZN", "Amazon.com Inc.", 160.00));
        m.addStock(new Stock("TSLA", "Tesla Inc.", 230.00));
        return m;
    }

    private void run() {
        System.out.println("=== Stock Trading Platform (Single File) ===");
        System.out.println("Starting cash: $" + fmt(portfolio.getCash()));
        market.newSession();
        portfolio.recordPerformance(market);

        boolean running = true;
        while (running) {
            market.tickAll();                // market moves every loop
            portfolio.recordPerformance(market);

            System.out.println();
            System.out.println("Menu: [1] Market [2] Buy [3] Sell [4] Portfolio [5] Performance [6] Save [7] Load [0] Exit");
            System.out.print("Choose: ");
            String choice = in.nextLine().trim();

            try {
                switch (choice) {
                    case "1": showMarket(); break;
                    case "2": buyFlow(); break;
                    case "3": sellFlow(); break;
                    case "4": showPortfolio(); break;
                    case "5": showPerformance(); break;
                    case "6": saveFlow(); break;
                    case "7": loadFlow(); break;
                    case "0":
                        autoSaveOnExit();
                        running = false;
                        break;
                    default: System.out.println("Invalid option.");
                }
            } catch (Exception ex) {
                System.out.println("⚠️ " + ex.getMessage());
            }
        }

        System.out.println("Goodbye.");
    }

    private void showMarket() {
        System.out.println("\n--- Market Data ---");
        System.out.printf("%-6s %-18s %10s %10s%n", "SYM", "NAME", "PRICE", "CHG%");
        for (String sym : market.symbols()) {
            Stock s = market.get(sym);
            System.out.printf("%-6s %-18s %10s %9.2f%%%n",
                    s.getSymbol(), truncate(s.getName(), 18), fmt(s.getPrice()), s.pctChangeFromOpen());
        }
    }

    private void buyFlow() {
        System.out.print("Symbol to BUY: ");
        String symbol = in.nextLine().trim().toUpperCase(Locale.ROOT);
        System.out.print("Shares: ");
        int qty = Integer.parseInt(in.nextLine().trim());

        double price = market.getPrice(symbol);
        double cost = price * qty;
        System.out.printf("Confirm BUY %d %s @ %s = %s ? (y/n): ",
                qty, symbol, fmt(price), fmt(cost));
        if (yes()) {
            portfolio.buy(symbol, qty, market);
            System.out.println("✅ Bought. Cash now: $" + fmt(portfolio.getCash()));
        } else {
            System.out.println("Cancelled.");
        }
    }

    private void sellFlow() {
        System.out.print("Symbol to SELL: ");
        String symbol = in.nextLine().trim().toUpperCase(Locale.ROOT);
        System.out.print("Shares: ");
        int qty = Integer.parseInt(in.nextLine().trim());

        double price = market.getPrice(symbol);
        double proceeds = price * qty;
        System.out.printf("Confirm SELL %d %s @ %s = %s ? (y/n): ",
                qty, symbol, fmt(price), fmt(proceeds));
        if (yes()) {
            portfolio.sell(symbol, qty, market);
            System.out.println("✅ Sold. Cash now: $" + fmt(portfolio.getCash()));
        } else {
            System.out.println("Cancelled.");
        }
    }

    private void showPortfolio() {
        System.out.println("\n--- Portfolio ---");
        System.out.println("Cash: $" + fmt(portfolio.getCash()));
        System.out.printf("%-6s %8s %10s %12s %12s %10s%n",
                "SYM", "SHARES", "AVG COST", "PRICE", "MKT VALUE", "P/L%");
        double totalHoldings = 0.0;
        for (Holding h : portfolio.getHoldings().values()) {
            double price = market.getPrice(h.symbol);
            double value = h.marketValue(price);
            totalHoldings += value;
            double plPct = (h.avgCost > 0) ? ((price - h.avgCost) / h.avgCost * 100.0) : 0;
            System.out.printf("%-6s %8d %10s %12s %12s %9.2f%%%n",
                    h.symbol, h.shares, fmt(h.avgCost), fmt(price), fmt(value), plPct);
        }
        double total = portfolio.getCash() + totalHoldings;
        System.out.println("Total Value: $" + fmt(total));

        System.out.println("\nRecent Transactions:");
        List<Transaction> txs = portfolio.getTransactions();
        int from = Math.max(0, txs.size() - 8);
        for (int i = txs.size() - 1; i >= from; i--) {
            System.out.println("  " + txs.get(i));
        }
    }

    private void showPerformance() {
        System.out.println("\n--- Performance (latest 10 points) ---");
        List<PerformancePoint> pts = portfolio.getPerformance();
        int start = Math.max(0, pts.size() - 10);
        double base = pts.get(0).totalValue;
        for (int i = start; i < pts.size(); i++) {
            PerformancePoint p = pts.get(i);
            double chg = (p.totalValue - base) / base * 100.0;
            System.out.printf("%s | %s | %+.2f%%%n",
                    p.time.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    fmt(p.totalValue), chg);
        }
    }

    private void saveFlow() throws IOException {
        Storage.save(SAVE_FILE, new SaveData(portfolio, market));
        System.out.println("💾 Saved to " + SAVE_FILE);
    }

    private void loadFlow() throws IOException, ClassNotFoundException {
        SaveData data = Storage.load(SAVE_FILE);
        this.portfolio = data.portfolio;
        this.market = data.market;
        System.out.println("📂 Loaded from " + SAVE_FILE);
    }

    private void autoSaveOnExit() {
        try {
            Storage.save(SAVE_FILE, new SaveData(portfolio, market));
            System.out.println("Auto-saved to " + SAVE_FILE);
        } catch (Exception ignored) { }
    }

    private boolean yes() {
        String ans = in.nextLine().trim().toLowerCase(Locale.ROOT);
        return ans.startsWith("y");
    }

    private static String fmt(double v) { return String.format(Locale.US, "%,.2f", v); }

    private static String truncate(String s, int n) {
        if (s.length() <= n) return s;
        return s.substring(0, Math.max(0, n - 1)) + "…";
    }

    public static void main(String[] args) {
        new StockTradingplatform().run();
    }
}
